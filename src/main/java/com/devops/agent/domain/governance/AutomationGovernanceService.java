package com.devops.agent.domain.governance;

import com.devops.agent.domain.tools.ToolRiskLevel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 自动化治理服务（L3）：风险等级策略 + 动作白名单。
 *
 * <h3>为什么两张表合到一个 Service</h3>
 * 它们不是两个独立的 CRUD，而是<b>一组必须联合校验的约束</b>：
 * <ul>
 *   <li>白名单条目的 {@code requiresApproval} 只能收紧、不能放宽到低于风险策略；</li>
 *   <li>白名单条目的环境必须是风险策略允许环境的<b>子集</b>；</li>
 *   <li>条目的爆炸半径不能超过策略上限。</li>
 * </ul>
 * 拆成两个 Service 会让这些跨表规则无处安放——要么下沉到 Controller
 * （分层错位），要么互相注入（循环依赖风险）。
 *
 * <h3>核心不变式：安全配置只能自己收紧，不能自己放宽</h3>
 * 所有校验都朝同一个方向：条目可以比策略更严，绝不能更松。
 * 这样「调整策略」就有了可预期的语义——把某一级的策略收紧，
 * 该级下所有动作立刻同步收紧；而不需要逐条去检查有没有漏网的例外。
 *
 * @author OpsBrain AI
 * @since 2026-08-25
 */
@Slf4j
@Service
public class AutomationGovernanceService {

    /** 合法环境词表。手工填写的自由文本会让「prod」「production」「PROD」并存 */
    private static final Set<String> KNOWN_ENVIRONMENTS = Set.of("prod", "staging", "dev");

    /** 合法类别词表，与迁移脚本 v26 的种子数据一致 */
    private static final Set<String> KNOWN_CATEGORIES =
            Set.of("k8s", "host", "cloud", "database", "script", "notify");

    private static final int MAX_ACTION_KEY_LENGTH = 64;
    private static final int MAX_DISPLAY_NAME_LENGTH = 64;
    private static final int MAX_DESCRIPTION_LENGTH = 255;
    private static final int MAX_PATTERN_LENGTH = 255;

    /** 合法告警级别词表，对齐 {@code sys_alert.level} */
    private static final Set<String> KNOWN_ALERT_LEVELS = Set.of("P0", "P1", "P2", "P3", "P4");

    private final RiskPolicyRepository policyRepository;
    private final ActionAllowlistRepository allowlistRepository;
    private final AutomationPolicyRepository automationPolicyRepository;

    public AutomationGovernanceService(RiskPolicyRepository policyRepository,
                                       ActionAllowlistRepository allowlistRepository,
                                       AutomationPolicyRepository automationPolicyRepository) {
        this.policyRepository = policyRepository;
        this.allowlistRepository = allowlistRepository;
        this.automationPolicyRepository = automationPolicyRepository;
    }

    // ==================================================================
    // 风险等级策略
    // ==================================================================

    @Transactional(readOnly = true)
    public List<RiskPolicy> listPolicies() {
        return policyRepository.findAll();
    }

    @Transactional(readOnly = true)
    public RiskPolicy getPolicy(String riskLevel) {
        return policyRepository.findByRiskLevel(normalizeRiskLevel(riskLevel))
                .orElseThrow(() -> new IllegalArgumentException("风险等级不存在: " + riskLevel));
    }

    /**
     * 更新风险策略。
     *
     * <p><b>不允许新增或删除等级</b>——见 {@link RiskPolicyRepository} 类注释。
     * 传入未知等级直接拒绝，而不是插入一行永远不会被命中的死配置。</p>
     *
     * @param expectedVersion 客户端读到的版本号，用于 CAS
     * @throws IllegalArgumentException 等级不存在或字段非法
     */
    @Transactional(rollbackFor = Exception.class)
    public RiskPolicy updatePolicy(String riskLevel, RiskPolicy submitted,
                                   int expectedVersion, String operator) {
        String level = normalizeRiskLevel(riskLevel);
        RiskPolicy existing = policyRepository.findByRiskLevel(level)
                .orElseThrow(() -> new IllegalArgumentException(
                        "风险等级不存在，且不支持新增：" + riskLevel));

        validatePolicy(level, submitted);

        submitted.setRiskLevel(level);
        policyRepository.update(submitted, expectedVersion, operator);

        // 重新读取而非返回入参：入参没有更新后的 version 与 updateTime，
        // 前端拿它回填会立刻在下一次提交时撞版本冲突
        RiskPolicy updated = policyRepository.findByRiskLevel(level).orElse(existing);

        // 白名单条目可能因策略收紧而与之冲突，回报数量供前端提示
        log.warn("🔐 [Governance] 风险策略更新完成 | level={} | operator={} | 受影响的启用动作数={}",
                level, operator, countEnabledByRiskLevel(level));
        return updated;
    }

