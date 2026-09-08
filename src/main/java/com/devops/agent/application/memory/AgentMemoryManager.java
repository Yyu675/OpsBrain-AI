package com.devops.agent.application.memory;

import com.devops.agent.domain.memory.KeyFacts;
import com.devops.agent.domain.memory.SessionSummary;
import com.devops.agent.domain.memory.SummaryDistiller;
import com.devops.agent.infrastructure.cache.HotMemoryStore;
import com.devops.agent.infrastructure.persistence.repo.SessionSummaryRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Agent 记忆管理器（三层记忆统一门面）
 * <p>
 * 参考 Agent Methodology §6：
 * <table border="1">
 *   <tr><th>层级</th><th>存储</th><th>内容</th><th>特点</th></tr>
 *   <tr><td>热 Hot</td><td>Redis</td><td>最近 N 轮对话、会话统计</td><td>低延迟、TTL 滑动续期</td></tr>
 *   <tr><td>温 Warm</td><td>PostgreSQL</td><td>会话摘要、关键事实蒸馏</td><td>可持久化、可审计、可续聊</td></tr>
 *   <tr><td>冷 Cold</td><td>归档文件</td><td>历史全量</td><td>成本低、不进上下文</td></tr>
 * </table>
 * </p>
 * <p>
 * 记忆治理原则（严格遵守）：
 * <ul>
 *   <li>不把全量历史直接塞给模型 —— 热记忆滑动窗口 + 温记忆事实蒸馏</li>
 *   <li>不让模型自己决定什么是事实 —— 硬事实由正则抽取</li>
 *   <li>会话总结是可执行事实蒸馏，不是文学概括</li>
 *   <li>任一层失败都降级而非阻塞主流程</li>
 * </ul>
 *
 * @author OpsBrain AI
 * @since 2026-08-08
 */
@Slf4j
@Component
public class AgentMemoryManager {

    private final HotMemoryStore hotMemory;
    private final com.devops.agent.infrastructure.persistence.repo.ConversationTurnRepository turnRepo;
    private final SessionSummaryRepository summaryRepo;
    private final SummaryDistiller distiller;
    private final ObjectMapper objectMapper;

    public AgentMemoryManager(HotMemoryStore hotMemory,
                              SessionSummaryRepository summaryRepo,
                              SummaryDistiller distiller,
                              ObjectMapper objectMapper,
                              com.devops.agent.infrastructure.persistence.repo.ConversationTurnRepository turnRepo) {
        this.hotMemory = hotMemory;
        this.turnRepo = turnRepo;
        this.summaryRepo = summaryRepo;
        this.distiller = distiller;
        this.objectMapper = objectMapper;
    }

    // ==================== 读取：构建上下文 ====================

    /**
     * 加载会话上下文，供 Prompt 注入
     * <p>
     * 组合策略：温记忆的关键事实（压缩后的历史结论）+ 热记忆的最近对话（原文）。
     * 前者提供长期记忆但 Token 占用低，后者保证近期对话连贯。
     * </p>
     *
     * @param sessionId 会话 ID
     * @return 上下文，无历史时各字段为空集合
     */
    public MemoryContext loadContext(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return MemoryContext.empty();
        }

        // 热记忆：最近 N 轮对话原文
        List<String> recentHistory = hotMemory.loadHistoryAsText(sessionId);

        // 温记忆：关键事实（跨会话长期记忆）
        String factsText = null;
        try {
            SessionSummary summary = summaryRepo.findBySessionId(sessionId);
            if (summary != null && summary.getKeyFacts() != null) {
                String t = summary.getKeyFacts().toPromptText();
                if (t != null && !t.isBlank()) {
                    factsText = t;
                }
            }
        } catch (Exception e) {
            log.warn("⚠️ [Memory] 温记忆加载失败（降级为仅热记忆）| sessionId={} | {}", sessionId, e.getMessage());
        }

