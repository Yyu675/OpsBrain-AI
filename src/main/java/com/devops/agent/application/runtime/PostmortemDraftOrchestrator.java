package com.devops.agent.application.runtime;

import com.devops.agent.common.audit.OperationAuditRecord;
import com.devops.agent.common.context.TraceContext;
import com.devops.agent.domain.biz.entity.DevOpsTicket;
import com.devops.agent.domain.biz.entity.TicketPostmortem;
import com.devops.agent.domain.biz.entity.TicketReply;
import com.devops.agent.domain.biz.service.TicketPostmortemService;
import com.devops.agent.domain.biz.service.TicketService;
import com.devops.agent.domain.rag.KnowledgeDoc;
import com.devops.agent.domain.rag.KnowledgeDocService;
import com.devops.agent.infrastructure.concurrent.ManagedExecutors;
import com.devops.agent.infrastructure.persistence.repo.OperationAuditRepository;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

/**
 * 复盘自动沉淀知识草稿编排（飞轮最后一圈，2026-09-24）。
 *
 * <p>
 * 触发链：保存复盘归档（{@code PUT /{id}/postmortem}）成功 →
 * {@link #submitDraftAsync} → 单线程池异步 → 守卫 → 生成 RCA 正文 →
 * 以 {@code DRAFT} 状态入库（<b>不发布、不向量化、不进检索</b>）→
 * 人工在知识库审核后手动发布。AI 不替人决策——自动的只是「把草稿
 * 摆到审核台上」这一步。
 * </p>
 *
 * <h3>四道幂等守卫（每道都缺一不可）</h3>
 * <ol>
 *   <li><b>开关</b>：{@code devops.knowledge.postmortem-auto-draft-enabled}，
 *       关闭时连队列都不进；</li>
 *   <li><b>在途去重</b>：同一工单 LLM 生成期间（秒级）的重复保存不再入队
 *       ——编辑复盘会连续触发保存，没有它会并发烧两次 LLM；</li>
 *   <li><b>回链去重</b>：该工单已有回链文档（沉淀抽屉发过 / 自动草稿已建）
 *       即跳过——每工单至多一篇自动草稿；</li>
 *   <li><b>内容守卫</b>：LLM 输出缺「## 故障现象」「## 根因分析」章节
 *       视为不合格（含 MOCK 模型的固定假数据），回落结构化模板。</li>
 * </ol>
 *
 * <h3>失败方向</h3>
 * 复盘保存绝不因本编排失败而失败：全部异常收在本类内（warn 级留痕）。
 * 同 {@code HybridRetrieverService} 对 boost 的立场——回流是增值冒险，
 * 不是主链的筋骨。
 *
 * <p>结构化模板（LLM 不可用/不合格时的回落）不编造任何事实：
 * 取不到的章节一律写「待补充」，与 PRD 诚实原则一致。</p>
 */