    /**
     * 校验策略字段。
     *
     * <p>数值上下限不是防御性编程的随手一写，每一条都对应一个真实故障模式：
     * 爆炸半径 0 让自愈静默空转、超时 0 让审批单立刻过期、
     * 重试次数过大让一个坏动作被反复执行放大影响。</p>
     */
    private void validatePolicy(String level, RiskPolicy p) {
        if (p.getApprovalMode() == null) {
            throw new IllegalArgumentException("审批模式不能为空");
        }
        if (p.getEscalateTarget() == null) {
            throw new IllegalArgumentException("升级目标不能为空");
        }
        requireRange(p.getApprovalTimeoutMinutes(), 1, 1440, "审批时限（分钟）");
        requireRange(p.getMaxBlastRadiusPercent(), 1, 100, "爆炸半径百分比");
        requireRange(p.getMaxBlastRadiusCount(), 1, 9999, "爆炸半径实例数");
        requireRange(p.getCooldownSeconds(), 0, 3600, "观察窗口（秒）");
        requireRange(p.getMaxRetries(), 0, 5, "最大重试次数");
        requireRange(p.getEscalateAfterMinutes(), 0, 1440, "升级等待（分钟）");

        p.setAllowedEnvironments(normalizeEnvironments(p.getAllowedEnvironments(), true));

        // ── 高危等级的硬底线 ──────────────────────────────────────
        // 这两条不做成「建议」而做成「拒绝」：一旦允许用户把高风险动作
        // 配成免审批 + 全自动，蓝图 §三 整套安全防线就形同虚设，
        // 而这种配置往往是在故障当下图省事改的，事后没人记得改回来
        if (ToolRiskLevel.HIGH_RISK_EXECUTION.name().equals(level)) {
            if (!p.getApprovalMode().requiresHuman()) {
                throw new IllegalArgumentException(
                        "高风险执行不允许配置为免审批——该等级涵盖不可逆操作，"
                                + "如需放开请先将具体动作降级到「受控写操作」");
            }
            if (p.isAutoExecuteAllowed() && p.getMaxBlastRadiusPercent() > 25) {
                throw new IllegalArgumentException(
                        "高风险执行开启自动执行时，爆炸半径不得超过 25%");
            }
        }
    }

    // ==================================================================
    // 动作白名单
    // ==================================================================

    /**
     * 分页查询，并把「生效后的实际约束」计算好一并下发。
     *
     * <p>合并逻辑放在服务端而不是前端：前端各自实现
     * {@code requiresApproval ?? policy.requiresHuman()} 必然与引擎的实际判断漂移，
     * 而界面显示「不需审批」但引擎实际拦下来，用户会认为系统坏了。</p>
     */
    @Transactional(readOnly = true)
    public Map<String, Object> listActions(String keyword, String category, String riskLevel,
                                           Boolean enabled, int page, int size) {
        Map<String, Object> result =
                allowlistRepository.query(keyword, category, riskLevel, enabled, page, size);

        @SuppressWarnings("unchecked")
        List<ActionAllowlistEntry> items = (List<ActionAllowlistEntry>) result.get("items");

        // 一次性取出全部策略做 Map，避免逐条查（列表 20 行就是 20 次查询）
        Map<String, RiskPolicy> policies = policyMap();
        for (ActionAllowlistEntry e : items) {
            applyEffective(e, policies.get(e.getRiskLevel()));
        }
        return result;
    }

    @Transactional(readOnly = true)
    public ActionAllowlistEntry getAction(long id) {
        ActionAllowlistEntry e = allowlistRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("动作不存在: " + id));
        applyEffective(e, policyRepository.findByRiskLevel(e.getRiskLevel()).orElse(null));
        return e;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> actionFilterOptions() {
        Map<String, Object> options = new LinkedHashMap<>(allowlistRepository.filterOptions());
        // 风险等级来自 Java 枚举而非库里的 DISTINCT：即便当前没有任何
        // HIGH_RISK_EXECUTION 的动作，新建表单里也必须能选到它
        List<Map<String, String>> levels = new ArrayList<>();
        for (ToolRiskLevel level : ToolRiskLevel.values()) {
            levels.add(Map.of(
                    "value", level.name(),
                    "label", level.getDisplayName(),
                    "description", level.getDescription()));
        }
        options.put("riskLevels", levels);
        options.put("environments", List.copyOf(KNOWN_ENVIRONMENTS));
        options.put("knownCategories", List.copyOf(KNOWN_CATEGORIES));
        return options;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> actionStats() {
        return allowlistRepository.stats();
    }

    @Transactional(rollbackFor = Exception.class)
    public ActionAllowlistEntry createAction(ActionAllowlistEntry submitted, String operator) {
        normalizeAction(submitted);
        RiskPolicy policy = requirePolicy(submitted.getRiskLevel());
        validateAction(submitted, policy);

        // 唯一约束在 DB 上，但先查一次能给出可读的提示。
        // DB 约束仍是最终保障——并发下两个请求可能都通过这里的检查
        allowlistRepository.findByActionKey(submitted.getActionKey()).ifPresent(existing -> {
            throw new IllegalStateException(
                    "动作标识已存在：" + submitted.getActionKey()
                            + "（当前为「" + existing.getDisplayName() + "」，"
                            + (existing.isEnabled() ? "已启用" : "已停用") + "）");
        });

        Long id = allowlistRepository.insert(submitted, operator);
        return getAction(id);
    }

    @Transactional(rollbackFor = Exception.class)
    public ActionAllowlistEntry updateAction(long id, ActionAllowlistEntry submitted,
                                             int expectedVersion, String operator) {
        ActionAllowlistEntry existing = allowlistRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("动作不存在: " + id));

        normalizeAction(submitted);
        RiskPolicy policy = requirePolicy(submitted.getRiskLevel());
        validateAction(submitted, policy);

        submitted.setId(id);
        // action_key 不可改（审计关联键），强制沿用旧值而不是信任前端传的
        submitted.setActionKey(existing.getActionKey());

        allowlistRepository.update(submitted, expectedVersion, operator);
        return getAction(id);
    }