        log.debug("🧠 [Memory] 上下文加载 | sessionId={} | 热记忆={}条 | 温记忆事实={}",
                sessionId, recentHistory.size(), factsText != null ? "有" : "无");

        return new MemoryContext(recentHistory, factsText);
    }

    // ==================== 写入：每轮对话 ====================

    /**
     * 记录用户提问到热记忆
     */
    public void recordUserTurn(String sessionId, String query) {
        hotMemory.appendMessage(sessionId, "user", query);
    }

    /**
     * 记录一轮完整对话，并更新温记忆
     * <p>
     * 在 {@code onCompleteResponse} 中调用。执行：
     * <ol>
     *   <li>AI 回答写入热记忆</li>
     *   <li>累积会话统计（轮次/Token/成本）</li>
     *   <li>蒸馏本轮关键事实，与历史事实合并后 UPSERT 到温记忆</li>
     * </ol>
     * 全程异常降级，不影响对话主流程。
     * </p>
     *
     * @param sessionId   会话 ID
     * @param traceId     本次请求 traceId
     * @param userQuery   用户提问
     * @param aiAnswer    AI 回答
     * @param toolResults 工具执行结果
     * @param tokens      本轮 Token 消耗
     * @param costRmb     本轮成本
     * @param finalState  状态机终态
     */
    public void recordCompletedTurn(String sessionId, String traceId,
                                    String userQuery, String aiAnswer,
                                    List<Map<String, Object>> toolResults,
                                    int tokens, double costRmb, String finalState) {
        if (sessionId == null || sessionId.isBlank()) return;

        try {
            // 1. 热记忆：AI 回答 + 统计累积
            hotMemory.appendMessage(sessionId, "assistant", aiAnswer);
            hotMemory.accumulateStats(sessionId, tokens, costRmb);

            // 2. 蒸馏本轮事实
            KeyFacts fresh = distiller.distill(userQuery, aiAnswer, toolResults);

            // 3. 与温记忆已有事实合并
            SessionSummary existing = summaryRepo.findBySessionId(sessionId);
            KeyFacts merged = distiller.merge(
                    existing != null ? existing.getKeyFacts() : null,
                    fresh
            );

            // 4. 轮次数从 DB 推导（P2-31）：避免 Redis 不可用时显示「0 轮」
            int totalTurnCount = (existing != null ? existing.getTurnCount() : 0) + 1;

            SessionSummary s = new SessionSummary();
            s.setSessionId(sessionId);
            s.setTraceId(traceId);
            s.setSummary(distiller.buildSummaryText(merged, totalTurnCount));
            s.setKeyFacts(merged);
            // UPSERT 中 turn/token/cost 是累加语义，故只传本轮增量
            s.setTurnCount(1);
            s.setTotalTokens(tokens);
            s.setTotalCostRmb(costRmb);
            s.setFinalState(finalState);
            s.setRelatedTickets(extractTicketIds(toolResults));

            summaryRepo.upsert(s);

            log.debug("🧠 [Memory] 温记忆已更新 | sessionId={} | 事实数={} | 累计{}轮",
                    sessionId, merged.getConfirmedFacts().size(), totalTurnCount);

            // 5. 对话原文转写（B-2 冷归档补全）
            //
            // 为什么放在这里而不是 SSE 主流程：本方法已经拿到 userQuery 与
            // aiAnswer 全文，且整体包在 try-catch 里——原文转写天然属于
            // 「每轮收尾」这件事，不需要动 SSE 回调。
            //
            // 为什么需要它：热记忆（Redis）TTL 仅 120 分钟，而冷归档按天执行，
            // 归档时原文早已过期。此前冷归档只能存摘要并如实标注
            // contentScope=SUMMARY_ONLY，而合规审计与模型评测取数都要逐轮原文。
            //
            // 单独 try-catch 而非并入上面那个：转写失败不该让「温记忆已更新」
            // 这件已完成的事被日志描述成失败。
            persistTurnQuietly(sessionId, traceId, userQuery, aiAnswer,
                    toolResults, tokens, costRmb, finalState);

        } catch (Exception e) {
            // 记忆写入失败不影响用户已收到的回答
            log.warn("⚠️ [Memory] 记录对话失败（不影响主流程）| sessionId={} | {}", sessionId, e.getMessage());
        }
    }

    // ==================== 会话管理 ====================

    /**
     * 查询最近会话列表（供前端「历史会话」）
     */
    public List<SessionSummary> listRecentSessions(String tenantId, int limit, int offset) {
        try {
            return summaryRepo.findRecent(tenantId != null ? tenantId : "default", limit, offset);
        } catch (Exception e) {
            log.warn("⚠️ [Memory] 查询会话列表失败 | {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 统计会话总数
     */
    public long countSessions(String tenantId) {
        try {
            return summaryRepo.countByTenant(tenantId != null ? tenantId : "default");
        } catch (Exception e) {
            return 0L;
        }
    }

    /**
     * 清除会话热记忆（温记忆保留，仍可续聊）
     */
    public void clearHotMemory(String sessionId) {
        hotMemory.evict(sessionId);
    }

    // ==================== 内部工具 ====================

    /**
     * 从工具结果提取工单号列表
     */
    /**
     * 转写对话原文——失败只记日志，绝不影响已完成的记忆写入。
     *
     * <p>轮次序号从库里推导而非用上面的 {@code totalTurnCount}：
     * 后者来自温记忆的累加值，而温记忆与原文表是两条独立写入路径，
     * 任一条曾经失败过，两者的轮次就会错位——用错位的序号写入会撞唯一键
     * 而被静默跳过。以原文表自身的 MAX+1 为准，它才是这张表的事实。</p>
     */
    private void persistTurnQuietly(String sessionId, String traceId,
                                    String userQuery, String aiAnswer,
                                    List<Map<String, Object>> toolResults,
                                    int tokens, double costRmb, String finalState) {
        try {
            String toolJson = null;
            if (toolResults != null && !toolResults.isEmpty()) {
                try {
                    toolJson = objectMapper.writeValueAsString(toolResults);
                } catch (Exception e) {
                    // 工具结果序列化失败不应拖累问答原文——那才是主要内容。
                    // 置 null 并留痕，而不是整轮放弃
                    log.warn("⚠️ [Memory] 工具结果序列化失败，原文仍会写入（tool_results 置空）"
                            + " | sessionId={} | {}", sessionId, e.getMessage());
                }
            }
            int seq = turnRepo.nextTurnSeq(sessionId);
            turnRepo.append(sessionId, traceId, seq, userQuery, aiAnswer,
                    toolJson, tokens, costRmb, finalState);
        } catch (Exception e) {
            log.warn("⚠️ [Memory] 对话原文转写失败（不影响主流程与温记忆）| sessionId={} | {}",
                    sessionId, e.getMessage());
        }
    }

    private String extractTicketIds(List<Map<String, Object>> toolResults) {
        if (toolResults == null || toolResults.isEmpty()) return "[]";
        List<String> ids = new ArrayList<>();
        for (Map<String, Object> tr : toolResults) {
            Object result = tr.get("result");
            if (result instanceof Map<?, ?> m) {
                Object tid = m.get("ticketId");
                if (tid != null) ids.add(tid.toString());
            }
        }
        try {
            return objectMapper.writeValueAsString(ids);
        } catch (Exception e) {
            return "[]";
        }
    }

    // ==================== 返回类型 ====================

    /**
     * 记忆上下文
     *
     * @param recentHistory 热记忆：最近对话原文（时间正序）
     * @param keyFactsText  温记忆：关键事实渲染文本，无则为 null
     */
    public record MemoryContext(List<String> recentHistory, String keyFactsText) {

        public static MemoryContext empty() {
            return new MemoryContext(List.of(), null);
        }

        public boolean hasHistory() {
            return !recentHistory.isEmpty() || (keyFactsText != null && !keyFactsText.isBlank());
        }
    }
}