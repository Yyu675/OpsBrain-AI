package com.devops.agent.domain.governance;

import java.time.LocalDateTime;

/**
 * 动作白名单条目（L3）。对应表 {@code sys_action_allowlist}。
 *
 * <h3>语义：允许清单，不是禁止清单</h3>
 * <b>表里没有对应记录 = 不允许自动执行</b>，而不是「不受限制」。
 * 默认拒绝是安全配置的唯一正确默认——漏配一条动作的后果应当是
 * 「这个动作没自动跑」，而不是「它不受任何约束地跑了」。
 *
 * <p>对齐蓝图 §三「原子操作白名单枚举」，但把它从 Java 枚举
 * 改成可运行时调整的数据——枚举无法在故障当下临时收紧。</p>
 *
 * @author OpsBrain AI
 * @since 2026-08-25
 */
public class ActionAllowlistEntry {

    private Long id;

    /** 语言无关的动作标识，如 {@code k8s.pod.restart}。全局唯一 */
    private String actionKey;

    private String displayName;
    private String description;

    /** 归类：k8s / host / cloud / database / script / notify */
    private String category;

    /** 关联的 {@link com.devops.agent.domain.tools.ToolRiskLevel} 枚举名 */
    private String riskLevel;

    /** 目标资源匹配模式，如 {@code ns:prod/*}。空 = 不限制 */
    private String targetPattern;

    /** 允许生效的环境，逗号分隔。与风险策略取交集 */
    private String environments;

    /** 参数约束 JSON。引擎执行前据此校验模型给出的参数 */
    private String paramSchema;

    /**
     * 条目级审批覆盖。{@code null} = 跟随风险等级策略。
     * <p>只能收紧（设为 true），不能把高危动作放宽为免审批——
     * 校验在 {@code ActionAllowlistService}。</p>
     */
    private Boolean requiresApproval;

    /** 条目级爆炸半径覆盖。{@code null} = 跟随风险等级策略 */
    private Integer maxBlastRadiusCount;

    /**
     * 是否启用。
     * <p>停用而非删除：历史执行记录引用 {@link #actionKey}，
     * 物理删除会让审计记录变成指向不存在动作的孤儿。</p>
     */
    private boolean enabled;

    private int version;
    private String createdBy;
    private String updatedBy;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    // ==================== 派生字段（不落库） ====================

    /**
     * 生效后的实际审批要求，由 Service 结合风险策略计算。
     * <p>存在的理由：列表页要回答「这条动作现在到底要不要审批」，
     * 而答案是 {@code requiresApproval ?? policy.approvalMode.requiresHuman()}。
     * 让前端各自去算这个合并逻辑，必然出现前端算的与引擎实际执行的不一致——
     * 那是最危险的一类界面谎言。</p>
     */
    private Boolean effectiveRequiresApproval;

    /** 生效后的爆炸半径上限，同上由 Service 合并 */
    private Integer effectiveBlastRadiusCount;

    // ==================== 业务方法 ====================

    /**
     * 该条目是否允许在指定环境执行。
     *
     * <p>注意这只是条目自身的声明；最终判定还要与风险策略取交集
     * （见 {@code ActionAllowlistService.isExecutable}）——
     * 条目写了 prod 但策略只允许 dev 时，结论是不允许。</p>
     */
    public boolean allowsEnvironment(String environment) {
        if (environment == null || environment.isBlank() || environments == null) {
            return false;
        }
        String target = environment.trim().toLowerCase();
        for (String allowed : environments.split(",")) {
            if (allowed.trim().toLowerCase().equals(target)) {
                return true;
            }
        }
        return false;
    }

    // ==================== Getters & Setters ====================

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getActionKey() { return actionKey; }
    public void setActionKey(String actionKey) { this.actionKey = actionKey; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public String getRiskLevel() { return riskLevel; }
    public void setRiskLevel(String riskLevel) { this.riskLevel = riskLevel; }

    public String getTargetPattern() { return targetPattern; }
    public void setTargetPattern(String targetPattern) { this.targetPattern = targetPattern; }

    public String getEnvironments() { return environments; }
    public void setEnvironments(String environments) { this.environments = environments; }

    public String getParamSchema() { return paramSchema; }
    public void setParamSchema(String paramSchema) { this.paramSchema = paramSchema; }

    public Boolean getRequiresApproval() { return requiresApproval; }
    public void setRequiresApproval(Boolean v) { this.requiresApproval = v; }

    public Integer getMaxBlastRadiusCount() { return maxBlastRadiusCount; }
    public void setMaxBlastRadiusCount(Integer v) { this.maxBlastRadiusCount = v; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }

    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }

    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }

    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime t) { this.createTime = t; }

    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime t) { this.updateTime = t; }

    public Boolean getEffectiveRequiresApproval() { return effectiveRequiresApproval; }
    public void setEffectiveRequiresApproval(Boolean v) { this.effectiveRequiresApproval = v; }

    public Integer getEffectiveBlastRadiusCount() { return effectiveBlastRadiusCount; }
    public void setEffectiveBlastRadiusCount(Integer v) { this.effectiveBlastRadiusCount = v; }
}
