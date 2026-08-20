package com.devops.agent.domain.tools;

import com.devops.agent.application.runtime.ToolRuntimeManager;
import com.devops.agent.domain.biz.entity.TicketEnums;
import com.devops.agent.domain.biz.service.TicketService;
import com.devops.agent.domain.rag.HybridRetrieverService;
import com.devops.agent.domain.tools.ToolRiskLevel;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.List;

/**
 * M6 - 白名单工具模块(L2 幻觉防护)
 * <p>
 * 职责: 只暴露两个 @Tool,物理隔离 Shell/文件系统
 * ⚠️ 必须交给 Spring IOC 托管,通过 .tools(devOpsToolsBean) 注入
 * 严禁 new DevOpsTools(),否则 @Autowired 字段为 null
 * <p>
 * MVP-3: 集成 @ToolMeta 元数据 + ToolRuntimeManager 治理
 * </p>
 *
 * @author OpsBrain AI
 * @since 2026-07-15
 */
@Component
public class DevOpsTools {

    private static final Logger log = LoggerFactory.getLogger(DevOpsTools.class);

    @Autowired
    private HybridRetrieverService hybridRetrieverService;

    @Autowired
    private TicketService ticketService;

    @Autowired
    private ToolParameterValidator validator;

    @Autowired
    private ToolRuntimeManager toolRuntimeManager;

