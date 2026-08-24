package com.devops.agent.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.stp.StpUtil;
import com.devops.agent.common.dto.ApiResponse;
import com.devops.agent.domain.governance.ActionAllowlistEntry;
import com.devops.agent.domain.governance.ApprovalMode;
import com.devops.agent.domain.governance.AutomationGovernanceService;
import com.devops.agent.domain.governance.EscalateTarget;
import com.devops.agent.domain.governance.RiskPolicy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 自动化治理配置接口（L3）：风险等级策略 + 动作白名单。
 *
 * <h3>端点</h3>
 * <ul>
 *   <li>GET    /api/v1/governance/risk-policies            —— 四级策略列表</li>
 *   <li>PUT    /api/v1/governance/risk-policies/{level}    —— 更新某一级（CAS）</li>
 *   <li>GET    /api/v1/governance/actions                  —— 白名单分页</li>
 *   <li>GET    /api/v1/governance/actions/stats            —— 顶部统计</li>
 *   <li>GET    /api/v1/governance/actions/filter-options   —— 筛选候选值</li>
 *   <li>GET    /api/v1/governance/actions/{id}             —— 详情</li>
 *   <li>POST   /api/v1/governance/actions                  —— 新增</li>
 *   <li>PUT    /api/v1/governance/actions/{id}             —— 更新（CAS）</li>
 *   <li>POST   /api/v1/governance/actions/{id}/toggle      —— 启停（CAS）</li>
 *   <li>POST   /api/v1/governance/evaluate                 —— 模拟校验</li>
 * </ul>
 *
 * <h3>权限：全部限 ADMIN</h3>
 * 这组接口配置的是「AI 能不能自动动生产系统」。让普通运维能改，
 * 等于让任何一个账号被盗的后果从「看到数据」升级为「AI 按攻击者的配置执行动作」。
 * 用 {@code @SaCheckRole} 而非只靠前端路由 meta——前端权限只是体验优化。
 *
 * <h3>为什么没有 DELETE 动作白名单</h3>
 * 只提供停用（toggle）。历史执行记录引用 {@code action_key}，
 * 物理删除会让审计记录指向不存在的动作，而审计在 L3/L4 是合规要求。
 * 这与 {@code KnowledgeDocController} 的物理删除限 ADMIN 是同一类考量，
 * 但这里更进一步：<b>根本不提供</b>。
 *
 * @author OpsBrain AI
 * @since 2026-08-25
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/governance")
@SaCheckRole("ADMIN")
public class AutomationGovernanceController {

    private final AutomationGovernanceService service;

    public AutomationGovernanceController(AutomationGovernanceService service) {
        this.service = service;
    }

    // ==================================================================
    // 请求体
    // ==================================================================

    /**
     * 风险策略更新请求。
     *
     * <p>用包装类型 {@code Integer} 而非 {@code int}：前端漏传某个字段时，
     * 基本类型会静默变成 0——「审批时限 0 分钟」意味着审批单一创建就过期，
     * 这种由默认值造成的配置错误比报错难查得多。这里让它明确报「不能为空」。</p>
     */
    public record RiskPolicyRequest(
            String approvalMode,
            Integer approvalTimeoutMinutes,
            Boolean autoExecuteAllowed,
            Integer maxBlastRadiusPercent,
            Integer maxBlastRadiusCount,
            Integer cooldownSeconds,
            Integer maxRetries,
            Integer escalateAfterMinutes,
            String escalateTarget,
            String allowedEnvironments,
            /** 客户端读到的版本号，用于 CAS。缺失即拒绝——见 requireVersion */
            Integer version
    ) {}

    public record ActionRequest(
            @NotBlank(message = "动作标识不能为空")
            @Size(max = 64, message = "动作标识不能超过 64 字")
            String actionKey,

            @NotBlank(message = "显示名称不能为空")
            @Size(max = 64, message = "显示名称不能超过 64 字")
            String displayName,

            @Size(max = 255, message = "描述不能超过 255 字")
            String description,

            @NotBlank(message = "类别不能为空")
            String category,

            @NotBlank(message = "风险等级不能为空")
            String riskLevel,

            @Size(max = 255, message = "目标匹配模式不能超过 255 字")
            String targetPattern,

            @NotBlank(message = "生效环境不能为空")
            String environments,

            String paramSchema,
            Boolean requiresApproval,
            Integer maxBlastRadiusCount,
            Boolean enabled,
            Integer version
    ) {}

