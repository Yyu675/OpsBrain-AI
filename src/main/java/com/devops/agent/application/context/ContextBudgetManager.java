package com.devops.agent.application.context;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Context Budget Manager - 上下文预算管理器
 * <p>
 * 职责：在调用 LLM 前预算 Token 分配，防止上下文爆炸，控制成本，实现显式降级。
 * </p>
 * <p>
 * 核心原则（参考 Agent Methodology §7）：
 * <ol>
 *   <li>必选项优先：System Prompt + User Query + 回答预留空间</li>
 *   <li>可选项按重要级压缩：Evidence (Rerank TopN) > Tool Results > History (摘要)</li>
 *   <li>超预算返回降级标记 + 降级原因，不抛异常</li>
 *   <li>零依赖实现：先用字符数估算（中文 ~1.5 字符/Token），后续可接入 tiktoken</li>
 * </ol>
 *
 * @author OpsBrain AI
 * @since 2026-08-08
 */
@Slf4j
@Component
public class ContextBudgetManager {

    /**
     * 模型上下文窗口大小（从配置读取，默认 qwen-plus 32k）
     */
    @Value("${devops.ai.context-window:32000}")
    private int modelContextWindow;

    /**
     * 回答预留 Token 数（防止模型截断输出）
     */
    @Value("${devops.ai.reserved-response-tokens:1500}")
    private int reservedResponseTokens;

    /**
     * System Prompt 估算 Token 数（固定预估）
     */
    @Value("${devops.ai.system-prompt-tokens:800}")
    private int systemPromptTokens;

    /**
     * 单字符估算 Token 比率（中文约 1.5 字符/Token，英文约 4 字符/Token）
     */
    private static final double CHARS_PER_TOKEN = 1.5;

    /**
     * 历史消息最大保留轮数（预算允许时）
     */
    private static final int MAX_HISTORY_TURNS = 10;

    /**
     * Evidence 最大保留条数（Rerank 后）
     */
    private static final int MAX_EVIDENCE_CHUNKS = 3;

    /**
     * Tool Results 最大保留条数
     */
    private static final int MAX_TOOL_RESULTS = 2;

    /**
     * 预算分配结果
     */
    @lombok.Builder
    @lombok.Data
    public static class BudgetAllocation {
        private final int totalBudget;
        private final int usedTokens;
        private final int remainingTokens;
        private final boolean withinBudget;
        private final String degradationReason;
        private final List<String> includedHistory;      // 实际纳入的历史消息
        private final List<String> includedEvidence;     // 实际纳入的证据片段
        private final List<String> includedToolResults;  // 实际纳入的工具结果
    }

