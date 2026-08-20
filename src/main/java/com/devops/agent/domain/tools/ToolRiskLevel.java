package com.devops.agent.domain.tools;

/**
 * Tool 风险等级枚举
 * <p>
 * 参考 Agent Methodology §9.1：工具分级治理，风险越高自治越少。
 * </p>
 *
 * @author OpsBrain AI
 * @since 2026-08-08
 */
public enum ToolRiskLevel {

    /**
     * 纯只读查询：搜索、读取、统计
     * 特点：无副作用、可重试、可降级、可并发、无需审批
     * 典型：searchDevOpsKnowledge、queryDashboard、getConfig
     */
    READ_ONLY("只读查询", "无副作用，可安全重试降级"),

    /**
     * 草稿/建议生成：生成文案、生成 SQL、生成脚本、生成方案
     * 特点：不直接改业务状态、输出供人工审核、可重试
     * 典型：generateScript、draftResponse、suggestOptimization
     */
    DRAFT("草稿生成", "不直接改状态，输出供人工审核"),

    /**
     * 受控写操作：创建工单、创建记录、发送通知、更新非核心配置
     * 特点：有副作用但可控、需幂等、可补偿、低风险默认不需审批
     * 典型：createDevOpsTicket、sendNotification、updateTag
     */
    CONTROLLED_WRITE("受控写操作", "有副作用但可控，需幂等补偿"),

    /**
     * 高风险执行：生产环境重启、删除资源、修改核心配置、执行脚本、数据库 DDL
     * 特点：不可逆或难逆、影响面大、必须审批、必须人工确认、审计全留痕
     * 典型：restartPod、deleteResource、executeScript、alterTable
     */
    HIGH_RISK_EXECUTION("高风险执行", "不可逆或难逆，必须审批人工确认");

    private final String displayName;
    private final String description;

    ToolRiskLevel(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() { return displayName; }
    public String getDescription() { return description; }
}