    /**
     * 启用/停用。
     *
     * <p>启用时重新跑一遍完整校验：条目可能是在旧策略下配好的，
     * 之后策略被收紧了。若不复查，「停用 → 策略收紧 → 重新启用」
     * 这条路径就能绕过所有约束——这正是安全配置里最常见的绕过方式。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public ActionAllowlistEntry toggleAction(long id, boolean enabled,
                                             int expectedVersion, String operator) {
        ActionAllowlistEntry existing = allowlistRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("动作不存在: " + id));

        if (enabled) {
            RiskPolicy policy = requirePolicy(existing.getRiskLevel());
            validateAction(existing, policy);
        }

        allowlistRepository.toggleEnabled(id, enabled, expectedVersion, operator);
        return getAction(id);
    }

    // ==================================================================
    // 引擎侧查询（供将来的执行引擎调用）
    // ==================================================================

    /**
     * 判定某个动作在指定环境下是否可自动执行。
     *
     * <p>这是白名单存在的最终目的——执行引擎在动手前问这一句。
     * 现在还没有引擎，但接口先定下来，可以让配置页的「模拟校验」直接复用，
     * 用户改完配置能立刻看到「在 prod 上执行 k8s.pod.restart：不允许，原因是…」。</p>
     *
     * @return 判定结果，含 {@code allowed} 与人类可读的 {@code reason}
     */
    @Transactional(readOnly = true)
    public Map<String, Object> evaluate(String actionKey, String environment) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("actionKey", actionKey);
        result.put("environment", environment);

        Optional<ActionAllowlistEntry> found = allowlistRepository.findByActionKey(actionKey);
        if (found.isEmpty()) {
            // 未登记 = 拒绝。这是白名单语义的核心，不是「查不到就放行」
            return deny(result, "该动作未登记在白名单中。白名单为允许清单，未登记的动作一律不允许自动执行");
        }

        ActionAllowlistEntry entry = found.get();
        if (!entry.isEnabled()) {
            return deny(result, "该动作已登记但处于停用状态");
        }
        if (!entry.allowsEnvironment(environment)) {
            return deny(result, "该动作未在 " + environment + " 环境开放（当前开放：" + entry.getEnvironments() + "）");
        }

        RiskPolicy policy = policyRepository.findByRiskLevel(entry.getRiskLevel()).orElse(null);
        if (policy == null) {
            // 策略缺失时拒绝而非放行：读不到约束就等于没有约束，
            // 此时执行是在完全无防护的状态下操作生产系统
            return deny(result, "风险等级 " + entry.getRiskLevel() + " 的策略缺失，出于安全默认拒绝");
        }
        if (!policy.allowsEnvironment(environment)) {
            return deny(result, "风险等级「" + policy.getDisplayName() + "」未在 "
                    + environment + " 环境开放（当前开放：" + policy.getAllowedEnvironments() + "）");
        }
        if (!policy.isAutoExecuteAllowed()) {
            return deny(result, "风险等级「" + policy.getDisplayName()
                    + "」未开启自动执行，需人工手动触发");
        }

