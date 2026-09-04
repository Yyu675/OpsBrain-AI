package com.devops.agent.domain.governance;

import java.time.LocalDateTime;
import java.util.Locale;

/**
 * 自动化策略（L3）。对应表 {@code sys_automation_policy}。
 *
 * <h3>这张表回答什么</h3>
 * 「**什么情况下**该调哪个动作」。它是 v26 两张表之间缺的那一环：
 * <ul>
 *   <li>{@code sys_action_allowlist} —— 允许调哪些动作（能不能做）</li>
 *   <li>{@code sys_risk_policy} —— 每级风险怎么管（怎么做）</li>
 *   <li><b>本表</b> —— 什么告警触发什么动作（什么时候做）</li>
 * </ul>
 *
 * <h3>为什么只存 actionKey 而不内联动作定义</h3>
 * 策略只说「匹配上了就执行 {@code k8s.pod.restart}」。
 * 至于该动作能否执行、是否需要审批、爆炸半径多大，全部由白名单与风险策略回答。
 * 若在这里再存一份动作参数约束，就会出现「策略说能跑、白名单说不能跑」
 * 的自相矛盾状态，而运维无法判断该信哪个。
 *
 * @author OpsBrain AI
 * @since 2026-08-25
 */
public class AutomationPolicy {

    private Long id;

    private String name;
    private String description;

    // ==================== 匹配条件 ====================
    // 留空 = 不限制（通配）。与白名单的「无记录=拒绝」方向相反，这是刻意的：
    // 策略是「主动声明我要管什么」，条件越少覆盖越广；
    // 白名单是「授权清单」，未授权必须拒绝。

    /** 告警级别，逗号分隔，如 {@code P2,P3}。对应 {@code sys_alert.level} */
    private String matchAlertLevels;

    /** 业务模块，对应 {@code sys_alert.module} */
    private String matchModule;

    /** 服务名匹配模式，支持 {@code *} 通配 */
    private String matchServicePattern;

    /** 告警规则名匹配模式 */
    private String matchAlertNamePattern;

    // ==================== 命中后做什么 ====================

    /** 引用 {@code sys_action_allowlist.action_key} */
    private String actionKey;

    /** 传给动作的参数 JSON */
    private String actionParams;

    private String environment;

    // ==================== 执行控制 ====================

    /** 求值顺序，越小越先 */
    private int priority;

    private boolean stopOnMatch;

    /** 冷却期（分钟），防自动化风暴 */
    private int cooldownMinutes;

    private int maxExecutionsPerDay;

    // ==================== 安全开关 ====================

    /**
     * 演练模式：照常匹配与记录，但不真正执行。
     * <p>新建策略默认开启。自动化最危险的时刻是「刚配好、
     * 还没人知道它会匹配到什么」——直接上线的策略若匹配范围写宽了，
     * 第一次触发就是事故。</p>
     */
    private boolean dryRun;

    private boolean enabled;

    private int version;
    private String createdBy;
    private String updatedBy;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    // ==================== 派生字段（不落库） ====================

    /**
     * 所引用动作的当前状态，由 Service 装填。
     *
     * <p>存在的理由：策略引用的动作可能已被停用或删改。
     * 列表页必须显示「这条策略引用的动作现在还有效吗」——
     * 否则用户会看到一条「已启用」的策略，实际永远不会执行，
     * 而界面上没有任何迹象。</p>
     */
    private String actionDisplayName;
    private String actionRiskLevel;
    private Boolean actionEnabled;

    /** 该策略当前是否真的会生效（自身启用 + 动作可用），由 Service 计算 */
    private Boolean effective;

    /** 不生效的原因，供页面直接展示 */
    private String ineffectiveReason;

    // ==================== 业务方法 ====================

    /**
     * 判断告警级别是否命中。
     *
     * <p>留空视为通配。大小写与空格容错——级别是人手填的，
     * 因为多打一个空格就让策略静默不匹配，排查起来毫无线索。</p>
     */
    public boolean matchesLevel(String level) {
        if (matchAlertLevels == null || matchAlertLevels.isBlank()) {
            return true;
        }
        if (level == null) {
            return false;
        }
        String target = level.trim().toUpperCase(Locale.ROOT);
        for (String candidate : matchAlertLevels.split(",")) {
            if (candidate.trim().toUpperCase(Locale.ROOT).equals(target)) {
                return true;
            }
        }
        return false;
    }

