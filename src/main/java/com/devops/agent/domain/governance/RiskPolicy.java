package com.devops.agent.domain.governance;

import com.devops.agent.domain.tools.ToolRiskLevel;

import java.time.LocalDateTime;

/**
 * 风险等级策略（L3）。对应表 {@code sys_risk_policy}。
 *
 * <h3>这张表回答什么</h3>
 * 「{@link ToolRiskLevel} 的某一级，在自动化执行时受哪些约束」——
 * 要不要审批、能不能自动执行、一次最多影响几个实例、失败后怎么升级。
 *
 * <h3>为什么必须落库而不是继续留在代码里</h3>
 * 这些约束此前散落在 {@code @ToolMeta(requiresApproval = ...)} 注解与
 * 若干 if 判断中，调整必须改代码 + 重新构建 + 重启。
 * 但安全边界的调整往往发生在故障当下——「先把自动重启关掉」这个动作
 * 不能依赖一次发布流程。
 *
 * <h3>为什么用可变 Bean 而非 record</h3>
 * 项目里 {@code KnowledgeTag} 等只读视图用 record，
 * 但本类要承载「部分字段更新」（前端只提交改动的几项），
 * record 的全参构造会让每次更新都要显式重复未改动的值，
 * 极易在新增字段时漏掉一项、把它悄悄重置为默认值。
 *
 * @author OpsBrain AI
 * @since 2026-08-25
 */
public class RiskPolicy {

    /** 主键，对应 {@link ToolRiskLevel} 枚举名。不可修改 */
    private String riskLevel;

    private String displayName;
    private String description;

    // ==================== 审批门槛 ====================

    private ApprovalMode approvalMode;

    /** 审批时限（分钟）。超时未审批标 EXPIRED，避免动作无限挂起 */
    private int approvalTimeoutMinutes;

    // ==================== 执行限制 ====================

    /**
     * 是否允许引擎自动执行。
     * <p>与 {@link #approvalMode} 是两个正交维度：
     * 「审批通过了」不等于「可以由机器执行」——
     * 有些动作即便批准也要求人工在终端里敲，本字段表达这层区别。</p>
     */
    private boolean autoExecuteAllowed;

    /** 爆炸半径百分比上限（1-100） */
    private int maxBlastRadiusPercent;

    /** 爆炸半径绝对值上限。与百分比取较小值，见 {@link #resolveBlastRadius(int)} */
    private int maxBlastRadiusCount;

    /** 两批之间的观察窗口（秒）。蓝图 §三 的「等待 60 秒校验健康心跳」 */
    private int cooldownSeconds;

    private int maxRetries;

    // ==================== 升级路径 ====================

    private int escalateAfterMinutes;
    private EscalateTarget escalateTarget;

    // ==================== 生效范围 ====================

    /** 允许生效的环境，逗号分隔。空串 = 不允许任何环境 */
    private String allowedEnvironments;

    // ==================== 元数据 ====================

    /** 乐观锁版本。并发编辑时后提交者收到 40009，而非静默覆盖 */
    private int version;

    private String updatedBy;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    // ==================== 业务方法 ====================

    /**
     * 计算本次实际允许影响的实例数。
     *
     * <p><b>为什么取较小值</b>：只按百分比算，1000 实例的集群 5% = 50 个，
     * 一次挂 50 个不可接受；只按绝对值算，小集群配 1 个合理，
     * 大集群 1 个又过于保守失去意义。两者取小兼顾两端。</p>
     *
     * <p>下限强制为 1（当 totalInstances ≥ 1 时）：算出 0 会让自愈永远不执行，
     * 却不报任何错——这种「配置生效了但什么也没发生」最难排查。
     * 宁可执行 1 个，也不要静默空转。</p>
     *
     * @param totalInstances 目标资源的实例总数
     * @return 本批允许操作的实例数；totalInstances ≤ 0 时返回 0
     */
    public int resolveBlastRadius(int totalInstances) {
        if (totalInstances <= 0) {
            return 0;
        }
        // 向下取整后至少 1：20 实例 * 5% = 1；10 实例 * 5% = 0.5 → 1
        int byPercent = Math.max(1, totalInstances * maxBlastRadiusPercent / 100);
        int limit = Math.min(byPercent, Math.max(1, maxBlastRadiusCount));
        // 不能超过实例总数本身
        return Math.min(limit, totalInstances);
    }

    /**
     * 该策略是否允许在指定环境生效。
     *
     * <p>大小写不敏感、容忍多余空格——环境名由人手工填写，
     * 因为多打一个空格就让生产环境的安全限制静默失效是不可接受的。</p>
     */
    public boolean allowsEnvironment(String environment) {
        if (environment == null || environment.isBlank() || allowedEnvironments == null) {
            return false;
        }
        String target = environment.trim().toLowerCase();
        for (String allowed : allowedEnvironments.split(",")) {
            if (allowed.trim().toLowerCase().equals(target)) {
                return true;
            }
        }
        return false;
    }

    /** 是否完全无需人工介入即可执行（免审批 + 允许自动执行） */
    public boolean isFullyAutonomous() {
        return autoExecuteAllowed && approvalMode != null && !approvalMode.requiresHuman();
    }

    // ==================== Getters & Setters ====================

    public String getRiskLevel() { return riskLevel; }
    public void setRiskLevel(String riskLevel) { this.riskLevel = riskLevel; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public ApprovalMode getApprovalMode() { return approvalMode; }
    public void setApprovalMode(ApprovalMode approvalMode) { this.approvalMode = approvalMode; }

    public int getApprovalTimeoutMinutes() { return approvalTimeoutMinutes; }
    public void setApprovalTimeoutMinutes(int v) { this.approvalTimeoutMinutes = v; }

    public boolean isAutoExecuteAllowed() { return autoExecuteAllowed; }
    public void setAutoExecuteAllowed(boolean v) { this.autoExecuteAllowed = v; }

    public int getMaxBlastRadiusPercent() { return maxBlastRadiusPercent; }
    public void setMaxBlastRadiusPercent(int v) { this.maxBlastRadiusPercent = v; }

    public int getMaxBlastRadiusCount() { return maxBlastRadiusCount; }
    public void setMaxBlastRadiusCount(int v) { this.maxBlastRadiusCount = v; }

    public int getCooldownSeconds() { return cooldownSeconds; }
    public void setCooldownSeconds(int v) { this.cooldownSeconds = v; }

    public int getMaxRetries() { return maxRetries; }
    public void setMaxRetries(int v) { this.maxRetries = v; }

    public int getEscalateAfterMinutes() { return escalateAfterMinutes; }
    public void setEscalateAfterMinutes(int v) { this.escalateAfterMinutes = v; }

    public EscalateTarget getEscalateTarget() { return escalateTarget; }
    public void setEscalateTarget(EscalateTarget v) { this.escalateTarget = v; }

    public String getAllowedEnvironments() { return allowedEnvironments; }
    public void setAllowedEnvironments(String v) { this.allowedEnvironments = v; }

    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }

    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }

    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime t) { this.createTime = t; }

    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime t) { this.updateTime = t; }
}