        applyEffective(entry, policy);
        result.put("allowed", true);
        result.put("reason", "允许自动执行");
        result.put("requiresApproval", entry.getEffectiveRequiresApproval());
        result.put("approvalMode", policy.getApprovalMode().name());
        result.put("blastRadiusCount", entry.getEffectiveBlastRadiusCount());
        result.put("cooldownSeconds", policy.getCooldownSeconds());
        return result;
    }

    private Map<String, Object> deny(Map<String, Object> result, String reason) {
        result.put("allowed", false);
        result.put("reason", reason);
        return result;
    }

    // ==================================================================
    // 内部：校验与归一化
    // ==================================================================

    /**
     * 计算生效后的实际约束（条目覆盖 + 策略兜底）。
     *
     * <p>策略为 null 时倒向<b>最严格</b>：需审批、爆炸半径 1。
     * 读不到策略说明配置有问题，此时给出宽松结论会让页面显示
     * 「无需审批」这种与实际不符且危险的信息。</p>
     */
    private void applyEffective(ActionAllowlistEntry e, RiskPolicy policy) {
        if (policy == null) {
            e.setEffectiveRequiresApproval(Boolean.TRUE);
            e.setEffectiveBlastRadiusCount(1);
            return;
        }
        e.setEffectiveRequiresApproval(
                e.getRequiresApproval() != null
                        ? e.getRequiresApproval()
                        : policy.getApprovalMode().requiresHuman());
        e.setEffectiveBlastRadiusCount(
                e.getMaxBlastRadiusCount() != null
                        ? Math.min(e.getMaxBlastRadiusCount(), policy.getMaxBlastRadiusCount())
                        : policy.getMaxBlastRadiusCount());
    }

    /**
     * 跨表校验：条目只能比策略更严，不能更松。
     *
     * <p>三条规则各自对应一种绕过方式，都必须堵：</p>
     * <ol>
     *   <li>把高危动作的 requiresApproval 显式设成 false —— 绕过审批；</li>
     *   <li>把条目环境写成 prod 而策略只开了 dev —— 绕过环境限制；</li>
     *   <li>把条目爆炸半径写成 100 而策略上限是 1 —— 绕过影响面控制。</li>
     * </ol>
     */
    private void validateAction(ActionAllowlistEntry e, RiskPolicy policy) {
        requireText(e.getActionKey(), MAX_ACTION_KEY_LENGTH, "动作标识");
        requireText(e.getDisplayName(), MAX_DISPLAY_NAME_LENGTH, "显示名称");
        if (e.getDescription() != null && e.getDescription().length() > MAX_DESCRIPTION_LENGTH) {
            throw new IllegalArgumentException("描述不能超过 " + MAX_DESCRIPTION_LENGTH + " 字");
        }
        if (e.getTargetPattern() != null && e.getTargetPattern().length() > MAX_PATTERN_LENGTH) {
            throw new IllegalArgumentException("目标匹配模式不能超过 " + MAX_PATTERN_LENGTH + " 字");
        }
        if (!KNOWN_CATEGORIES.contains(e.getCategory())) {
            throw new IllegalArgumentException(
                    "类别不合法，可选：" + String.join(" / ", KNOWN_CATEGORIES));
        }

        // 规则 1：审批只能收紧
        if (Boolean.FALSE.equals(e.getRequiresApproval())
                && policy.getApprovalMode().requiresHuman()) {
            throw new IllegalArgumentException(
                    "风险等级「" + policy.getDisplayName() + "」要求"
                            + policy.getApprovalMode().getDisplayName()
                            + "，单个动作不能配置为免审批。如需放开请调整该等级的策略");
        }

        // 规则 2：环境必须是策略允许环境的子集
        for (String env : e.getEnvironments().split(",")) {
            String trimmed = env.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (!policy.allowsEnvironment(trimmed)) {
                throw new IllegalArgumentException(
                        "环境「" + trimmed + "」超出风险等级「" + policy.getDisplayName()
                                + "」的允许范围（" + policy.getAllowedEnvironments() + "）");
            }
        }

        // 规则 3：爆炸半径不得超过策略上限
        if (e.getMaxBlastRadiusCount() != null) {
            requireRange(e.getMaxBlastRadiusCount(), 1, 9999, "爆炸半径实例数");
            if (e.getMaxBlastRadiusCount() > policy.getMaxBlastRadiusCount()) {
                throw new IllegalArgumentException(
                        "爆炸半径 " + e.getMaxBlastRadiusCount() + " 超过风险等级上限 "
                                + policy.getMaxBlastRadiusCount());
            }
        }

        // 空目标模式 + 写操作 = 无边界地对所有资源生效，这是最容易酿成事故的配置
        boolean isWrite = !ToolRiskLevel.READ_ONLY.name().equals(e.getRiskLevel())
                && !ToolRiskLevel.DRAFT.name().equals(e.getRiskLevel());
        if (isWrite && (e.getTargetPattern() == null || e.getTargetPattern().isBlank())) {
            throw new IllegalArgumentException(
                    "写操作必须指定目标匹配模式（如 ns:staging/*），"
                            + "留空意味着对所有资源生效");
        }
    }

    /** 归一化：去空白、统一大小写，让「 Prod 」与「prod」等价 */
    private void normalizeAction(ActionAllowlistEntry e) {
        if (e.getActionKey() != null) {
            e.setActionKey(e.getActionKey().trim().toLowerCase(Locale.ROOT));
        }
        if (e.getDisplayName() != null) {
            e.setDisplayName(e.getDisplayName().trim());
        }
        if (e.getDescription() != null) {
            e.setDescription(e.getDescription().trim());
        }
        e.setCategory(e.getCategory() == null ? "" : e.getCategory().trim().toLowerCase(Locale.ROOT));
        e.setRiskLevel(normalizeRiskLevel(e.getRiskLevel()));
        if (e.getTargetPattern() != null) {
            e.setTargetPattern(e.getTargetPattern().trim());
        }
        e.setEnvironments(normalizeEnvironments(e.getEnvironments(), false));
    }

    /**
     * 环境列表归一化。
     *
     * @param allowEmpty 策略允许空串（表示不允许任何环境）；条目不允许
     */
    private String normalizeEnvironments(String raw, boolean allowEmpty) {
        if (raw == null || raw.isBlank()) {
            if (allowEmpty) {
                return "";
            }
            throw new IllegalArgumentException("必须至少选择一个生效环境");
        }
        // LinkedHashSet 去重同时保序：用户填 "dev,dev,prod" 不该报错，
        // 但也不该存成三项——存重复值会让后续的 split 判断做无谓的重复比较
        Set<String> normalized = new java.util.LinkedHashSet<>();
        for (String part : raw.split(",")) {
            String env = part.trim().toLowerCase(Locale.ROOT);
            if (env.isEmpty()) {
                continue;
            }
            if (!KNOWN_ENVIRONMENTS.contains(env)) {
                throw new IllegalArgumentException(
                        "未知环境「" + env + "」，可选：" + String.join(" / ", KNOWN_ENVIRONMENTS));
            }
            normalized.add(env);
        }
        if (normalized.isEmpty() && !allowEmpty) {
            throw new IllegalArgumentException("必须至少选择一个生效环境");
        }
        return String.join(",", normalized);
    }

    /** 风险等级归一化 + 存在性校验。对着 Java 枚举校验，比 DB 外键更严 */
    private String normalizeRiskLevel(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("风险等级不能为空");
        }
        String value = raw.trim().toUpperCase(Locale.ROOT);
        for (ToolRiskLevel level : EnumSet.allOf(ToolRiskLevel.class)) {
            if (level.name().equals(value)) {
                return value;
            }
        }
        throw new IllegalArgumentException("未知风险等级: " + raw);
    }

    private RiskPolicy requirePolicy(String riskLevel) {
        return policyRepository.findByRiskLevel(riskLevel)
                .orElseThrow(() -> new IllegalStateException(
                        "风险等级 " + riskLevel + " 的策略未初始化，请先执行 migration_v26"));
    }

    private Map<String, RiskPolicy> policyMap() {
        Map<String, RiskPolicy> map = new LinkedHashMap<>();
        for (RiskPolicy p : policyRepository.findAll()) {
            map.put(p.getRiskLevel(), p);
        }
        return map;
    }

    private long countEnabledByRiskLevel(String riskLevel) {
        Object total = allowlistRepository
                .query(null, null, riskLevel, true, 1, 1)
                .get("total");
        return total instanceof Number n ? n.longValue() : 0L;
    }

    private static void requireText(String value, int maxLength, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + "不能为空");
        }
        if (value.length() > maxLength) {
            throw new IllegalArgumentException(fieldName + "不能超过 " + maxLength + " 字");
        }
    }

    private static void requireRange(int value, int min, int max, String fieldName) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(
                    fieldName + "必须在 " + min + " 到 " + max + " 之间（当前 " + value + "）");
        }
    }

    // ==================================================================
    // 自动化策略（v27）
    // ==================================================================

    /**
     * 分页查询策略，并装填「所引用动作的当前状态」。
     *
     * <p>装填的理由：策略引用的动作可能已被停用。列表页必须显示
     * 「这条策略引用的动作现在还有效吗」——否则用户会看到一条
     * 「已启用」的策略，实际永远不会执行，而界面上没有任何迹象。</p>
     */
    @Transactional(readOnly = true)
    public Map<String, Object> listAutomationPolicies(String keyword, String actionKey,
                                                      String environment, Boolean enabled,
                                                      int page, int size) {
        Map<String, Object> result = automationPolicyRepository.query(
                keyword, actionKey, environment, enabled, page, size);

        @SuppressWarnings("unchecked")
        List<AutomationPolicy> items = (List<AutomationPolicy>) result.get("items");
        for (AutomationPolicy p : items) {
            applyActionState(p);
        }
        return result;
    }

    @Transactional(readOnly = true)
    public AutomationPolicy getAutomationPolicy(long id) {
        AutomationPolicy p = automationPolicyRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("策略不存在: " + id));
        applyActionState(p);
        return p;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> automationPolicyStats() {
        return automationPolicyRepository.stats();
    }

    @Transactional(rollbackFor = Exception.class)
    public AutomationPolicy createAutomationPolicy(AutomationPolicy submitted, String operator) {
        normalizePolicy(submitted);
        validatePolicyRule(submitted);

        automationPolicyRepository.findByName(submitted.getName()).ifPresent(existing -> {
            throw new IllegalStateException(
                    "策略名已存在：" + submitted.getName()
                            + "（同名策略会让日志里「策略 X 已触发」无法定位是哪一条）");
        });

        Long id = automationPolicyRepository.insert(submitted, operator);
        return getAutomationPolicy(id);
    }

    @Transactional(rollbackFor = Exception.class)
    public AutomationPolicy updateAutomationPolicy(long id, AutomationPolicy submitted,
                                                   int expectedVersion, String operator) {
        automationPolicyRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("策略不存在: " + id));

        normalizePolicy(submitted);
        validatePolicyRule(submitted);

        // 改名时查重，但要排除自己
        automationPolicyRepository.findByName(submitted.getName()).ifPresent(other -> {
            if (!other.getId().equals(id)) {
                throw new IllegalStateException("策略名已存在：" + submitted.getName());
            }
        });

        submitted.setId(id);
        automationPolicyRepository.update(submitted, expectedVersion, operator);
        return getAutomationPolicy(id);
    }

    /**
     * 启用 / 停用策略。
     *
     * <p>启用时复查完整规则，理由与动作白名单相同：策略可能是在
     * 动作还启用时配好的，之后动作被停用了。不复查就会让
     * 「停用 → 动作被停 → 重新启用」产出一条永远不会执行的僵尸策略。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public AutomationPolicy toggleAutomationPolicy(long id, boolean enabled,
                                                   int expectedVersion, String operator) {
        AutomationPolicy existing = automationPolicyRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("策略不存在: " + id));

        if (enabled) {
            validatePolicyRule(existing);
        }

        automationPolicyRepository.toggleEnabled(id, enabled, expectedVersion, operator);
        return getAutomationPolicy(id);
    }

    /**
     * 切换演练模式。
     *
     * <p><b>关掉 dry_run 是本模块风险最高的单个操作</b>——策略从「只记录」
     * 变成「真动手」。因此关闭时要重新校验一遍：不能让一条引用了
     * 已停用动作、或环境已超出授权范围的策略进入真实执行状态。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public AutomationPolicy toggleDryRun(long id, boolean dryRun,
                                         int expectedVersion, String operator) {
        AutomationPolicy existing = automationPolicyRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("策略不存在: " + id));

        if (!dryRun) {
            validatePolicyRule(existing);
        }

        automationPolicyRepository.toggleDryRun(id, dryRun, expectedVersion, operator);
        return getAutomationPolicy(id);
    }

    /**
     * 删除策略。
     *
     * <p>与动作白名单不同，策略<b>允许物理删除</b>：它不是审计记录的关联键，
     * 执行历史里记的是 action_key 与具体目标，删掉一条匹配规则
     * 不会让历史记录变成孤儿。而策略往往是试出来的，
     * 不给删除会让列表迅速堆满废弃规则，反而干扰求值顺序的判断。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteAutomationPolicy(long id, int expectedVersion) {
        automationPolicyRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("策略不存在: " + id));
        automationPolicyRepository.delete(id, expectedVersion);
    }

    /**
     * 匹配预演：给定一个告警，看哪些策略会命中、最终会发生什么。
     *
     * <p>这是本模块最有价值的端点。策略配置的核心风险是
     * <b>「匹配范围与预期不符」</b>——你以为只会匹配 order 服务，
     * 实际把整个集群都圈进去了，而这件事在真实告警来临前无从发现。</p>
     *
     * <p>预演按引擎真实的求值顺序（priority 升序）走一遍，
     * 逐条给出命中与否、以及命中后的最终判定（含白名单与风险策略的联合结论）。</p>
     */
    @Transactional(readOnly = true)
    public Map<String, Object> simulate(String level, String module, String service,
                                        String alertName, String environment) {
        List<Map<String, Object>> evaluated = new ArrayList<>();
        Map<String, Object> firstEffective = null;
        boolean stopped = false;

        for (AutomationPolicy p : automationPolicyRepository.findEnabledInEvalOrder()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("policyId", p.getId());
            row.put("policyName", p.getName());
            row.put("priority", p.getPriority());
            row.put("actionKey", p.getActionKey());
            row.put("dryRun", p.isDryRun());

            if (stopped) {
                // 前面已有 stopOnMatch 命中，后续策略引擎根本不会求值。
                // 如实标注而不是跳过不显示——用户需要知道「这条没被求值」
                // 与「这条求值了但没匹配」的区别
                row.put("matched", false);
                row.put("skipped", true);
                row.put("reason", "前序策略已命中且设置了「命中即停」，引擎不会求值到这里");
                evaluated.add(row);
                continue;
            }

            boolean envMatch = p.getEnvironment() != null
                    && p.getEnvironment().equalsIgnoreCase(environment);
            boolean matched = envMatch && p.matches(level, module, service, alertName);
            row.put("matched", matched);
            row.put("skipped", false);

            if (!matched) {
                row.put("reason", !envMatch
                        ? "策略生效环境为 " + p.getEnvironment() + "，与本次 " + environment + " 不符"
                        : describeMismatch(p, level, module, service, alertName));
                evaluated.add(row);
                continue;
            }

            // 命中后还要过白名单与风险策略——策略说要做，不代表允许做
            Map<String, Object> verdict = evaluate(p.getActionKey(), environment);
            row.put("actionVerdict", verdict);

            boolean allowed = Boolean.TRUE.equals(verdict.get("allowed"));
            if (!allowed) {
                row.put("outcome", "BLOCKED");
                row.put("reason", "策略命中，但动作被拦截：" + verdict.get("reason"));
            } else if (p.isDryRun()) {
                row.put("outcome", "DRY_RUN");
                row.put("reason", "策略命中且动作允许，但处于演练模式，只记录不执行");
            } else if (Boolean.TRUE.equals(verdict.get("requiresApproval"))) {
                row.put("outcome", "PENDING_APPROVAL");
                row.put("reason", "策略命中，将创建审批单等待人工确认");
            } else {
                row.put("outcome", "EXECUTE");
                row.put("reason", "策略命中，将直接自动执行");
            }

            if (firstEffective == null) {
                firstEffective = row;
            }
            evaluated.add(row);

            if (p.isStopOnMatch()) {
                stopped = true;
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("input", Map.of(
                "level", level == null ? "" : level,
                "module", module == null ? "" : module,
                "service", service == null ? "" : service,
                "alertName", alertName == null ? "" : alertName,
                "environment", environment));
        result.put("evaluated", evaluated);
        result.put("matchedCount", evaluated.stream()
                .filter(r -> Boolean.TRUE.equals(r.get("matched"))).count());
        result.put("firstEffective", firstEffective);
        if (firstEffective == null) {
            result.put("summary", "没有任何启用中的策略匹配该告警，将走默认流程（自动建单，人工处理）");
        } else {
            result.put("summary", "将由策略「" + firstEffective.get("policyName") + "」处理："
                    + firstEffective.get("reason"));
        }
        return result;
    }

    /** 说清「为什么没匹配」，逐个条件比对。只说结论用户无法自己调整规则 */
    private String describeMismatch(AutomationPolicy p, String level, String module,
                                    String service, String alertName) {
        List<String> reasons = new ArrayList<>();
        if (!p.matchesLevel(level)) {
            reasons.add("级别要求 " + p.getMatchAlertLevels() + "，实际 " + level);
        }
        if (!p.matchesModule(module)) {
            reasons.add("模块要求 " + p.getMatchModule() + "，实际 " + module);
        }
        if (!p.matchesService(service)) {
            reasons.add("服务需匹配 " + p.getMatchServicePattern() + "，实际 " + service);
        }
        if (!p.matchesAlertName(alertName)) {
            reasons.add("告警名需匹配 " + p.getMatchAlertNamePattern() + "，实际 " + alertName);
        }
        return reasons.isEmpty() ? "条件不满足" : String.join("；", reasons);
    }

    /**
     * 装填所引用动作的当前状态与「是否真的会生效」。
     *
     * <p>与白名单的 effective 值同理：这个判断放在服务端，
     * 让前端各自去算必然与引擎漂移。</p>
     */
    private void applyActionState(AutomationPolicy p) {
        Optional<ActionAllowlistEntry> found =
                allowlistRepository.findByActionKey(p.getActionKey());

        if (found.isEmpty()) {
            p.setActionEnabled(Boolean.FALSE);
            p.setEffective(Boolean.FALSE);
            p.setIneffectiveReason("引用的动作「" + p.getActionKey() + "」不在白名单中");
            return;
        }

        ActionAllowlistEntry action = found.get();
        p.setActionDisplayName(action.getDisplayName());
        p.setActionRiskLevel(action.getRiskLevel());
        p.setActionEnabled(action.isEnabled());

        if (!p.isEnabled()) {
            p.setEffective(Boolean.FALSE);
            p.setIneffectiveReason("策略未启用");
        } else if (!action.isEnabled()) {
            // 这正是必须装填动作状态的原因：策略自己是「已启用」，
            // 但引用的动作被停用了，实际永远不会执行
            p.setEffective(Boolean.FALSE);
            p.setIneffectiveReason("引用的动作「" + action.getDisplayName() + "」已停用");
        } else if (!action.allowsEnvironment(p.getEnvironment())) {
            p.setEffective(Boolean.FALSE);
            p.setIneffectiveReason("动作未在 " + p.getEnvironment() + " 环境开放");
        } else {
            p.setEffective(Boolean.TRUE);
            p.setIneffectiveReason(null);
        }
    }

    /**
     * 策略规则校验。
     *
     * <p>与白名单的校验同属一类：跨表约束不能只靠 DB。
     * 这里额外守住一条「所有匹配条件不能同时为空」——
     * 那等于「对所有告警执行这个动作」，几乎必然是配错了，
     * 而它造成的后果是全站范围的。</p>
     */
    private void validatePolicyRule(AutomationPolicy p) {
        requireText(p.getName(), 64, "策略名");
        if (p.getDescription() != null && p.getDescription().length() > MAX_DESCRIPTION_LENGTH) {
            throw new IllegalArgumentException("描述不能超过 " + MAX_DESCRIPTION_LENGTH + " 字");
        }
        requireRange(p.getPriority(), 1, 9999, "求值顺序");
        requireRange(p.getCooldownMinutes(), 0, 1440, "冷却期（分钟）");
        requireRange(p.getMaxExecutionsPerDay(), 1, 1000, "每日执行上限");

        // ── 匹配条件不能全空 ──────────────────────────────────────
        boolean allBlank = isBlank(p.getMatchAlertLevels())
                && isBlank(p.getMatchModule())
                && isWildcard(p.getMatchServicePattern())
                && isWildcard(p.getMatchAlertNamePattern());
        if (allBlank) {
            throw new IllegalArgumentException(
                    "至少要指定一个匹配条件。全部留空意味着「对所有告警执行该动作」，"
                            + "影响面是全站范围的");
        }

        // 级别词表校验：写错的级别（如 P5、Critical）会让策略静默永不匹配
        if (!isBlank(p.getMatchAlertLevels())) {
            for (String lv : p.getMatchAlertLevels().split(",")) {
                String v = lv.trim().toUpperCase(Locale.ROOT);
                if (v.isEmpty()) {
                    continue;
                }
                if (!KNOWN_ALERT_LEVELS.contains(v)) {
                    throw new IllegalArgumentException(
                            "未知告警级别「" + v + "」，可选：" + String.join(" / ",
                                    new java.util.TreeSet<>(KNOWN_ALERT_LEVELS)));
                }
            }
        }

        // ── 环境词表 ──────────────────────────────────────────────
        if (p.getEnvironment() == null || !KNOWN_ENVIRONMENTS.contains(p.getEnvironment())) {
            throw new IllegalArgumentException(
                    "生效环境不合法，可选：" + String.join(" / ", KNOWN_ENVIRONMENTS));
        }

        // ── 引用的动作必须存在、启用，且在该环境开放 ────────────────
        ActionAllowlistEntry action = allowlistRepository.findByActionKey(p.getActionKey())
                .orElseThrow(() -> new IllegalArgumentException(
                        "引用的动作不存在于白名单：" + p.getActionKey()
                                + "。请先在「动作白名单」中登记该动作"));

        if (!action.isEnabled()) {
            throw new IllegalArgumentException(
                    "引用的动作「" + action.getDisplayName() + "」当前已停用，"
                            + "启用策略前请先启用该动作——否则策略会成为永不执行的僵尸规则");
        }

        if (!action.allowsEnvironment(p.getEnvironment())) {
            throw new IllegalArgumentException(
                    "动作「" + action.getDisplayName() + "」未在 " + p.getEnvironment()
                            + " 环境开放（当前开放：" + action.getEnvironments() + "）");
        }

        // 参数必须是合法 JSON——留到引擎执行时才发现格式错，
        // 意味着故障当下自愈失败，那是最不能出问题的时刻
        if (!isBlank(p.getActionParams())) {
            try {
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(p.getActionParams());
            } catch (Exception e) {
                throw new IllegalArgumentException("动作参数不是合法 JSON：" + e.getMessage());
            }
        }
    }

    /** 归一化：去空白、统一大小写 */
    private void normalizePolicy(AutomationPolicy p) {
        if (p.getName() != null) {
            p.setName(p.getName().trim());
        }
        if (p.getDescription() != null) {
            p.setDescription(p.getDescription().trim());
        }
        if (p.getMatchAlertLevels() != null) {
            // 去重保序 + 统一大写，让 "p3, P3 " 存成 "P3"
            Set<String> levels = new java.util.LinkedHashSet<>();
            for (String lv : p.getMatchAlertLevels().split(",")) {
                String v = lv.trim().toUpperCase(Locale.ROOT);
                if (!v.isEmpty()) {
                    levels.add(v);
                }
            }
            p.setMatchAlertLevels(String.join(",", levels));
        }
        if (p.getMatchModule() != null) {
            p.setMatchModule(p.getMatchModule().trim().toUpperCase(Locale.ROOT));
        }
        if (p.getMatchServicePattern() != null) {
            p.setMatchServicePattern(p.getMatchServicePattern().trim());
        }
        if (p.getMatchAlertNamePattern() != null) {
            p.setMatchAlertNamePattern(p.getMatchAlertNamePattern().trim());
        }
        if (p.getActionKey() != null) {
            p.setActionKey(p.getActionKey().trim().toLowerCase(Locale.ROOT));
        }
        if (p.getEnvironment() != null) {
            p.setEnvironment(p.getEnvironment().trim().toLowerCase(Locale.ROOT));
        }
        if (p.getActionParams() != null && p.getActionParams().isBlank()) {
            p.setActionParams(null);
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** 空或纯 {@code *} 都视为「不限制」 */
    private static boolean isWildcard(String s) {
        return isBlank(s) || "*".equals(s.trim());
    }
}
