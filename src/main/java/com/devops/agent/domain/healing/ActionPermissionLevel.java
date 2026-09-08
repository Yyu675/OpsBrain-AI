package com.devops.agent.domain.healing;

/**
 * 原子操作权限等级（PRD §九「原子操作白名单枚举」三枚举的执行器侧落地）。
 * <p>
 * 与治理侧 {@code ToolRiskLevel} 的关系：ToolRiskLevel 管「这个动作在工具层
 * 有多危险」（白名单策略的键），本枚举管「这个执行器声称自己能碰多深的
 * 系统」——治理门对 DESTRUCTIVE 执行器强制四眼审批，无论白名单如何配置。
 * </p>
 */
public enum ActionPermissionLevel {

    /**
     * 只读诊断：查询、读取、聚合。无副作用，默认全开放。
     * PRD V1.2 的第一个真实执行器（K8s fabric8 只读）即落在本等级。
     */
    READ_ONLY_DIAGNOSTIC("只读诊断"),

    /**
     * 安全自愈：可逆、副作用有限的修复动作（优雅重启、磁盘清理）。
     * P3/P4 告警经白名单 + 免审批策略后可全自动闭环。
     */
    SAFE_AUTO_HEALING("安全自愈"),

    /**
     * 破坏高危：不可逆或高爆炸半径动作。必须人工签发（至少双人审批），
     * 治理门硬性收紧，不允许任何配置把它放宽为免审批。
     */
    DESTRUCTIVE_HIGH_RISK("破坏高危");

    private final String displayName;

    ActionPermissionLevel(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
