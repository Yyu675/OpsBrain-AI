package com.devops.agent.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.stp.StpUtil;
import com.devops.agent.common.dto.ApiCode;
import com.devops.agent.common.dto.ApiResponse;
import com.devops.agent.domain.auth.UserRepository;
import com.devops.agent.domain.healing.ExecutorRegistry;
import com.devops.agent.domain.healing.HealingAction;
import com.devops.agent.domain.healing.HealingExecution;
import com.devops.agent.domain.healing.HealingExecutionRepository;
import com.devops.agent.domain.healing.HealingOrchestrator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 自愈中心接口（S3-1，L4 受控自愈）。
 *
 * <ul>
 *   <li>POST /api/v1/healing/executions           —— 手工触发自愈动作（走治理门）</li>
 *   <li>GET  /api/v1/healing/executions           —— 执行台账列表（审计）</li>
 *   <li>GET  /api/v1/healing/executions/{id}      —— 台账详情</li>
 *   <li>POST /api/v1/healing/executions/{id}/undo —— 撤销一次成功执行</li>
 *   <li>GET  /api/v1/healing/executors            —— 已注册执行器清单</li>
 * </ul>
 *
 * <h3>安全</h3>
 * 类级 {@code @SaCheckRole("ADMIN")}——触发处置动作是授权行为，仅管理员；
 * 发起人身份取自 Sa-Token 登录态，不接受前端传入。
 * 审批路径不在这里：批准走审批中心（ApprovalOrchestrator 的 HEALING 分发），
 * 本控制器只做「触发、查询、撤销」三件事。
 *
 * @author OpsBrain AI
 * @since 2026-09-08
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/healing")
@SaCheckRole("ADMIN")
public class HealingController {

    private final HealingOrchestrator orchestrator;
    private final HealingExecutionRepository repository;
    private final ExecutorRegistry registry;
    private final UserRepository userRepository;

    public HealingController(HealingOrchestrator orchestrator,
                             HealingExecutionRepository repository,
                             ExecutorRegistry registry,
                             UserRepository userRepository) {
        this.orchestrator = orchestrator;
        this.repository = repository;
        this.registry = registry;
        this.userRepository = userRepository;
    }

    /** 手工触发的入参（requestedBy 由登录态补，前端传了也不认）。 */
    public record TriggerRequest(String actionKey, String environment, String target,
                                 Map<String, Object> params, Long alertId) {}

    /** 手工触发自愈动作：门裁决 → 直执行或建审批单，结果立即返回。 */
    @PostMapping("/executions")
    public ApiResponse<HealingOrchestrator.HealingOutcome> trigger(@RequestBody TriggerRequest req) {
        if (req == null || req.actionKey() == null || req.actionKey().isBlank()
                || req.environment() == null || req.environment().isBlank()) {
            return ApiResponse.error(ApiCode.BAD_REQUEST, "actionKey 与 environment 必填");
        }
        try {
            HealingAction action = new HealingAction(
                    req.actionKey(), req.environment(), req.target(),
                    req.params(), req.alertId(), currentOperator(), Instant.now());
            HealingOrchestrator.HealingOutcome outcome = orchestrator.handle(action);
            return ApiResponse.success(outcome, outcome.message());
        } catch (Exception e) {
            log.error("❌ [HealingController] 触发自愈失败 | action={} | {}", req.actionKey(), e.getMessage(), e);
            return ApiResponse.error(ApiCode.INTERNAL_ERROR, "触发自愈失败：" + e.getMessage());
        }
    }

    /** 执行台账列表（审计视角，最新优先）。 */
    @GetMapping("/executions")
    public ApiResponse<List<HealingExecution>> list(@RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.success(repository.listRecent(limit));
    }

    /** 台账详情。 */
    @GetMapping("/executions/{id}")
    public ApiResponse<HealingExecution> detail(@PathVariable long id) {
        return repository.findById(id)
                .map(ApiResponse::success)
                .orElseGet(() -> ApiResponse.error(ApiCode.NOT_FOUND, "执行台账不存在: " + id));
    }

    /** 撤销一次已成功且有撤销凭据的执行。 */
    @PostMapping("/executions/{id}/undo")
    public ApiResponse<HealingOrchestrator.HealingOutcome> undo(@PathVariable long id) {
        try {
            HealingOrchestrator.HealingOutcome outcome = orchestrator.undo(id);
            return ApiResponse.success(outcome, outcome.message());
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(ApiCode.NOT_FOUND, e.getMessage());
        } catch (IllegalStateException e) {
            return ApiResponse.error(ApiCode.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            log.error("❌ [HealingController] 撤销失败 | id={}", id, e);
            return ApiResponse.error(ApiCode.INTERNAL_ERROR, "撤销失败，请稍后重试");
        }
    }

    /** 已注册执行器清单（管理页可见性：当前系统有几只「手」）。 */
    @GetMapping("/executors")
    public ApiResponse<List<String>> executors() {
        return ApiResponse.success(registry.registeredExecutorKeys());
    }

    /** 发起人身份：Sa-Token 登录态，不接受前端传入。 */
    private String currentOperator() {
        try {
            Long userId = StpUtil.getLoginIdAsLong();
            return userRepository.findById(userId)
                    .map(u -> u.getDisplayName() != null && !u.getDisplayName().isBlank()
                            ? u.getDisplayName() : u.getUsername())
                    .orElse("user:" + userId);
        } catch (Exception e) {
            return "unknown";
        }
    }
}