    /**
     * 执行预算分配
     *
     * @param userQuery       用户当前提问
     * @param historyMessages 历史对话消息（最近在前，格式："User: ...\nAssistant: ..."）
     * @param evidenceChunks  检索到的证据片段（已按相关度排序）
     * @param toolResults     工具执行结果
     * @return 预算分配结果，含实际纳入的内容与降级信息
     */
    public BudgetAllocation allocate(
            String userQuery,
            List<String> historyMessages,
            List<String> evidenceChunks,
            List<String> toolResults) {

        int totalBudget = modelContextWindow;
        int reserved = reservedResponseTokens;
        int availableBudget = totalBudget - reserved;

        // 1. 计算必选项 Token
        int systemTokens = systemPromptTokens;
        int queryTokens = estimateTokens(userQuery);
        int mandatoryTokens = systemTokens + queryTokens;

        if (mandatoryTokens > availableBudget) {
            // 极端情况：连必选项都放不下
            log.warn("⚠️ [Budget] 必选项超预算！system={} + query={} > available={}",
                    systemTokens, queryTokens, availableBudget);
            return BudgetAllocation.builder()
                    .totalBudget(totalBudget)
                    .usedTokens(mandatoryTokens)
                    .remainingTokens(0)
                    .withinBudget(false)
                    .degradationReason("QUERY_TOO_LONG: 用户问题过长，超过模型上下文窗口")
                    .includedHistory(List.of())
                    .includedEvidence(List.of())
                    .includedToolResults(List.of())
                    .build();
        }

        int remainingAfterMandatory = availableBudget - mandatoryTokens;

        // 2. 可选项按优先级纳入
        // Priority 1: Evidence (RAG 检索结果) - 最重要，决定回答质量
        List<String> selectedEvidence = selectTopN(evidenceChunks, MAX_EVIDENCE_CHUNKS, remainingAfterMandatory);
        int evidenceTokens = selectedEvidence.stream().mapToInt(this::estimateTokens).sum();
        remainingAfterMandatory -= evidenceTokens;

        // Priority 2: Tool Results (工具执行结果) - 次重要，提供事实支撑
        List<String> selectedToolResults = selectTopN(toolResults, MAX_TOOL_RESULTS, remainingAfterMandatory);
        int toolTokens = selectedToolResults.stream().mapToInt(this::estimateTokens).sum();
        remainingAfterMandatory -= toolTokens;

        // Priority 3: History (历史对话) - 最低优先级，仅作上下文连贯
        List<String> selectedHistory = new ArrayList<>();
        int historyTokens = 0;
        if (remainingAfterMandatory > 0 && historyMessages != null && !historyMessages.isEmpty()) {
            for (String msg : historyMessages) {
                int msgTokens = estimateTokens(msg);
                if (historyTokens + msgTokens <= remainingAfterMandatory && selectedHistory.size() < MAX_HISTORY_TURNS) {
                    selectedHistory.add(0, msg); // 保持时间顺序（旧在前）
                    historyTokens += msgTokens;
                } else {
                    break;
                }
            }
        }

        int usedTokens = mandatoryTokens + evidenceTokens + toolTokens + historyTokens;
        boolean withinBudget = usedTokens <= availableBudget;
        String degradationReason = buildDegradationReason(
                evidenceChunks.size(), selectedEvidence.size(),
                toolResults.size(), selectedToolResults.size(),
                historyMessages != null ? historyMessages.size() : 0, selectedHistory.size(),
                withinBudget);

        log.debug("📊 [Budget] 分配完成 | total={} | used={} | remaining={} | within={} | reason={} | hist={}/{} ev={}/{} tool={}/{}",
                totalBudget, usedTokens, totalBudget - usedTokens, withinBudget, degradationReason,
                selectedHistory.size(), historyMessages != null ? historyMessages.size() : 0,
                selectedEvidence.size(), evidenceChunks.size(),
                selectedToolResults.size(), toolResults.size());

        return BudgetAllocation.builder()
                .totalBudget(totalBudget)
                .usedTokens(usedTokens)
                .remainingTokens(totalBudget - usedTokens)
                .withinBudget(withinBudget)
                .degradationReason(degradationReason)
                .includedHistory(selectedHistory)
                .includedEvidence(selectedEvidence)
                .includedToolResults(selectedToolResults)
                .build();
    }

    /**
     * 简化版分配（仅用户问题 + 证据，用于缓存命中/简单场景）
     */
    public BudgetAllocation allocateSimple(String userQuery, List<String> evidenceChunks) {
        return allocate(userQuery, List.of(), evidenceChunks, List.of());
    }

    /**
     * 选择 Top-N 项，受 Token 预算限制
     */
    private List<String> selectTopN(List<String> candidates, int maxCount, int tokenBudget) {
        List<String> selected = new ArrayList<>();
        int used = 0;
        if (candidates == null) return selected;

        for (String candidate : candidates) {
            if (selected.size() >= maxCount) break;
            int tokens = estimateTokens(candidate);
            if (used + tokens <= tokenBudget) {
                selected.add(candidate);
                used += tokens;
            } else {
                break;
            }
        }
        return selected;
    }

    /**
     * 估算 Token 数（字符数 / 1.5，向上取整）
     * TODO: 后续接入 tiktoken 精确计算
     */
    private int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        // 粗略估算：中文按 1.5 字符/Token，英文按 4 字符/Token
        // 这里统一按 1.5 处理（偏保守，倾向于过估算）
        return (int) Math.ceil(text.length() / CHARS_PER_TOKEN);
    }

    /**
     * 公开的 Token 估算方法（供外部调用）
     */
    public int estimateTokensPublic(String text) {
        return estimateTokens(text);
    }

    /**
     * 构建降级原因说明
     */
    private String buildDegradationReason(
            int totalEvidence, int usedEvidence,
            int totalTools, int usedTools,
            int totalHistory, int usedHistory,
            boolean withinBudget) {

        if (withinBudget) {
            return "OK";
        }

        List<String> reasons = new ArrayList<>();
        if (usedEvidence < totalEvidence) reasons.add("EVIDENCE_TRUNCATED(" + usedEvidence + "/" + totalEvidence + ")");
        if (usedTools < totalTools) reasons.add("TOOL_RESULTS_TRUNCATED(" + usedTools + "/" + totalTools + ")");
        if (usedHistory < totalHistory) reasons.add("HISTORY_TRUNCATED(" + usedHistory + "/" + totalHistory + ")");

        return String.join("; ", reasons);
    }
}