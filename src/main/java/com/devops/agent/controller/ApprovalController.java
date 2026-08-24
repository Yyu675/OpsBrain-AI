package com.devops.agent.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.stp.StpUtil;
import com.devops.agent.application.runtime.ApprovalOrchestrator;
import com.devops.agent.common.dto.ApiResponse;
import com.devops.agent.domain.approval.ApprovalRequest;
import com.devops.agent.domain.approval.ApprovalService;
import com.devops.agent.domain.auth.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 审批中心接口（方向 D：L3 人机协同审批）
 *
 * <p>对齐蓝图 §二：P0/P1 高危动作「必须由人工专家审查确认点击后，AI 才可执行」。</p>
 *
 * <ul>
 *   <li>GET  /api/v1/approvals            —— 审批列表（默认待审）</li>
 *   <li>GET  /api/v1/approvals/{id}       —— 审批单详情</li>
 *   <li>POST /api/v1/approvals/{id}/approve —— 批准并重放执行</li>
 *   <li>POST /api/v1/approvals/{id}/reject  —— 驳回（理由必填）</li>
 *   <li>GET  /api/v1/approvals/pending/count —— 待审数（前端角标）</li>
 * </ul>
 *
 * <h3>安全</h3>
 * 类级 {@code @SaCheckRole("ADMIN")}——审批是授权行为，仅管理员可执行。
 * 审批人身份取自 Sa-Token 登录态，<b>不接受前端传入</b>（防伪造审批人）。
 *
 * @author OpsBrain AI
 * @since 2026-08-20
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/approvals")
@SaCheckRole("ADMIN")
public class ApprovalController {

    private final ApprovalService approvalService;
    private final ApprovalOrchestrator orchestrator;
    private final UserRepository userRepository;

    public ApprovalController(ApprovalService approvalService,
                             ApprovalOrchestrator orchestrator,
                             UserRepository userRepository) {
        this.approvalService = approvalService;
        this.orchestrator = orchestrator;
        this.userRepository = userRepository;
    }

    public record DecisionRequest(String reason) {}

    /**
     * 审批列表
     *
     * @param status 状态筛选：省略/PENDING=待审队列（最早优先）；ALL=全部；其他=指定状态（最新优先）
     */
    @GetMapping
    public ApiResponse<Map<String, Object>> list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (status == null || status.isBlank() || "PENDING".equalsIgnoreCase(status)) {
            return ApiResponse.success(approvalService.listPending(page, size));
        }
        String filter = "ALL".equalsIgnoreCase(status) ? null : status.trim().toUpperCase();
        return ApiResponse.success(approvalService.listByStatus(filter, page, size));
    }

    /** 待审数（前端角标） */
    @GetMapping("/pending/count")
    public ApiResponse<Map<String, Object>> pendingCount() {
        Map<String, Object> data = approvalService.listPending(1, 1);
        return ApiResponse.success(Map.of("pending", data.getOrDefault("total", 0)));
    }

    @GetMapping("/{id}")
    public ApiResponse<ApprovalRequest> detail(@PathVariable Long id) {
        try {
            return ApiResponse.success(approvalService.getById(id));
        } catch (ApprovalService.ApprovalException e) {
            return ApiResponse.error(40004, e.getMessage());
        } catch (Exception e) {
            log.error("❌ [ApprovalController] 查询审批单失败 | id={}", id, e);
            return ApiResponse.error(50001, "查询审批单失败");
        }
    }

    /**
     * 批准并重放执行
     * <p>批准后立即执行被拦下的动作（如创建工单）；执行失败标 EXECUTE_FAILED，
     * 批准本身不回退（人的决策是既成事实）。</p>
     */
    @PostMapping("/{id}/approve")
    public ApiResponse<ApprovalRequest> approve(@PathVariable Long id,
                                                @RequestBody(required = false) DecisionRequest body) {
        String approver = currentApprover();
        try {
            ApprovalRequest result = orchestrator.approveAndExecute(
                    id, approver, body != null ? body.reason() : null);
            String msg = "EXECUTED".equals(result.getStatus())
                    ? "已批准并执行成功"
                    : "已批准，但执行失败：" + result.getExecuteResult();
            return ApiResponse.success(result, msg);
        } catch (ApprovalService.ApprovalException e) {
            // 不存在 / 已被他人处理
            int code = e.getMessage() != null && e.getMessage().contains("不存在") ? 40004 : 40102;
            return ApiResponse.error(code, e.getMessage());
        } catch (Exception e) {
            log.error("❌ [ApprovalController] 批准失败 | id={}", id, e);
            return ApiResponse.error(50001, "批准失败，请稍后重试");
        }
    }

    /** 驳回（理由必填） */
    @PostMapping("/{id}/reject")
    public ApiResponse<ApprovalRequest> reject(@PathVariable Long id,
                                               @RequestBody(required = false) DecisionRequest body) {
        String approver = currentApprover();
        String reason = body != null ? body.reason() : null;
        try {
            return ApiResponse.success(orchestrator.reject(id, approver, reason), "已驳回");
        } catch (ApprovalService.ApprovalException e) {
            String msg = e.getMessage() != null ? e.getMessage() : "驳回失败";
            int code = msg.contains("不存在") ? 40004
                    : msg.contains("理由") ? 40001
                    : 40102;
            return ApiResponse.error(code, msg);
        } catch (Exception e) {
            log.error("❌ [ApprovalController] 驳回失败 | id={}", id, e);
            return ApiResponse.error(50001, "驳回失败，请稍后重试");
        }
    }

    /**
     * 当前审批人：取自 Sa-Token 登录态，不接受前端传入（防伪造审批人）
     */
    private String currentApprover() {
        try {
            Long userId = StpUtil.getLoginIdAsLong();
            return userRepository.findById(userId)
                    .map(u -> u.getDisplayName() != null && !u.getDisplayName().isBlank()
                            ? u.getDisplayName() : u.getUsername())
                    .orElse("user:" + userId);
        } catch (Exception e) {
            // @SaCheckRole 已保证登录，理论不会到此
            return "unknown";
        }
    }
}
