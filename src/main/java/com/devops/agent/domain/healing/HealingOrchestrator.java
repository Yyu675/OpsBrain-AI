package com.devops.agent.domain.healing;

import com.devops.agent.common.audit.OperationAuditRecord;
import com.devops.agent.domain.approval.ApprovalService;
import com.devops.agent.domain.biz.service.TicketService;
import com.devops.agent.infrastructure.persistence.repo.OperationAuditRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 自愈编排器（S3-1 批次 2）：治理门裁决 → 演算 → （审批）→ 执行 → 台账。
 * <p>
 * 三条路径（全部留痕，无一绕过台账）：
 * <ul>
 *   <li><b>AUTO_EXECUTE</b>：dryRun 通过 → 直接执行 → 一行终态
 *       （SUCCEEDED/FAILED 带快照与撤销凭据）；</li>
 *   <li><b>REQUIRES_APPROVAL</b>：dryRun 通过 → 创建审批单（payload 可重放）
 *       → 一行 PENDING_APPROVAL；批准后由 {@link #executeApproved} 续走
 *       → markFinished 回填终态 → 回写审批单执行结果；</li>
 *   <li><b>DENIED / NO_EXECUTOR</b>：立即一行 REJECTED，reason 落 error 列——
 *       拒绝也是审计事实，不是「没发生」。</li>
 * </ul>
 * </p>
 * <p>
 * 本类只管「该不该、做没做、做成没」。<b>怎么拍快照、怎么撤销</b>
 * （错误率监控联动 + undo 触发）是批次 3 回滚触发器的职责。
 * </p>
 */
@Service
public class HealingOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(HealingOrchestrator.class);

    private final HealingGate gate;
    private final ExecutorRegistry registry;
    private final HealingExecutionRepository repository;
    private final ApprovalService approvalService;
    private final OperationAuditRepository operationAuditRepository;
    private final VerifierRegistry verifierRegistry;
    private final TicketService ticketService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 幂等窗口（3-3.4）：同一告警同一动作在该秒数内已有活台账则拦截。
     * 字段带默认值——单元测试手工构造时不走 Spring 注入也是 300。
     */
    @Value("${devops.healing.idempotency-window-seconds:300}")
    private int idempotencyWindowSeconds = 300;

    public HealingOrchestrator(HealingGate gate,
                               ExecutorRegistry registry,
                               HealingExecutionRepository repository,
                               ApprovalService approvalService,
                               OperationAuditRepository operationAuditRepository,
                               VerifierRegistry verifierRegistry,
                               TicketService ticketService) {
        this.gate = gate;
        this.registry = registry;
        this.repository = repository;
        this.approvalService = approvalService;
        this.operationAuditRepository = operationAuditRepository;
        this.verifierRegistry = verifierRegistry;
        this.ticketService = ticketService;
    }

    /**
     * 对入站动作走完整一条编排路径（响应 API 的同步出口）。
     *
     * @return 本次编排的终局视图——无论是自动直执行、进了审批还是被拒
     */
    public HealingOutcome handle(HealingAction action) {
        // 1. 门裁决（唯一准入裁决点）
        HealingGate.GateDecision decision = gate.decide(action);
        log.info("[Healing] 门裁决 | action={} | type={} | reason={}",
                action.actionKey(), decision.type(), decision.reason());

        if (decision.type() == HealingGate.DecisionType.DENIED
                || decision.type() == HealingGate.DecisionType.NO_EXECUTOR) {
            long id = repository.insert(toDraft(action, decision, null, null,
                    HealingExecution.Status.REJECTED, null, null, null, null));
            auditIfAgent(action, "healing.execute.rejected", id, false, decision.reason());
            return HealingOutcome.terminal(id, decision, HealingExecution.Status.REJECTED,
                    decision.reason(), null);
        }

        // 2. 幂等闸（3-3.4）：同一告警同一动作在窗口内已有活台账 → 拦截。
        //    手工触发（alertId 为 null）不拦——操作员的重复点击是明示意图。
        if (action.alertId() != null) {
            LocalDateTime since = LocalDateTime.now().minusSeconds(idempotencyWindowSeconds);
            int blocking = repository.countRecentBlocking(
                    action.alertId(), action.actionKey(), since);
            if (blocking > 0) {
                String reason = "幂等拦截：告警 #" + action.alertId() + " 的「" + action.actionKey()
                        + "」在 " + idempotencyWindowSeconds + " 秒内已有 " + blocking
                        + " 条待审/成功台账，拒绝重复执行";
                long id = repository.insert(toDraft(action, decision, null, null,
                        HealingExecution.Status.REJECTED, null, reason, null, null));
                auditIfAgent(action, "healing.execute.rejected", id, false, reason);
                log.warn("[Healing] 幂等拦截 | id={} | alert={} | action={} | blocking={}",
                        id, action.alertId(), action.actionKey(), blocking);
                return HealingOutcome.terminal(id, decision,
                        HealingExecution.Status.REJECTED, reason, null);
            }
        }

        // 3. 演算（审批单/执行计划的事实依据）
        Optional<ActionExecutor> executorOpt = registry.locate(action.actionKey());
        if (executorOpt.isEmpty()) {          // 竞态防御：门刚查过，此处双保险
            long id = repository.insert(toDraft(action, decision, null, null,
                    HealingExecution.Status.REJECTED, null, "注册表演算前执行器消失", null, null));
            auditIfAgent(action, "healing.execute.rejected", id, false, "注册表演算前执行器消失");
            return HealingOutcome.terminal(id, decision, HealingExecution.Status.REJECTED,
                    "注册表演算前执行器消失", null);
        }
        ActionExecutor executor = executorOpt.get();
        ExecutionResult dry = executor.dryRun(action);
        if (!dry.success()) {
            long id = repository.insert(toDraft(action, decision, null, null,
                    HealingExecution.Status.FAILED, null, dry.error(), null, null));
            auditIfAgent(action, "healing.execute", id, false, "演算未通过: " + dry.error());
            return HealingOutcome.terminal(id, decision, HealingExecution.Status.FAILED,
                    "演算未通过：" + dry.error(), null);
        }

        // 3. 分支：免审批直执行 / 要审批建单
        if (decision.type() == HealingGate.DecisionType.AUTO_EXECUTE) {
            ExecutionResult real = executor.execute(action);
            HealingExecution stored = toDraft(action, decision, dry, real,
                    real.success() ? HealingExecution.Status.SUCCEEDED : HealingExecution.Status.FAILED,
                    real.output(), real.error(), real.preSnapshot(), real.undoToken());
            long id = repository.insert(stored);
            auditIfAgent(action, "healing.execute", id, real.success(),
                    real.success() ? null : real.error());
            log.warn("[Healing] AUTO_EXECUTE 终态 | id={} | action={} | success={}",
                    id, action.actionKey(), real.success());
            return HealingOutcome.terminal(id, decision, stored.status(),
                    real.success() ? "已执行" : "执行失败：" + real.error(), real);
        }

        // 4. 审批路径：payload 可重放（审批批准后执行器按同样参数重走 dryRun+execute）
        String payloadJson = buildApprovalPayload(action, dry);
        Long approvalId = approvalService.submit(
                "HEALING",                                     // actionType：审批页区分自愈单
                action.actionKey(),
                executor.permissionLevel(action.actionKey()).name(),  // 风险列 = 执行器声明的权限等级
                buildSummary(action, dry),
                payloadJson, action.requestedBy(),
                traceId(action), null);                        // sessionId 未上手
        HealingExecution pending = toDraft(action, decision, dry, null,
                HealingExecution.Status.PENDING_APPROVAL, null, null, null, null);
        long id = repository.insert(HealingExecution.draft(
                pending.actionKey(), pending.environment(), pending.target(), pending.paramsJson(),
                pending.alertId(), pending.requestedBy(), pending.gateDecision(), approvalId,
                decision.executorKey(), pending.status(), pending.dryRunPlan(), null,
                null, null, null));
        auditIfAgent(action, "healing.submit_approval", id, true, null);
        log.warn("[Healing] REQUIRES_APPROVAL | id={} | approvalId={} | action={} | mode={}",
                id, approvalId, action.actionKey(), decision.approvalMode());
        return HealingOutcome.pending(id, decision, approvalId, dry);
    }

    /**
     * 审批单被批准后的续走入口（HealingController 在批审回调中调）。
     * <p>
     * 契约：行必须处于 PENDING_APPROVAL；按台账里的原始参数重走
     * dryRun + execute；终态回填 + 审批单执行结果回写。
     * </p>
     */
    public HealingOutcome executeApproved(long executionId) {
        HealingExecution row = repository.findById(executionId)
                .orElseThrow(() -> new IllegalArgumentException("执行台账不存在: id=" + executionId));
        if (!HealingExecution.Status.PENDING_APPROVAL.equals(row.status())) {
            throw new IllegalStateException(
                    "执行台账非待审批态，禁止重复执行: id=" + executionId + ", status=" + row.status());
        }
        HealingAction action = replayFromRow(row);
        Optional<ActionExecutor> executorOpt = registry.locate(row.actionKey());
        if (executorOpt.isEmpty()) {
            repository.markFinished(executionId, HealingExecution.Status.FAILED,
                    null, "批准复核时执行器已不可用", null, null);
            auditIfAgent(action, "healing.execute_approved", executionId, false, "批准复核时执行器已不可用");
            return HealingOutcome.terminal(executionId,
                    HealingGate.GateDecision.denied(row.actionKey(), "批准复核时执行器已不可用"),
                    HealingExecution.Status.FAILED, "批准复核时执行器已不可用", null);
        }
        ActionExecutor executor = executorOpt.get();

        ExecutionResult dry = executor.dryRun(action);
        if (!dry.success()) {
            repository.markFinished(executionId, HealingExecution.Status.FAILED,
                    null, "批准复核演算未通过: " + dry.error(), null, null);
            auditIfAgent(action, "healing.execute_approved", executionId, false, "批准复核演算未通过");
            return HealingOutcome.terminal(executionId,
                    HealingGate.GateDecision.needsApproval(row.actionKey(), "SINGLE", executor.executorKey(), null, null),
                    HealingExecution.Status.FAILED, "批准复核演算未通过: " + dry.error(), null);
        }

        ExecutionResult real = executor.execute(action);
        String finalStatus = real.success()
                ? HealingExecution.Status.SUCCEEDED : HealingExecution.Status.FAILED;
        repository.markFinished(executionId, finalStatus,
                real.output(), real.error(), mapToJson(real.preSnapshot()), real.undoToken());
        if (row.approvalId() != null) {
            approvalService.recordExecution(row.approvalId(), real.success(),
                    real.success() ? real.output() : real.error());
        }
        auditIfAgent(action, "healing.execute_approved", executionId, real.success(),
                real.success() ? null : real.error());
        log.warn("[Healing] 批准执行终态 | id={} | approvalId={} | success={}",
                executionId, row.approvalId(), real.success());
        return HealingOutcome.terminal(executionId,
                HealingGate.GateDecision.needsApproval(row.actionKey(), row.gateDecision(),
                        executor.executorKey(), null, null),
                finalStatus,
                real.success() ? "批准后执行成功" : "批准后执行失败：" + real.error(), real);
    }

    /**
     * 审批中心回调的桥：按审批单 id 找到执行台账续走。
     * （ApprovalOrchestrator 的 replay 分发 HEALING 类型时走这里。）
     */
    public HealingOutcome executeApprovedByApprovalId(long approvalId) {
        HealingExecution row = repository.findByApprovalId(approvalId)
                .orElseThrow(() -> new IllegalStateException(
                        "HEALING 审批单没有对应的执行台账: approvalId=" + approvalId));
        return executeApproved(row.id());
    }

    /**
     * 执行后验证（S3-3/§7.4）：对一次 SUCCEEDED 的执行跑验证器，
     * 验证未通过 → 自动撤销（undo）→ 升级人工工单。
     * <p>
     * 这正是 3-3.5 Saga 补偿在自愈域的落点：自动补偿的唯一有意义
     * 触发点是「验证发现执行没起效」——为补偿而补偿只会徒增动作面。
     * UNHEALTHY 且撤销成功 → P1 工单（已自动回滚，人知悉即可）；
     * UNHEALTHY 且撤销失败/无凭据 → P0 工单（系统还在病态，人必须立刻接管）。
     * </p>
     *
     * @return 验证终局视图（复用 HealingOutcome 载体）
     */
    public HealingOutcome verifyAndMaybeRollback(long executionId) {
        HealingExecution row = repository.findById(executionId)
                .orElseThrow(() -> new IllegalArgumentException("执行台账不存在: id=" + executionId));
        if (!HealingExecution.Status.SUCCEEDED.equals(row.status())) {
            throw new IllegalStateException(
                    "只有执行成功（SUCCEEDED）的台账可验证: id=" + executionId + ", status=" + row.status());
        }
        if (row.verifyStatus() != null) {
            throw new IllegalStateException(
                    "该台账已验证过（" + row.verifyStatus() + "），重复验证没有增量信息: id=" + executionId);
        }

        Optional<ActionVerifier> verifierOpt = verifierRegistry.locate(row.actionKey());
        if (verifierOpt.isEmpty()) {
            // 无验证器也必须有结论性留痕——未验证的 SUCCEEDED 不能长得像已验证
            repository.markVerified(executionId, HealingExecution.Verify.SKIPPED,
                    "{\"summary\":\"无匹配验证器\"}");
            return HealingOutcome.terminal(executionId,
                    HealingGate.GateDecision.auto(row.actionKey(), row.executorKey(), null, null),
                    HealingExecution.Verify.SKIPPED, "无匹配验证器，标记 SKIPPED", null);
        }

        HealingAction action = replayFromRow(row);
        ExecutionResult executionEcho = new ExecutionResult(
                row.executorKey(), row.actionKey(), true, false, row.output(), null,
                jsonToMap(row.preSnapshotJson()), row.undoToken(), null, null);
        ActionVerifier.VerificationResult verification =
                verifierOpt.get().verify(action, executionEcho);

        boolean pass = verification.isHealthy();
        String verifyJson = mapToJson(Map.of(
                "status", verification.status(),
                "summary", verification.summary() == null ? "" : verification.summary(),
                "before", verification.before(),
                "after", verification.after()));
        repository.markVerified(executionId,
                pass ? HealingExecution.Verify.PASS
                        : (ActionVerifier.VerificationResult.UNKNOWN.equals(verification.status())
                                ? HealingExecution.Verify.UNKNOWN : HealingExecution.Verify.FAIL),
                verifyJson);

        if (pass) {
            log.info("[Healing] 验证通过 | id={} | {}", executionId, verification.summary());
            return HealingOutcome.terminal(executionId,
                    HealingGate.GateDecision.auto(row.actionKey(), row.executorKey(), null, null),
                    HealingExecution.Verify.PASS, "验证通过：" + verification.summary(), null);
        }
        if (ActionVerifier.VerificationResult.UNKNOWN.equals(verification.status())) {
            log.warn("[Healing] 验证未知 | id={} | {}", executionId, verification.summary());
            return HealingOutcome.terminal(executionId,
                    HealingGate.GateDecision.auto(row.actionKey(), row.executorKey(), null, null),
                    HealingExecution.Verify.UNKNOWN, "验证未知：" + verification.summary(), null);
        }

        // FAIL：自动撤销 + 升级人工（P1 已回滚 / P0 未回滚）
        boolean undoSucceeded = false;
        String undoNote;
        if (row.undoToken() == null || row.undoToken().isBlank()) {
            undoNote = "该执行没有撤销凭据，无法自动回滚";
        } else {
            HealingOutcome undoOutcome = undo(executionId);
            undoSucceeded = HealingExecution.Status.UNDONE.equals(undoOutcome.status());
            undoNote = undoSucceeded ? "已自动回滚（台账转 UNDONE）"
                    : "自动回滚失败: " + undoOutcome.message();
        }
        String ticketId = escalateToTicket(row, verification, undoSucceeded, undoNote);
        log.error("[Healing] 验证失败已升级 | id={} | undo={} | ticket={}",
                executionId, undoNote, ticketId);
        return HealingOutcome.terminal(executionId,
                HealingGate.GateDecision.auto(row.actionKey(), row.executorKey(), null, null),
                HealingExecution.Verify.FAIL,
                "验证失败：" + verification.summary() + "；" + undoNote + "；升级工单 " + ticketId, null);
    }

    /**
     * 心跳批扫描（定时任务入口）：把观察窗内所有未验证的 SUCCEEDED 台账
     * 逐个验证。单条异常不影响整批（记录下批再来）。
     *
     * @param settleSeconds  沉淀期（刚执行完的指标未稳定，不准验）
     * @param observeSeconds 观察窗（超出窗口的陈旧执行不再验）
     */
    public int verifyPendingBatch(int settleSeconds, int observeSeconds, int batchLimit) {
        LocalDateTime now = LocalDateTime.now();
        List<HealingExecution> pending = repository.listPendingVerification(
                now.minusSeconds(settleSeconds), now.minusSeconds(observeSeconds), batchLimit);
        int processed = 0;
        for (HealingExecution row : pending) {
            try {
                verifyAndMaybeRollback(row.id());
                processed++;
            } catch (Exception ex) {
                log.warn("[Healing] 心跳验证单条失败（下批重试）| id={} | {}",
                        row.id(), ex.getMessage());
            }
        }
        if (processed > 0) {
            log.info("[Healing] 心跳验证批次完成 | processed={} | pending={}",
                    processed, pending.size());
        }
        return processed;
    }

    /** 升级人工工单（P1 已回滚 / P0 回不去）。创建失败只记日志，不影响验证留痕。 */
    private String escalateToTicket(HealingExecution row,
                                    ActionVerifier.VerificationResult verification,
                                    boolean undoSucceeded, String undoNote) {
        try {
            String priority = undoSucceeded ? "P1" : "P0";
            String title = "自愈验证失败 · " + row.actionKey() + " @ " + row.target();
            String description = "执行台账 #" + row.id() + "（告警 #" + row.alertId() + "）\n"
                    + "验证结论：" + verification.summary() + "\n"
                    + "自动回滚：" + undoNote + "\n"
                    + "请人工核查目标系统当前状态并补齐处置。";
            var ticket = ticketService.createTicket(title, priority, "healing",
                    description, null, null, null, "agent-healing");
            return ticket != null && ticket.getId() != null ? "#" + ticket.getId() : "(创建返回空)";
        } catch (Exception ex) {
            log.error("❌ [Healing] 升级工单创建失败 | id={} | {}", row.id(), ex.getMessage());
            return "(创建失败: " + ex.getMessage() + ")";
        }
    }

    /**
     * 撤销一次已成功的执行（批次 3：手动撤销入口；后续监控联动的
     * 回滚触发器复用同一入口——快照与凭据 V7 起已当场落行）。
     * <p>
     * 契约：行必须 SUCCEEDED 且带 undo_token；执行器必须仍支持撤销。
     * 撤销成功 → UNDONE；撤销失败 → UNDO_FAILED（原成功事实不动，
     * 失败单独留痕，这是审计的诚实性——不能把撤而不成记成 UNDONE）。
     * </p>
     */
    public HealingOutcome undo(long executionId) {
        HealingExecution row = repository.findById(executionId)
                .orElseThrow(() -> new IllegalArgumentException("执行台账不存在: id=" + executionId));
        if (!HealingExecution.Status.SUCCEEDED.equals(row.status())) {
            throw new IllegalStateException(
                    "只有执行成功（SUCCEEDED）的台账可撤销: id=" + executionId + ", status=" + row.status());
        }
        if (row.undoToken() == null || row.undoToken().isBlank()) {
            throw new IllegalStateException("该次执行没有撤销凭据（undo_token 为空），不可撤销: id=" + executionId);
        }
        HealingAction action = replayFromRow(row);
        Optional<ActionExecutor> executorOpt = registry.locate(row.actionKey());
        if (executorOpt.isEmpty()) {
            repository.markUndoOutcome(executionId, HealingExecution.Status.UNDO_FAILED,
                    null, "撤销时执行器已不可用");
            auditIfAgent(action, "healing.undo", executionId, false, "撤销时执行器已不可用");
            return HealingOutcome.terminal(executionId,
                    HealingGate.GateDecision.denied(row.actionKey(), "撤销时执行器已不可用"),
                    HealingExecution.Status.UNDO_FAILED, "撤销时执行器已不可用", null);
        }
        ActionExecutor executor = executorOpt.get();
        ExecutionResult undone = executor.undo(action, row.undoToken(), jsonToMap(row.preSnapshotJson()));
        if (undone.success()) {
            repository.markUndoOutcome(executionId, HealingExecution.Status.UNDONE,
                    undone.output(), null);
            auditIfAgent(action, "healing.undo", executionId, true, null);
            log.warn("[Healing] 已撤销 | id={} | action={} | token={}",
                    executionId, row.actionKey(), row.undoToken());
            return HealingOutcome.terminal(executionId,
                    HealingGate.GateDecision.auto(row.actionKey(), executor.executorKey(), null, null),
                    HealingExecution.Status.UNDONE, "已撤销：" + undone.output(), undone);
        }
        repository.markUndoOutcome(executionId, HealingExecution.Status.UNDO_FAILED,
                null, undone.error());
        auditIfAgent(action, "healing.undo", executionId, false, undone.error());
        log.warn("[Healing] 撤销失败 | id={} | action={} | error={}",
                executionId, row.actionKey(), undone.error());
        return HealingOutcome.terminal(executionId,
                HealingGate.GateDecision.auto(row.actionKey(), executor.executorKey(), null, null),
                HealingExecution.Status.UNDO_FAILED, "撤销失败：" + undone.error(), undone);
    }

    /** 台账回放：从行字段复原 HealingAction（params_json → Map）。 */
    private HealingAction replayFromRow(HealingExecution row) {
        Map<String, Object> params = jsonToMap(row.paramsJson());
        return new HealingAction(row.actionKey(), row.environment(), row.target(),
                params, row.alertId(), row.requestedBy(), null);
    }

    /**
     * agent 路径审计旁写（3-3.6）。
     * <p>
     * HTTP 面（管理员手工触发/撤销）由 OperationAuditInterceptor 全量覆盖；
     * 告警驱动（{@code requestedBy="auto"}）的执行没有 HTTP 入口，
     * 由编排器补写 sys_operation_audit——L4 合规红线是
     * 「谁在什么时候改了什么必须可追溯」，人不能缺、agent 更不能缺。
     * 旁写失败只告警不阻断：审计通道故障不该瘫痪执行主链。
     * </p>
     */
    private void auditIfAgent(HealingAction action, String auditAction, long executionId,
                              boolean success, String detail) {
        if (!"auto".equals(action.requestedBy())) {
            return;
        }
        try {
            operationAuditRepository.save(new OperationAuditRecord(
                    null, "agent", action.requestedBy(), auditAction, "healing_execution",
                    String.valueOf(executionId), null, "agent:healing",
                    success ? 200 : 500, success, null,
                    action.actionKey() + " @ " + action.target(),
                    success ? null : detail, null, null, 0, LocalDateTime.now()));
        } catch (Exception ex) {
            log.warn("⚠️ [Healing] agent 审计旁写失败（不阻断主流程）| id={} | {}",
                    executionId, ex.getMessage());
        }
    }

    // ---------------- 私有工具 ----------------

    private HealingExecution toDraft(HealingAction action, HealingGate.GateDecision decision,
                                     ExecutionResult dry, ExecutionResult real,
                                     String status, String output, String error,
                                     Map<String, Object> preSnapshot, String undoToken) {
        return HealingExecution.draft(
                action.actionKey(), action.environment(), action.target(),
                mapToJson(action.params()), action.alertId(), action.requestedBy(),
                decision.type().name(), null, decision.executorKey(), status,
                dry != null ? dry.output() : null, output, error,
                mapToJson(preSnapshot), undoToken);
    }

    private String buildSummary(HealingAction action, ExecutionResult dry) {
        return "自愈审批：" + action.actionKey() + " @ " + action.target()
                + "（环境 " + action.environment() + "）。演算：" + dry.output();
    }

    private String buildApprovalPayload(HealingAction action, ExecutionResult dry) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "actionKey", action.actionKey(),
                    "environment", action.environment(),
                    "target", action.target() == null ? "" : action.target(),
                    "params", action.params(),
                    "alertId", action.alertId() == null ? -1 : action.alertId(),
                    "dryRunPlan", dry.output() == null ? "" : dry.output()));
        } catch (Exception ex) {
            return "{\"error\":\"payload build failed\"}";
        }
    }

    private String traceId(HealingAction action) {
        return "heal-" + (action.alertId() == null ? "manual" : action.alertId())
                + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String mapToJson(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception ex) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> jsonToMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception ex) {
            return Map.of();
        }
    }

    /**
     * 编排终局视图（API 层返回的契约）。
     *
     * @param executionId 台账行 id
     * @param decision    门裁决类型（审批路径为 REQUIRES_APPROVAL）
     * @param status      台账终态
     * @param message     人类可读的终局总结
     * @param approvalId  审批单 id（PENDING_APPROVAL 时必有；终态行为 null）
     * @param dryRunPlan  演算计划文本（终端行有值 = 审批单可展示「将要发生什么」）
     * @param result      执行结果（PENDING_APPROVAL 为 null）
     */
    public record HealingOutcome(
            long executionId,
            HealingGate.DecisionType decision,
            String status,
            String message,
            Long approvalId,
            String dryRunPlan,
            ExecutionResult result) {

        public static HealingOutcome terminal(long executionId, HealingGate.GateDecision decision,
                                              String status, String message, ExecutionResult result) {
            return new HealingOutcome(executionId, decision.type(), status,
                    message, null, null, result);
        }

        public static HealingOutcome pending(long executionId, HealingGate.GateDecision decision,
                                             Long approvalId, ExecutionResult dryRunResult) {
            return new HealingOutcome(executionId, decision.type(),
                    HealingExecution.Status.PENDING_APPROVAL,
                    "已建审批单，等待人工点头", approvalId,
                    dryRunResult.output(), null);
        }
    }
}
