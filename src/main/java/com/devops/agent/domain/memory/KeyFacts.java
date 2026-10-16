package com.devops.agent.domain.memory;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 关键事实蒸馏结果
 * <p>
 * 参考 Agent Methodology §6.4：会话总结不是文学概括，而是<b>可执行事实蒸馏</b>。
 * 所有"关键事实"都尽量结构化，避免让模型自己决定什么是事实。
 * </p>
 * <p>
 * 用途：
 * <ul>
 *   <li>续聊时注入，替代全量历史，压缩上下文</li>
 *   <li>质量复盘时快速定位结论与遗留风险</li>
 *   <li>JSONB 存储支持 GIN 索引检索（如"查所有涉及 OOMKilled 的会话"）</li>
 * </ul>
 *
 * @author OpsBrain AI
 * @since 2026-08-08
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class KeyFacts {

    /**
     * 用户核心意图（一句话）
     * 示例："排查 K8s Pod 频繁 OOMKilled"
     */
    private String intent;

    /**
     * 已确认的关键事实（配置/版本/错误码/资源 ID 等硬信息）
     * 示例：["集群版本 1.28", "内存 limit 512Mi", "错误码 OOMKilled"]
     */
    private List<String> confirmedFacts = new ArrayList<>();

    /**
     * 执行过的工具及结果摘要
     */
    private List<ToolTrace> toolsUsed = new ArrayList<>();

    /**
     * 最终结论或建议
     * 示例："内存 limit 过低，建议调至 1Gi"
     */
    private String conclusion;

    /**
     * 待办事项与遗留风险
     * 示例：["未验证调整后是否仍存在内存泄漏"]
     */
    private List<String> pendingRisks = new ArrayList<>();

    /**
     * 引用的知识库文档（供溯源）
     */
    private List<String> citations = new ArrayList<>();

    // ==================== 内嵌类型 ====================

    /**
     * 工具调用痕迹
     */
    public static class ToolTrace {
        private String name;
        private String resultSummary;
        private boolean success;

        public ToolTrace() {
        }

        public ToolTrace(String name, String resultSummary, boolean success) {
            this.name = name;
            this.resultSummary = resultSummary;
            this.success = success;
        }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getResultSummary() { return resultSummary; }
        public void setResultSummary(String resultSummary) { this.resultSummary = resultSummary; }
        public boolean isSuccess() { return success; }
        public void setSuccess(boolean success) { this.success = success; }
    }

    // ==================== 便捷方法 ====================

    /**
     * 是否为空（无任何有效事实）
     * <p>{@code @JsonIgnore}：这是判定方法而非数据字段，不参与 JSONB 序列化</p>
     */
    @JsonIgnore
    public boolean isEmpty() {
        return (intent == null || intent.isBlank())
                && confirmedFacts.isEmpty()
                && toolsUsed.isEmpty()
                && (conclusion == null || conclusion.isBlank());
    }

    /**
     * 渲染为紧凑文本，供续聊时注入 Prompt
     * <p>相比全量历史，此形式 Token 占用通常低一个数量级。</p>
     */
    public String toPromptText() {
        StringBuilder sb = new StringBuilder();
        if (intent != null && !intent.isBlank()) {
            sb.append("【历史意图】").append(intent).append('\n');
        }
        if (!confirmedFacts.isEmpty()) {
            sb.append("【已确认事实】").append(String.join("；", confirmedFacts)).append('\n');
        }
        if (!toolsUsed.isEmpty()) {
            sb.append("【已执行】");
            for (ToolTrace t : toolsUsed) {
                sb.append(t.getName()).append(t.isSuccess() ? "(成功)" : "(失败)").append(' ');
            }
            sb.append('\n');
        }
        if (conclusion != null && !conclusion.isBlank()) {
            sb.append("【前次结论】").append(conclusion).append('\n');
        }
        if (!pendingRisks.isEmpty()) {
            sb.append("【遗留风险】").append(String.join("；", pendingRisks)).append('\n');
        }
        return sb.toString();
    }

    public void addConfirmedFact(String fact) {
        if (fact != null && !fact.isBlank() && !confirmedFacts.contains(fact)) {
            confirmedFacts.add(fact);
        }
    }

    public void addToolTrace(String name, String resultSummary, boolean success) {
        toolsUsed.add(new ToolTrace(name, resultSummary, success));
    }

    public void addCitation(String citation) {
        if (citation != null && !citation.isBlank() && !citations.contains(citation)) {
            citations.add(citation);
        }
    }

    // ==================== Getters & Setters ====================

    public String getIntent() { return intent; }
    public void setIntent(String intent) { this.intent = intent; }

    public List<String> getConfirmedFacts() { return confirmedFacts; }
    public void setConfirmedFacts(List<String> confirmedFacts) {
        this.confirmedFacts = confirmedFacts != null ? confirmedFacts : new ArrayList<>();
    }

    public List<ToolTrace> getToolsUsed() { return toolsUsed; }
    public void setToolsUsed(List<ToolTrace> toolsUsed) {
        this.toolsUsed = toolsUsed != null ? toolsUsed : new ArrayList<>();
    }

    public String getConclusion() { return conclusion; }
    public void setConclusion(String conclusion) { this.conclusion = conclusion; }

    public List<String> getPendingRisks() { return pendingRisks; }
    public void setPendingRisks(List<String> pendingRisks) {
        this.pendingRisks = pendingRisks != null ? pendingRisks : new ArrayList<>();
    }

    public List<String> getCitations() { return citations; }
    public void setCitations(List<String> citations) {
        this.citations = citations != null ? citations : new ArrayList<>();
    }
}