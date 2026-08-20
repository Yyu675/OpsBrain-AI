package com.devops.agent.domain.tools;

/**
 * Tool 风险等级枚举
 * <p>
 * 参考 Agent Methodology §9.1：工具分级治理，风险越高自治越少。
 * </p>
 *
 * <h3>与蓝图 ActionPermissionLevel 的对应（方向 D 决策）</h3>
 * 蓝图 §三 要求三级动作权限枚举 {@code ActionPermissionLevel}，但本枚举的四级
 * <b>已完整覆盖其语义</b>，故<b>不新建</b>——同一事实两处定义必然漂移（6.20 契约）。
 * 映射关系：
 * <table border="1">
 *   <tr><th>蓝图 ActionPermissionLevel</th><th>本枚举</th><th>自治策略</th></tr>
 *   <tr><td>READ_ONLY_DIAGNOSTIC</td><td>{@link #READ_ONLY}</td><td>默认全开放，无需审批</td></tr>
 *   <tr><td>SAFE_AUTO_HEALING</td><td>{@link #DRAFT} / {@link #CONTROLLED_WRITE}</td><td>P3/P4 可自动，写操作走 Single Writer + Saga</td></tr>
 *   <tr><td>DESTRUCTIVE_HIGH_RISK</td><td>{@link #HIGH_RISK_EXECUTION}</td><td>强制人工审批（sys_approval_request）后才执行</td></tr>
 * </table>
 * 审批落点见 {@code ApprovalService}：风险等级决定是否落审批单。
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