    public record ToggleRequest(Boolean enabled, Integer version) {}

    public record EvaluateRequest(String actionKey, String environment) {}

    // ==================================================================
    // 风险等级策略
    // ==================================================================

    @GetMapping("/risk-policies")
    public ApiResponse<Map<String, Object>> listPolicies() {
        List<RiskPolicy> policies = service.listPolicies();
        return ApiResponse.success(Map.of(
                "items", policies,
                // 词表随数据一起下发，前端不必自己维护一份枚举镜像——
                // 镜像必然漂移，本项目已因此踩过工单状态机 8 处不一致
                "approvalModes", describeApprovalModes(),
                "escalateTargets", describeEscalateTargets()
        ));
    }

    /**
     * 更新某一级策略。
     *
     * @param level 风险等级枚举名，如 {@code HIGH_RISK_EXECUTION}
     */
    @PutMapping("/risk-policies/{level}")
    public ApiResponse<RiskPolicy> updatePolicy(@PathVariable String level,
                                                @RequestBody RiskPolicyRequest req) {
        RiskPolicy policy = new RiskPolicy();
        policy.setRiskLevel(level);
        policy.setApprovalMode(ApprovalMode.parseOrStrictest(req.approvalMode()));
        policy.setApprovalTimeoutMinutes(requireInt(req.approvalTimeoutMinutes(), "审批时限"));
        policy.setAutoExecuteAllowed(Boolean.TRUE.equals(req.autoExecuteAllowed()));
        policy.setMaxBlastRadiusPercent(requireInt(req.maxBlastRadiusPercent(), "爆炸半径百分比"));
        policy.setMaxBlastRadiusCount(requireInt(req.maxBlastRadiusCount(), "爆炸半径实例数"));
        policy.setCooldownSeconds(requireInt(req.cooldownSeconds(), "观察窗口"));
        policy.setMaxRetries(requireInt(req.maxRetries(), "最大重试次数"));
        policy.setEscalateAfterMinutes(requireInt(req.escalateAfterMinutes(), "升级等待"));
        policy.setEscalateTarget(EscalateTarget.parseOrDefault(req.escalateTarget()));
        policy.setAllowedEnvironments(req.allowedEnvironments());

        String operator = currentOperator();
        return ApiResponse.success(
                service.updatePolicy(level, policy, requireVersion(req.version()), operator),
                "策略已更新");
    }

    // ==================================================================
    // 动作白名单
    // ==================================================================

    @GetMapping("/actions")
    public ApiResponse<Map<String, Object>> listActions(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String riskLevel,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(
                service.listActions(keyword, category, riskLevel, enabled, page, size));
    }

    @GetMapping("/actions/stats")
    public ApiResponse<Map<String, Object>> actionStats() {
        return ApiResponse.success(service.actionStats());
    }

    @GetMapping("/actions/filter-options")
    public ApiResponse<Map<String, Object>> actionFilterOptions() {
        return ApiResponse.success(service.actionFilterOptions());
    }

    @GetMapping("/actions/{id}")
    public ApiResponse<ActionAllowlistEntry> actionDetail(@PathVariable long id) {
        return ApiResponse.success(service.getAction(id));
    }

    @PostMapping("/actions")
    public ApiResponse<ActionAllowlistEntry> createAction(@Valid @RequestBody ActionRequest req) {
        return ApiResponse.success(
                service.createAction(toEntry(req), currentOperator()), "动作已创建");
    }

    @PutMapping("/actions/{id}")
    public ApiResponse<ActionAllowlistEntry> updateAction(@PathVariable long id,
                                                          @Valid @RequestBody ActionRequest req) {
        return ApiResponse.success(
                service.updateAction(id, toEntry(req), requireVersion(req.version()), currentOperator()),
                "动作已更新");
    }