@Component
public class PostmortemDraftOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(PostmortemDraftOrchestrator.class);

    private static final String OPERATOR = "postmortem-auto";
    private static final String KNOWLEDGE_SOURCE = "postmortem-auto";
    private static final String SOURCE_TYPE = "TICKET";

    /** prompt 上限：复盘正文是长文，但喂给 LLM 的上下文要截断防爆 token */
    private static final int MAX_PROMPT_DESC = 4000;
    private static final int MAX_PROMPT_TIMELINE = 8000;
    private static final int MAX_PROMPT_REPLY = 2000;

    /**
     * 系统提示词——与前端沉淀抽屉 {@code buildSinkQuery} 的六章节要求逐字对齐。
     * 两处必须保持同构：用户在抽屉里点一次生成与后端自动草稿产出的
     * 是同一种文档，章节结构漂移会让审核者面对两种长相的「复盘」。
     */
    private static final String RCA_SYSTEM_PROMPT = """
            你是企业级运维助手。把给定的工单处理过程整理为一篇结构化的运维故障复盘（RCA）知识文档，供知识库沉淀。
            要求输出 Markdown，必须包含六个章节，按顺序输出：
            ## 故障现象
            简述问题表现、发生时间、影响的服务与范围。
            ## 影响范围
            评估业务影响程度、持续时长、受影响用户/系统。证据不足写"待补充"。
            ## 根因分析
            根据处理过程推断根本原因，区分"现象"与"根因"。证据不足写"待补充"，不得编造因果。
            ## 处理步骤
            按时间线列出关键操作，执行命令用代码块包裹，标注每步目的。
            ## 预防措施
            给出可执行的预防建议（监控、告警阈值、配置规范等）。
            ## 改进项
            列出后续待跟进事项（如"补充监控指标""更新 SOP"），每条一行。
            不要复述工单号、负责人等元信息，只写技术内容。""";

    private final TicketPostmortemService pmService;
    private final TicketService ticketService;
    private final KnowledgeDocService knowledgeDocService;
    private final OperationAuditRepository auditRepository;

    /** MOCK/dev 下为 MockChatModel；字段名须与 bean 名 {@code turboModel} 一致（按名注入）。 */
    @Autowired(required = false)
    private ChatModel turboModel;

    /** 总开关。字段带默认值——单测手工构造不走 Spring 注解时为 true（同 HealingOrchestrator 惯例）。 */
    @Value("${devops.knowledge.postmortem-auto-draft-enabled:true}")
    private boolean enabled = true;

    /** 在途工单号：LLM 生成秒级窗口内的重复保存不重复入队。 */
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    /** 增值任务走 best-effort 池：满载丢弃 + 告警（丢一篇草稿无伤，压垮主链才伤）。 */
    private final ExecutorService draftExecutor =
            ManagedExecutors.forBestEffort("postmortem-draft", 1, 50);

    public PostmortemDraftOrchestrator(TicketPostmortemService pmService,
                                       TicketService ticketService,
                                       KnowledgeDocService knowledgeDocService,
                                       OperationAuditRepository auditRepository) {
        this.pmService = pmService;
        this.ticketService = ticketService;
        this.knowledgeDocService = knowledgeDocService;
        this.auditRepository = auditRepository;
    }

    @PreDestroy
    public void shutdown() {
        ManagedExecutors.shutdownGracefully(draftExecutor, "postmortem-draft", 5);
    }

    /**
     * 复盘保存成功后的触发入口（controller 调，同步部分只做最便宜的判断）。
     * 重守卫与生成全部在异步任务里——保存接口不等 LLM。
     */
    public void submitDraftAsync(String ticketId) {
        if (!enabled || ticketId == null || ticketId.isBlank()) {
            return;
        }
        // 在途去重放同步侧：连续保存时第二个请求应立刻返回，而不是排队烧 LLM
        if (!inFlight.add(ticketId)) {
            log.debug("[PostmortemDraft] 工单生成在途，本次保存跳过 | ticketId={}", ticketId);
            return;
        }
        try {
            draftExecutor.submit(TraceContext.wrap(() -> {
                try {
                    generateSafe(ticketId);
                } finally {
                    inFlight.remove(ticketId);
                }
            }));
        } catch (Exception ex) {
            // 池满（Discard 策略下 submit 不抛，这里兜的是其他异常）——不能让
            // 异常冒到 controller：复盘本体已保存成功，草稿丢了只是少一篇增值
            inFlight.remove(ticketId);
            log.warn("⚠️ [PostmortemDraft] 草稿任务入队失败（复盘保存不受影响）| ticketId={} | {}",
                    ticketId, ex.getMessage());
        }
    }

    /** 异步任务收口：任何异常只 warn，绝不向外冒（在途标记由外层 finally 摘除）。 */
    private void generateSafe(String ticketId) {
        try {
            generateNow(ticketId);
        } catch (Exception ex) {
            log.warn("⚠️ [PostmortemDraft] 自动沉淀草稿失败（复盘不受影响）| ticketId={} | {}",
                    ticketId, ex.getMessage());
        }
    }

    /**
     * 生成一篇 DRAFT 知识文档（同步可测；所有守卫在此内）。
     *
     * @return 生成的文档 id；任何守卫未过 / 失败返回 null
     */
    Long generateNow(String ticketId) {
        if (!enabled || ticketId == null || ticketId.isBlank()) {
            return null;
        }
        TicketPostmortem pm = pmService.getPostmortem(ticketId);
        if (pm == null) {
            return null;
        }
        // 回链去重：抽屉发过或自动草稿已建 → 每工单至多一篇
        if (!knowledgeDocService.findBySourceTicketId(ticketId).isEmpty()) {
            log.debug("[PostmortemDraft] 该工单已有回链文档，跳过 | ticketId={}", ticketId);
            return null;
        }
        DevOpsTicket ticket = ticketService.getTicketWithTags(ticketId);
        if (ticket == null) {
            log.warn("⚠️ [PostmortemDraft] 复盘存在但工单查不到，跳过 | ticketId={}", ticketId);
            return null;
        }

        String content = buildContent(ticket, pm);

        KnowledgeDoc doc = new KnowledgeDoc();
        doc.setTitle(truncate("【故障复盘】" + ticket.getTitle(), 255));
        doc.setCategory(ticket.getModule());
        doc.setContent(content);
        // 审稿人视角的作者 = 归档复盘的人；operator（操作记录）= 自动通道
        doc.setAuthor(pm.getAuthor());
        doc.setKnowledgeSource(KNOWLEDGE_SOURCE);
        doc.setSourceTicketId(ticketId);
        doc.setSourceType(SOURCE_TYPE);

        KnowledgeDocService.SaveResult r;
        try {
            r = knowledgeDocService.create(doc, List.of("故障复盘"), false, OPERATOR);
        } catch (KnowledgeDocService.DuplicateContentException dup) {
            // 并发窗口：另一次保存的草稿刚建成——是「已经有了」不是失败，回链守卫下次生效
            log.info("[PostmortemDraft] 内容与既有文档重复，视为已沉淀 | ticketId={} | {}",
                    ticketId, dup.getMessage());
            return null;
        }

        auditDocCreate(ticketId, r.docId());
        log.info("📝 [PostmortemDraft] 复盘知识草稿已入库（DRAFT 待审核）| ticketId={} | docId={}",
                ticketId, r.docId());
        return r.docId();
    }

    // ==================== 正文生成 ====================

    private String buildContent(DevOpsTicket ticket, TicketPostmortem pm) {
        if (turboModel != null) {
            try {
                ChatResponse resp = turboModel.chat(ChatRequest.builder()
                        .messages(List.of(
                                SystemMessage.from(RCA_SYSTEM_PROMPT),
                                UserMessage.from(buildPrompt(ticket, pm))))
                        .build());
                String text = resp == null || resp.aiMessage() == null
                        ? null : resp.aiMessage().text();
                if (looksLikeRcaDoc(text)) {
                    return text.trim();
                }
                log.debug("[PostmortemDraft] LLM 输出不含必需章节，回落结构化模板");
            } catch (Exception ex) {
                // MOCK 模式恒走这里（debug 级防噪声风暴，同 LlmHypothesisGenerator）
                log.debug("[PostmortemDraft] LLM 调用失败，回落结构化模板: {}", ex.getMessage());
            }
        }
        return templateContent(ticket, pm);
    }

    private String buildPrompt(DevOpsTicket t, TicketPostmortem pm) {
        StringBuilder sb = new StringBuilder(2048);
        sb.append("【工单标题】").append(t.getTitle()).append('\n');
        sb.append("【服务】").append(nullSafe(t.getModule())).append('\n');
        sb.append("【优先级】").append(nullSafe(t.getPriority())).append('\n');
        sb.append("【描述】").append(truncate(nullSafe(t.getDescription()), MAX_PROMPT_DESC)).append('\n');
        if (t.getRootCause() != null && !t.getRootCause().isBlank()) {
            sb.append("【根因分类】").append(nullSafe(t.getRootCauseCategory())).append('\n');
            sb.append("【根因】").append(t.getRootCause()).append('\n');
        }
        if (t.getVerifyConclusion() != null && !t.getVerifyConclusion().isBlank()) {
            sb.append("【验证结论】").append(t.getVerifyConclusion()).append('\n');
        }
        List<TicketReply> replies = ticketService.listReplies(t.getId());
        if (replies != null && !replies.isEmpty()) {
            sb.append("【处理回复】\n");
            for (TicketReply r : replies) {
                if (r.getContent() == null) continue;
                sb.append('[').append(r.getCreateTime()).append("][")
                  .append(nullSafe(r.getAuthor())).append("] ")
                  .append(truncate(r.getContent(), MAX_PROMPT_REPLY)).append('\n');
            }
        }
        String timeline = pm.getTimeline();
        if (timeline == null || timeline.isBlank()) {
            timeline = pmService.generateTimelineDraft(t.getId());
        }
        sb.append("【处置时间线】\n")
          .append(truncate(timeline, MAX_PROMPT_TIMELINE)).append('\n');
        if (pm.getImpactScope() != null && !pm.getImpactScope().isBlank()) {
            sb.append("【已归档·影响范围】").append(pm.getImpactScope()).append('\n');
        }
        if (pm.getLessons() != null && !pm.getLessons().isBlank()) {
            sb.append("【已归档·经验教训】").append(pm.getLessons()).append('\n');
        }
        return sb.toString();
    }

    /**
     * 合格判据：必须含「故障现象」与「根因分析」两个章节标题。
     * MOCK 模型返回固定假数据不含它们——不合格即回落模板，
     * dev 环境因此拿到的是结构化真数据而不是假 RCA。
     */
    static boolean looksLikeRcaDoc(String text) {
        return text != null
                && text.contains("## 故障现象")
                && text.contains("## 根因分析");
    }

    /**
     * 结构化模板（无 LLM / LLM 不合格时）：只填结构化字段里真实存在的值，
     * 取不到的写「待补充」——绝不编造。
     */
    private String templateContent(DevOpsTicket t, TicketPostmortem pm) {
        StringBuilder sb = new StringBuilder(2048);
        sb.append("## 故障现象\n");
        sb.append(t.getDescription() != null && !t.getDescription().isBlank()
                ? t.getDescription() : "待补充\n");

        sb.append("\n## 影响范围\n");
        if (pm.getImpactScope() != null && !pm.getImpactScope().isBlank()) {
            sb.append(pm.getImpactScope());
            if (pm.getImpactDuration() != null) {
                sb.append("（持续约 ").append(pm.getImpactDuration()).append(" 分钟）");
            }
            sb.append('\n');
        } else {
            sb.append("待补充\n");
        }

        sb.append("\n## 根因分析\n");
        if (t.getRootCause() != null && !t.getRootCause().isBlank()) {
            if (t.getRootCauseCategory() != null && !t.getRootCauseCategory().isBlank()) {
                sb.append("- 分类：").append(t.getRootCauseCategory()).append('\n');
            }
            sb.append("- 根因：").append(t.getRootCause()).append('\n');
        } else {
            sb.append("待补充（复盘时未确认根因）\n");
        }

        sb.append("\n## 处理步骤\n");
        String timeline = pm.getTimeline();
        if (timeline == null || timeline.isBlank()) {
            timeline = pmService.generateTimelineDraft(t.getId());
        }
        sb.append(timeline).append('\n');

        sb.append("\n## 预防措施\n");
        sb.append(pm.getLessons() != null && !pm.getLessons().isBlank()
                ? pm.getLessons() : "待补充\n");

        sb.append("\n## 改进项\n");
        sb.append("待补充（见工单改进项看板）\n");
        return sb.toString();
    }

    // ==================== 审计旁写 ====================

    /**
     * agent 写路径的审计旁写（同 HealingOrchestrator.auditIfAgent 的立场）：
     * HTTP 通道的知识创建由 OperationAuditInterceptor 覆盖，本自动通道
     * 没有 HTTP 入口，须自行留痕——「谁在什么时候改了什么」人不能缺、
     * agent 更不能缺。旁写失败只告警不阻断（审计通道故障不瘫主链）。
     */
    private void auditDocCreate(String ticketId, Long docId) {
        try {
            auditRepository.save(new OperationAuditRecord(
                    TraceContext.getTraceId(),
                    "agent", OPERATOR,
                    "knowledge.doc.create",
                    "knowledge_doc", String.valueOf(docId),
                    null, null,
                    200, true, null,
                    "复盘自动沉淀草稿 | ticketId=" + ticketId,
                    null, null, null, 0,
                    LocalDateTime.now()));
        } catch (Exception ex) {
            log.warn("⚠️ [PostmortemDraft] 审计旁写失败（不阻断）| ticketId={} | {}",
                    ticketId, ex.getMessage());
        }
    }

    // ==================== 小工具 ====================

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "…（已截断）";
    }
}