    /**
     * 工具1: 检索运维知识库
     * <p>
     * L4 熔断: HybridRetrieverService 内部已做 Score < 0.73 过滤
     * 元数据: READ_ONLY、幂等、可重试、无需审批
     * </p>
     *
     * @param keyword 精炼检索关键词(从用户问题提取)
     * @return 检索到的知识片段,若无匹配返回兜底话术
     */
    @Tool("检索 K8s/阿里云 SLB 等运维手册文档切片")
    @ToolMeta(
            name = "searchDevOpsKnowledge",
            description = "检索 K8s/阿里云 SLB 等运维手册文档切片",
            riskLevel = ToolRiskLevel.READ_ONLY,
            idempotent = true,
            idempotencyKey = "#keyword",
            requiresApproval = false,
            timeoutMs = 10000,
            maxRetries = 2
    )
    public String searchDevOpsKnowledge(@P("精炼检索关键词") String keyword) {
        log.info("[Tool] searchDevOpsKnowledge 被调用,keyword={}", keyword);

        // L3 Schema 校验
        try {
            validator.validateSearchKeyword(keyword);
        } catch (IllegalArgumentException e) {
            log.warn("[Tool] 检索关键词校验失败: {}", e.getMessage());
            throw e; // 抛给 LangChain4j,触发模型自愈重试
        }

        // 通过 ToolRuntimeManager 执行（治理：幂等、超时、重试、熔断、审计）
        try {
            // 用 getDeclaredMethod 而非 getMethod：后者只找 public 方法，
            // 内部实现方法一旦改为包级私有就会抛 NoSuchMethodException，
            // 且被下方 catch 包装成「检索工具执行失败」——模型连续重试全失败，
            // 最终给出不含知识库内容的泛泛回答，用户无从察觉检索没跑通。
            Method method = DevOpsTools.class.getDeclaredMethod("searchDevOpsKnowledgeInternal", String.class);
            method.setAccessible(true);
            return (String) toolRuntimeManager.executeTool("searchDevOpsKnowledge", this, method, new Object[]{keyword});
        } catch (NoSuchMethodException e) {
            // 方法签名不匹配属编码错误，必须显式区别于运行时故障
            log.error("[Tool] 检索内部方法签名不匹配，这是编码错误: {}", e.getMessage());
            throw new IllegalStateException("检索工具装配错误: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("[Tool] searchDevOpsKnowledge 执行异常: {}", e.getMessage(), e);
            throw new RuntimeException("检索工具执行失败: " + e.getMessage(), e);
        }
    }

    /**
     * 内部检索实现（业务逻辑，无治理代码）
     */
    // 必须是 public：ToolRuntimeManager 用 Class.getMethod() 反射调用，
    // 而 getMethod 只能找到 public 方法。包级私有会抛 NoSuchMethodException，
    // 被包装为「检索工具执行失败」——模型会连续重试数次仍全部失败，
    // 最终给出不含任何知识库内容的泛泛回答，且用户无从察觉检索根本没跑通。
    public String searchDevOpsKnowledgeInternal(String keyword) {
        // 调用混合检索(内部已做 L4 熔断)。
        // 用 retrieveWithSource 而非 retrieve：后者丢弃 doc_title/section_header，
        // 导致下面拼出的片段没有出处，模型无法满足 System Prompt 的
        // 强制溯源要求，进而误答「知识库暂无相关文档」——
        // 即便检索实际已命中。详见 RetrievedChunk 类注释。
        List<com.devops.agent.domain.rag.RetrievedChunk> chunks =
                hybridRetrieverService.retrieveWithSource(keyword, 3);

        if (chunks == null) {
            // 检索链路故障（向量化/检索服务不可用）——不是「无文档」。
            // 必须与 L4 熔断的「无相关文档」话术区分：前者是链路故障，
            // 引导用户重试；后者才引导用户补文档。伪装成无文档会误导排查方向。
            log.error("[Tool] 检索服务暂不可用,keyword={}（链路故障，非内容缺失）", keyword);
            return "⚠️ 知识库检索服务暂不可用，请稍后重试。\n" +
                    "（本次是检索链路故障，并非知识库中没有相关内容，请勿据此下结论）";
        }

        if (chunks.isEmpty()) {
            // L4 熔断兜底话术
            String fallback = String.format(
                    "📚 抱歉,知识库中未找到与 '%s' 高度相关的文档(相似度 < 0.73)。\n" +
                            "建议:\n" +
                            "1. 尝试换用更通用的关键词(如'K8s Pod'、'SLB 健康检查')\n" +
                            "2. 若为紧急故障,可直接提工单转人工排查\n" +
                            "3. 检查是否存在知识库文档遗漏,可联系运维团队补充文档",
                    keyword);
            log.warn("[Tool] L4 熔断触发,keyword={} 未命中高相似度文档", keyword);
            return fallback;
        }

        // 拼接检索结果。
        // 每个片段前置出处标签，且格式与 System Prompt 要求的
        // 【来源：文档标题 - 章节】完全一致——让模型可以直接照抄，
        // 而非自行拼装（自行拼装容易编造章节名）。
        StringBuilder result = new StringBuilder();
        result.append("📚 检索到以下知识片段（共 ").append(chunks.size()).append(" 段）:\n\n");
        for (int i = 0; i < chunks.size(); i++) {
            var c = chunks.get(i);
            result.append(String.format("【片段 %d】%s%n%s%n%n", i + 1, c.citation(), c.text()));
        }
        result.append("💡 以上是知识库的真实内容，请基于它作答。\n")
              .append("每条建议后附上对应片段的来源标签（已在片段标题处给出，直接引用即可）。\n")
              .append("⚠️ 检索已命中上述文档，不要回答「知识库暂无相关文档」。");

        log.info("[Tool] searchDevOpsKnowledge 返回 {} 个片段 | 出处={}",
                chunks.size(),
                chunks.stream().map(com.devops.agent.domain.rag.RetrievedChunk::docTitle).distinct().toList());
        return result.toString();
    }

    /**
     * 工具2: 提交运维工单（Single Writer 模式）
     * <p>
     * <b>本工具不写库</b>。它只做参数校验并产出结构化草稿
     * （{@link TicketDraft}），由编排层作为唯一写入者落库。
     * 见 {@link TicketDraft} 类注释了解此设计解决的三个问题。
     * </p>
     * <p>
     * L3 Schema 校验：参数不合格抛 IllegalArgumentException，
     * 触发模型自愈重试。
     * </p>
     *
     * @param title       工单标题
     * @param priority    优先级 [P0/P1/P2/P3]
     * @param module      故障模块 [K8S/ALIYUN_SLB/MYSQL/NETWORK/OTHER]
     * @param description 故障描述与堆栈摘要
     * @return 提交确认文本（含供编排层解析的草稿标记块）
     */
    @Tool("用户需要开工单/上报二级运维时调用")
    @ToolMeta(
            name = "createDevOpsTicket",
            description = "用户需要开工单/上报二级运维时调用",
            riskLevel = ToolRiskLevel.CONTROLLED_WRITE,
            idempotent = true,
            idempotencyKey = "#title + '_' + #priority + '_' + #module",
            requiresApproval = false, // P0 优先级由编排层在写前动态判断
            compensationAction = "voidTicket",
            timeoutMs = 15000,
            maxRetries = 1
    )
    public String createDevOpsTicket(
            @P("工单标题") String title,
            @P("优先级：P0=生产宕机/核心业务不可用，P1=影响业务但有临时方案，P2=需处理但不紧急，P3=优化建议") String priority,
            @P("故障模块 [K8S/ALIYUN_SLB/MYSQL/NETWORK/OTHER]") String module,
            @P("故障描述与堆栈摘要") String description) {

        log.info("[Tool] createDevOpsTicket 被调用,title={}, priority={}, module={}", title, priority, module);

        // L3 Schema 校验：失败抛给 LangChain4j 触发模型自愈重试
        try {
            validator.validateTicketParams(title, priority, module, description);
        } catch (IllegalArgumentException e) {
            log.warn("[Tool] 工单参数校验失败: {}", e.getMessage());
            throw e;
        }

        // 归一化到四档 P0~P3（兼容模型传入的旧值 HIGH/MEDIUM/LOW 与 URGENT 别名）
        String normalizedPriority = TicketEnums.Priority.normalize(priority);
        String normalizedModule = module.trim().toUpperCase();

        // P0 优先级标记为需审批，由编排层决定是否在写前拦截。
        // B0 前此处判定 "HIGH"，四档迁移后 HIGH 不再是合法值——
        // 若不改，审批标记会永久为 false，一个安全阀门静默失效。
        boolean needsApproval = TicketEnums.Priority.P0.equals(normalizedPriority);
        if (needsApproval) {
            log.warn("[Tool] P0 优先级工单已标记需审批 | title={}", title);
        }

        TicketDraft draft = new TicketDraft(
                title.trim(), normalizedPriority, normalizedModule,
                description.trim(), needsApproval);

        log.info("[Tool] 工单草稿已产出（未落库，待编排层写入）| title={} | needsApproval={}",
                title, needsApproval);

        // 返回给模型的文本：明确告知工单号由系统分配，禁止编造。
        // 这消除了一条幻觉路径——此前模型能看到真实工单号，可能复述错误。
        return String.format("""
                ✅ 工单提交成功，正在创建。

                标题: %s
                优先级: %s
                模块: %s

                ⚠️ 工单号由系统分配后直接展示给用户，你无需也不要提供工单号。
                请仅告知用户工单已提交，二级运维团队将在 30 分钟内响应。
                %s""",
                title, normalizedPriority, normalizedModule, draft.toMarkerBlock());
    }

    /**
     * 补偿动作：作废工单
     * <p>
     * 对应 {@code @ToolMeta.compensationAction = "voidTicket"}，
     * 由 {@code SagaCompensationManager} 在同 Saga 后续步骤失败时反射调用。
     * </p>
     * <p>
     * 约定：补偿方法签名必须为 {@code (String businessKey) -> String}。
     * </p>
     * <p>
     * <b>不是</b>物理删除：状态置 VOID 并追加补偿原因，保留审计痕迹，
     * 且使补偿本身可重试（幂等）。
     * </p>
     *
     * @param ticketNo 工单号（Saga 记录的 businessKey）
     * @return 补偿结果描述
     * @throws IllegalStateException 工单不存在时抛出，触发 MANUAL_INTERVENTION_REQUIRED
     */
    public String voidTicket(String ticketNo) {
        log.warn("↩️ [Tool] 补偿动作：作废工单 | ticketNo={}", ticketNo);
        // 异常向上抛出，由 SagaCompensationManager 捕获并标记补偿失败
        return ticketService.voidTicket(ticketNo, "Saga 自动补偿：同事务后续步骤失败");
    }
}