    /** 模块是否命中。留空通配 */
    public boolean matchesModule(String module) {
        if (matchModule == null || matchModule.isBlank()) {
            return true;
        }
        return module != null
                && matchModule.trim().equalsIgnoreCase(module.trim());
    }

    /** 服务名是否命中（支持 {@code *} 通配） */
    public boolean matchesService(String service) {
        return wildcardMatch(matchServicePattern, service);
    }

    /** 告警规则名是否命中（支持 {@code *} 通配） */
    public boolean matchesAlertName(String alertName) {
        return wildcardMatch(matchAlertNamePattern, alertName);
    }

    /**
     * 整体匹配判定。
     *
     * <p>四个条件是<b>与</b>关系：全部命中才算匹配。
     * 用「或」会让策略覆盖面难以预测——配了两个条件本意是收窄，
     * 结果反而变宽，这是配置类系统里最容易出的反直觉错误。</p>
     */
    public boolean matches(String level, String module, String service, String alertName) {
        return matchesLevel(level)
                && matchesModule(module)
                && matchesService(service)
                && matchesAlertName(alertName);
    }

    /**
     * 通配匹配。
     *
     * <p>只支持 {@code *}，不支持完整正则。理由：正则的表达力在这里是负担——
     * 运维写错一个正则可能让策略匹配到意料之外的服务，
     * 而 {@code order-*} 这种形式所见即所得，出错空间小得多。</p>
     */
    static boolean wildcardMatch(String pattern, String value) {
        if (pattern == null || pattern.isBlank() || "*".equals(pattern.trim())) {
            return true;
        }
        if (value == null) {
            return false;
        }
        String p = pattern.trim().toLowerCase(Locale.ROOT);
        String v = value.trim().toLowerCase(Locale.ROOT);

        // 把通配符之外的部分做正则转义，避免服务名里的 . - 被当成正则元字符
        StringBuilder regex = new StringBuilder();
        for (String segment : p.split("\\*", -1)) {
            if (regex.length() > 0) {
                regex.append(".*");
            }
            regex.append(java.util.regex.Pattern.quote(segment));
        }
        return v.matches(regex.toString());
    }

    // ==================== Getters & Setters ====================

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getMatchAlertLevels() { return matchAlertLevels; }
    public void setMatchAlertLevels(String v) { this.matchAlertLevels = v; }

    public String getMatchModule() { return matchModule; }
    public void setMatchModule(String v) { this.matchModule = v; }

    public String getMatchServicePattern() { return matchServicePattern; }
    public void setMatchServicePattern(String v) { this.matchServicePattern = v; }

    public String getMatchAlertNamePattern() { return matchAlertNamePattern; }
    public void setMatchAlertNamePattern(String v) { this.matchAlertNamePattern = v; }

    public String getActionKey() { return actionKey; }
    public void setActionKey(String actionKey) { this.actionKey = actionKey; }

    public String getActionParams() { return actionParams; }
    public void setActionParams(String actionParams) { this.actionParams = actionParams; }

    public String getEnvironment() { return environment; }
    public void setEnvironment(String environment) { this.environment = environment; }

    public int getPriority() { return priority; }
    public void setPriority(int priority) { this.priority = priority; }

    public boolean isStopOnMatch() { return stopOnMatch; }
    public void setStopOnMatch(boolean v) { this.stopOnMatch = v; }

    public int getCooldownMinutes() { return cooldownMinutes; }
    public void setCooldownMinutes(int v) { this.cooldownMinutes = v; }

    public int getMaxExecutionsPerDay() { return maxExecutionsPerDay; }
    public void setMaxExecutionsPerDay(int v) { this.maxExecutionsPerDay = v; }

    public boolean isDryRun() { return dryRun; }
    public void setDryRun(boolean dryRun) { this.dryRun = dryRun; }

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

    public String getActionDisplayName() { return actionDisplayName; }
    public void setActionDisplayName(String v) { this.actionDisplayName = v; }

    public String getActionRiskLevel() { return actionRiskLevel; }
    public void setActionRiskLevel(String v) { this.actionRiskLevel = v; }

    public Boolean getActionEnabled() { return actionEnabled; }
    public void setActionEnabled(Boolean v) { this.actionEnabled = v; }

    public Boolean getEffective() { return effective; }
    public void setEffective(Boolean effective) { this.effective = effective; }

    public String getIneffectiveReason() { return ineffectiveReason; }
    public void setIneffectiveReason(String v) { this.ineffectiveReason = v; }
}
