package com.devops.agent.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.devops.agent.application.runtime.SagaCompensationManager;
import com.devops.agent.common.dto.ApiResponse;
import com.devops.agent.domain.tools.ToolExecutionRecord;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Saga 事务运维控制器
 * <p>
 * 供运维人员处理补偿失败的脏数据。参考 Agent Methodology §9.4：
 * 补偿失败时标记 {@code MANUAL_INTERVENTION_REQUIRED} 并触发通知，
 * 必须有配套的人工处理入口，否则脏数据永久残留。
 * </p>
 *
 * <h3>权限：限 ADMIN（与其余治理端点一致）</h3>
 * 本控制器此前<b>没有任何角色注解</b>，是四个治理类控制器里唯一的例外
 * （{@code AuditLogController} / {@code ApprovalController} /
 * {@code AutomationGovernanceController} / {@code AgentTraceController} 均已限 ADMIN）。
 *
 * <p>它暴露的能力恰恰是权限敏感的两类：</p>
 * <ul>
 *   <li><b>读</b>——{@code /attention} 与 {@code /steps} 列出所有半残事务的
 *       工具名、业务主键、失败原因。这是一张「系统哪里坏了、坏在哪条数据上」的清单，
 *       等同于把内部故障面直接摊开；</li>
 *   <li><b>写</b>——{@code /compensate} 会真正执行<b>逆向补偿</b>，
 *       回滚已经落库的写操作（如作废已创建的工单）。
 *       虽然补偿动作幂等、重复执行安全，但「谁都能触发一次回滚」本身就是问题：
 *       普通用户对着一个 sagaId 反复调用，就能把别人刚建好的单据撤掉。</li>
 * </ul>
 *
 * <p>注：{@code WebConfig} 的拦截器已要求全局登录，所以此前并非完全裸奔；
 * 但「登录用户」与「管理员」之间的差距，正是本注解要补上的那一层。
 * 前端 Saga 补偿中心目前仍是 {@code FutureCapability} 占位页，
 * 尚未有真实入口——趁尚未接入先把服务端边界立好，比事后收紧安全。</p>
 *
 * @author OpsBrain AI
 * @since 2026-08-08
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/saga")
@SaCheckRole("ADMIN")
public class SagaController {

    private final SagaCompensationManager sagaManager;

    public SagaController(SagaCompensationManager sagaManager) {
        this.sagaManager = sagaManager;
    }

    /**
     * 查询需人工介入的执行记录
     * <p>
     * 包含三类：PARTIAL_SUCCESS（半残）、COMPENSATION_FAILED（补偿失败）、
     * MANUAL_INTERVENTION_REQUIRED（已标记需介入）。
     * </p>
     */
    @GetMapping("/attention")
    public ApiResponse<Map<String, Object>> listNeedingAttention(
            @RequestParam(defaultValue = "50") int limit) {

        int safeLimit = Math.min(Math.max(1, limit), 200);
        List<ToolExecutionRecord> records = sagaManager.listNeedingAttention(safeLimit);

        List<Map<String, Object>> items = new ArrayList<>(records.size());
        for (ToolExecutionRecord r : records) {
            items.add(toDetail(r));
        }

        Map<String, Object> data = new HashMap<>();
        data.put("records", items);
        data.put("count", items.size());
        return ApiResponse.success(data);
    }

    /**
     * 查询 Saga 完整执行链路（供回放与故障定位）
     *
     * @param sagaId Saga 事务 ID（等于请求的 traceId）
     */
    @GetMapping("/{sagaId}/steps")
    public ApiResponse<Map<String, Object>> listSteps(@PathVariable String sagaId) {
        List<ToolExecutionRecord> steps = sagaManager.listSagaSteps(sagaId);

        List<Map<String, Object>> items = new ArrayList<>(steps.size());
        for (ToolExecutionRecord r : steps) {
            items.add(toDetail(r));
        }

        Map<String, Object> data = new HashMap<>();
        data.put("sagaId", sagaId);
        data.put("steps", items);
        data.put("stepCount", items.size());
        return ApiResponse.success(data);
    }

    /**
     * 手动重试补偿
     * <p>
     * 用于补偿失败后，运维修复了下游问题（如数据库恢复）再重试。
     * 补偿动作幂等，重复执行安全。
     * </p>
     *
     * @param sagaId Saga 事务 ID
     */
    @PostMapping("/{sagaId}/compensate")
    public ApiResponse<Map<String, Object>> retryCompensation(@PathVariable String sagaId) {
        log.warn("🔧 [Saga] 人工触发补偿重试 | sagaId={}", sagaId);

        var result = sagaManager.compensateSaga(sagaId, "人工触发补偿重试");

        Map<String, Object> data = new HashMap<>();
        data.put("sagaId", sagaId);
        data.put("compensatedCount", result.compensatedCount());
        data.put("failedCount", result.failedCount());
        data.put("compensated", result.compensated());
        data.put("failed", result.failed());
        data.put("fullySucceeded", result.isFullySucceeded());

        if (result.needsManualIntervention()) {
            return ApiResponse.success(data);  // 仍有失败，前端按 failedCount 提示
        }
        return ApiResponse.success(data);
    }

    // ==================== 内部转换 ====================

    private Map<String, Object> toDetail(ToolExecutionRecord r) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", r.getId());
        m.put("traceId", r.getTraceId());
        m.put("sessionId", r.getSessionId());
        m.put("sagaId", r.getSagaId());
        m.put("stepSeq", r.getStepSeq());
        m.put("toolName", r.getToolName());
        m.put("riskLevel", r.getRiskLevel() != null ? r.getRiskLevel().name() : null);
        m.put("riskLevelLabel", r.getRiskLevel() != null ? r.getRiskLevel().getDisplayName() : null);
        m.put("state", r.getState() != null ? r.getState().name() : null);
        m.put("stateLabel", r.getState() != null ? r.getState().getDisplayName() : null);
        m.put("needsAttention", r.getState() != null && r.getState().needsAttention());
        m.put("failureType", r.getFailureType() != null ? r.getFailureType().name() : null);
        m.put("failureHint", r.getFailureType() != null ? r.getFailureType().getHandlingHint() : null);
        m.put("errorMessage", r.getErrorMessage());
        m.put("compensable", r.getCompensable());
        m.put("compensationAction", r.getCompensationAction());
        m.put("businessKey", r.getBusinessKey());
        m.put("compensatedAt", r.getCompensatedAt());
        m.put("compensationError", r.getCompensationError());
        m.put("attemptCount", r.getAttemptCount());
        m.put("durationMs", r.getDurationMs());
        m.put("createTime", r.getCreateTime());
        m.put("updateTime", r.getUpdateTime());
        return m;
    }
}