    /**
     * 启用 / 停用。
     *
     * <p>刻意做成独立端点而不是「更新时把 enabled 传过来」：
     * 列表页的开关只想改一个布尔值，若走全量更新，前端必须先把该行的
     * 全部字段回填一遍，任何一项读漏都会被静默重置成默认值。</p>
     */
    @PostMapping("/actions/{id}/toggle")
    public ApiResponse<ActionAllowlistEntry> toggleAction(@PathVariable long id,
                                                          @RequestBody ToggleRequest req) {
        if (req.enabled() == null) {
            throw new IllegalArgumentException("必须指定启用状态");
        }
        ActionAllowlistEntry updated = service.toggleAction(
                id, req.enabled(), requireVersion(req.version()), currentOperator());
        return ApiResponse.success(updated, req.enabled() ? "动作已启用" : "动作已停用");
    }

    /**
     * 模拟校验：「在 X 环境执行动作 Y，现在允许吗」。
     *
     * <p>这个端点的价值在于让配置<b>可验证</b>。安全配置最糟的失效模式是
     * 「以为配好了实际没生效」——用户改完一堆开关，无从确认结果。
     * 有了它，配完立刻能问一句、拿到带原因的明确答复。</p>
     */
    @PostMapping("/evaluate")
    public ApiResponse<Map<String, Object>> evaluate(@RequestBody EvaluateRequest req) {
        if (req.actionKey() == null || req.actionKey().isBlank()) {
            throw new IllegalArgumentException("动作标识不能为空");
        }
        String env = req.environment() == null || req.environment().isBlank()
                ? "prod" : req.environment();
        return ApiResponse.success(service.evaluate(req.actionKey().trim(), env));
    }

    // ==================================================================
    // 内部
    // ==================================================================

    private ActionAllowlistEntry toEntry(ActionRequest req) {
        ActionAllowlistEntry e = new ActionAllowlistEntry();
        e.setActionKey(req.actionKey());
        e.setDisplayName(req.displayName());
        e.setDescription(req.description());
        e.setCategory(req.category());
        e.setRiskLevel(req.riskLevel());
        e.setTargetPattern(req.targetPattern());
        e.setEnvironments(req.environments());
        e.setParamSchema(req.paramSchema());
        // requiresApproval / maxBlastRadiusCount 保留 null 语义（=跟随策略），
        // 不要在这里补默认值，否则「跟随策略」这一档就永远选不中了
        e.setRequiresApproval(req.requiresApproval());
        e.setMaxBlastRadiusCount(req.maxBlastRadiusCount());
        e.setEnabled(Boolean.TRUE.equals(req.enabled()));
        return e;
    }

    /**
     * 取操作者身份。
     *
     * <p>取自 Sa-Token 登录态，<b>不接受前端传入</b>——
     * 安全配置的变更记录是事后追责的依据，允许前端指定等于允许伪造。</p>
     */
    private String currentOperator() {
        try {
            Object id = StpUtil.getLoginId();
            return id == null ? "UNKNOWN" : id.toString();
        } catch (Exception e) {
            // @SaCheckRole 已保证登录，理论不会到此
            return "UNKNOWN";
        }
    }

    /**
     * 版本号必填。
     *
     * <p>缺失时不「宽容地取当前版本」——那等于关掉乐观锁。
     * 客户端没带版本号说明它不是从最新数据编辑来的，此时放行
     * 正好覆盖掉别人刚做的修改，而这恰恰是乐观锁要防的场景。</p>
     */
    private static int requireVersion(Integer version) {
        if (version == null) {
            throw new IllegalArgumentException(
                    "缺少版本号，无法安全提交。请刷新页面后重试");
        }
        return version;
    }

    private static int requireInt(Integer value, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName + "不能为空");
        }
        return value;
    }

    private static List<Map<String, Object>> describeApprovalModes() {
        return java.util.Arrays.stream(ApprovalMode.values())
                .map(m -> Map.<String, Object>of(
                        "value", m.name(),
                        "label", m.getDisplayName(),
                        "requiredApprovers", m.getRequiredApprovers()))
                .toList();
    }

    private static List<Map<String, Object>> describeEscalateTargets() {
        return java.util.Arrays.stream(EscalateTarget.values())
                .map(t -> Map.<String, Object>of(
                        "value", t.name(),
                        "label", t.getDisplayName()))
                .toList();
    }
}
