package com.devops.agent.domain.healing;

import com.devops.agent.domain.approval.ApprovalService;
import com.devops.agent.infrastructure.persistence.repo.OperationAuditRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * HealingOrchestrator 五条主路径：AUTO 直执行、审批建单、拒绝留痕、
 * 批准后重走执行、非待审态防重放。纯 JUnit + Mockito。
 */
@DisplayName("HealingOrchestrator（S3-1 批次 2：编排三路径）")
class HealingOrchestratorTest {

    private HealingGate gate;
    private HealingExecutionRepository repository;
    private ApprovalService approvalService;
    private OperationAuditRepository operationAuditRepository;
    private HealingOrchestrator orchestrator;

    private static HealingAction action() {
        return new HealingAction("mock.disk.cleanup", "prod", "ns:prod/app-user",
                Map.of("gracePeriodSeconds", 30), 42L, "auto", Instant.now());
    }

    @BeforeEach
    void setUp() {
        gate = mock(HealingGate.class);
        repository = mock(HealingExecutionRepository.class);
        approvalService = mock(ApprovalService.class);
        operationAuditRepository = mock(OperationAuditRepository.class);
        orchestrator = new HealingOrchestrator(
                gate, new ExecutorRegistry(List.of(new MockActionExecutor())),
                repository, approvalService, operationAuditRepository);
        when(repository.insert(any())).thenReturn(1001L);
    }

    @Test
    @DisplayName("AUTO_EXECUTE：dryRun → execute → 一行终态 SUCCEEDED（带快照/撤销凭据）")
    void autoExecuteRunsAndPersists() {
        when(gate.decide(any())).thenReturn(
                HealingGate.GateDecision.auto("mock.disk.cleanup", "mock", 3, 300));
        when(repository.countRecentBlocking(eq(42L), anyString(), any())).thenReturn(0);

        var outcome = orchestrator.handle(action());

        assertEquals(HealingExecution.Status.SUCCEEDED, outcome.status());
        assertEquals(1001L, outcome.executionId());
        assertNull(outcome.approvalId(), "AUTO 路径不应产生审批单");
        verify(approvalService, never()).submit(anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), isNull());

        ArgumentCaptor<HealingExecution> captor = ArgumentCaptor.forClass(HealingExecution.class);
        verify(repository, times(1)).insert(captor.capture());
        HealingExecution row = captor.getValue();
        assertEquals("SUCCEEDED", row.status());
        assertEquals("mock", row.executorKey());
        assertTrue(row.preSnapshotJson().contains("ns:prod/app-user"),
                "执行当下必须把快照落行，事后无从补拍");
        assertTrue(row.undoToken().startsWith("mock-undo-"));
    }

    @Test
    @DisplayName("REQUIRES_APPROVAL：建单 + PENDING_APPROVAL，执行器 execute 永不被调用")
    void approvalPathCreatesTicketOnly() {
        when(gate.decide(any())).thenReturn(
                HealingGate.GateDecision.needsApproval("mock.disk.cleanup", "SINGLE", "mock", 1, 600));
        when(approvalService.submit(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), isNull())).thenReturn(99L);

        var outcome = orchestrator.handle(action());

        assertEquals(HealingExecution.Status.PENDING_APPROVAL, outcome.status());
        assertEquals(99L, outcome.approvalId());
        assertNull(outcome.result(), "待审态行不得携带执行结果（说明执行器没被真实调用）");

        ArgumentCaptor<HealingExecution> captor = ArgumentCaptor.forClass(HealingExecution.class);
        verify(repository).insert(captor.capture());
        HealingExecution row = captor.getValue();
        assertEquals("PENDING_APPROVAL", row.status());
        assertEquals(99L, row.approvalId());
        assertTrue(row.dryRunPlan().contains("MOCK 演算通过"),
                "审批单展示的「将要发生什么」必须来自演算输出");
    }

    @Test
    @DisplayName("DENIED：一行 REJECTED 留痕，执行器与审批流双双不触")
    void deniedLeavesAuditRow() {
        when(gate.decide(any())).thenReturn(
                HealingGate.GateDecision.denied("mock.disk.cleanup", "该动作未登记在白名单中"));

        var outcome = orchestrator.handle(action());

        assertEquals(HealingExecution.Status.REJECTED, outcome.status());
        verify(repository).insert(any());
        verify(approvalService, never()).submit(anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), isNull());
        verify(repository, never()).markFinished(anyLong(), anyString(), anyString(),
                anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("批准后重走：PENDING_APPROVAL 行 → dryRun+execute → markFinished + 审批单回写")
    void executeApprovedHappyPath() {
        HealingExecution pending = new HealingExecution(1002L, "mock.disk.cleanup", "prod",
                "ns:prod/app-user", "{\"gracePeriodSeconds\":30}", 42L, "auto",
                "REQUIRES_APPROVAL", 99L, "mock", "PENDING_APPROVAL",
                "MOCK 演算通过", null, null, null, null, null, null);
        when(repository.findById(1002L)).thenReturn(Optional.of(pending));

        var outcome = orchestrator.executeApproved(1002L);

        assertEquals(HealingExecution.Status.SUCCEEDED, outcome.status());
        verify(repository).markFinished(eq(1002L), eq("SUCCEEDED"),
                anyString(), isNull(), anyString(), anyString());
        verify(approvalService).recordExecution(eq(99L), eq(true), anyString());
    }

    @Test
    @DisplayName("防重放：非待审态行 executeApproved 直接拒绝（台账的不可篡改性）")
    void executeApprovedRejectsNonPending() {
        HealingExecution done = new HealingExecution(1003L, "mock.disk.cleanup", "prod",
                "t", "{}", 1L, "auto", "AUTO_EXECUTE", null, "mock", "SUCCEEDED",
                null, "out", null, "{}", "tok", null, null);
        when(repository.findById(1003L)).thenReturn(Optional.of(done));

        assertThrows(IllegalStateException.class, () -> orchestrator.executeApproved(1003L));
        verify(repository, never()).markFinished(anyLong(), anyString(), anyString(),
                anyString(), anyString(), anyString());
        verify(approvalService, never()).recordExecution(anyLong(), anyBoolean(), anyString());
    }

    // ---------------- 批次 3：撤销路径 ----------------

    private static HealingExecution succeededRow(long id) {
        return new HealingExecution(id, "mock.disk.cleanup", "prod",
                "ns:prod/app-user", "{\"gracePeriodSeconds\":30}", 42L, "auto",
                "AUTO_EXECUTE", null, "mock", "SUCCEEDED",
                "MOCK 演算通过", "MOCK 执行成功",
                null, "{\"target\":\"ns:prod/app-user\"}", "mock-undo-abcd1234", null, null);
    }

    @Test
    @DisplayName("撤销：SUCCEEDED + undo_token 行 → 执行器 undo → 台账 UNDONE")
    void undoHappyPath() {
        when(repository.findById(2001L)).thenReturn(Optional.of(succeededRow(2001L)));

        var outcome = orchestrator.undo(2001L);

        assertEquals(HealingExecution.Status.UNDONE, outcome.status());
        verify(repository).markUndoOutcome(eq(2001L), eq("UNDONE"),
                anyString(), isNull());
    }

    @Test
    @DisplayName("撤销：非 SUCCEEDED 行直接拒绝（FAILED/REJECTED/PENDING 无成功可撤）")
    void undoRejectsNonSucceeded() {
        HealingExecution failed = new HealingExecution(2002L, "mock.disk.cleanup", "prod",
                "t", "{}", 1L, "auto", "AUTO_EXECUTE", null, "mock", "FAILED",
                null, null, "boom", null, null, null, null);
        when(repository.findById(2002L)).thenReturn(Optional.of(failed));

        assertThrows(IllegalStateException.class, () -> orchestrator.undo(2002L));
        verify(repository, never()).markUndoOutcome(anyLong(), anyString(),
                anyString(), anyString());
    }

    @Test
    @DisplayName("撤销：无撤销凭据成功行拒绝（执行时就没拿到 token，事后不能硬撤）")
    void undoRejectsMissingToken() {
        HealingExecution noToken = new HealingExecution(2003L, "mock.disk.cleanup", "prod",
                "t", "{}", 1L, "auto", "AUTO_EXECUTE", null, "mock", "SUCCEEDED",
                null, "out", null, "{}", null, null, null);
        when(repository.findById(2003L)).thenReturn(Optional.of(noToken));

        assertThrows(IllegalStateException.class, () -> orchestrator.undo(2003L));
        verify(repository, never()).markUndoOutcome(anyLong(), anyString(),
                anyString(), anyString());
    }

    @Test
    @DisplayName("按审批单调：HEALING 批准回调按 approvalId 桥到执行台账续走")
    void executeApprovedByApprovalIdBridges() {
        HealingExecution pending = new HealingExecution(1002L, "mock.disk.cleanup", "prod",
                "ns:prod/app-user", "{\"gracePeriodSeconds\":30}", 42L, "auto",
                "REQUIRES_APPROVAL", 99L, "mock", "PENDING_APPROVAL",
                "MOCK 演算通过", null, null, null, null, null, null);
        when(repository.findByApprovalId(99L)).thenReturn(Optional.of(pending));
        when(repository.findById(1002L)).thenReturn(Optional.of(pending));

        var outcome = orchestrator.executeApprovedByApprovalId(99L);

        assertEquals(HealingExecution.Status.SUCCEEDED, outcome.status());
        verify(repository).markFinished(eq(1002L), eq("SUCCEEDED"),
                anyString(), isNull(), anyString(), anyString());
        verify(approvalService).recordExecution(eq(99L), eq(true), anyString());
    }

    // ---------------- 批次 5：幂等闸与 agent 审计 ----------------

    @Test
    @DisplayName("幂等闸（3-3.4）：窗口内同告警同动作已有活台账 → REJECTED，演算与审批双双不发生")
    void idempotencyBlocksDuplicateWithinWindow() {
        when(gate.decide(any())).thenReturn(
                HealingGate.GateDecision.auto("mock.disk.cleanup", "mock", 3, 300));
        when(repository.countRecentBlocking(eq(42L), eq("mock.disk.cleanup"), any())).thenReturn(1);

        var outcome = orchestrator.handle(action());

        assertEquals(HealingExecution.Status.REJECTED, outcome.status());
        assertTrue(outcome.message().contains("幂等拦截"), outcome.message());
        ArgumentCaptor<HealingExecution> captor = ArgumentCaptor.forClass(HealingExecution.class);
        verify(repository, times(1)).insert(captor.capture());
        assertEquals("REJECTED", captor.getValue().status());
        // 被拦截的单子绝不能产生审批单
        verify(approvalService, never()).submit(anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), isNull());
    }

    @Test
    @DisplayName("幂等豁免：手工触发（alertId 为 null）不查窗口——操作员的重复点击是明示意图")
    void manualTriggerWithoutAlertIdBypassesIdempotency() {
        when(gate.decide(any())).thenReturn(
                HealingGate.GateDecision.auto("mock.disk.cleanup", "mock", 3, 300));
        HealingAction manual = new HealingAction("mock.disk.cleanup", "prod", "ns:prod/app-user",
                Map.of(), null, "admin", Instant.now());

        var outcome = orchestrator.handle(manual);

        assertEquals(HealingExecution.Status.SUCCEEDED, outcome.status());
        verify(repository, never()).countRecentBlocking(anyLong(), anyString(), any());
        // 手工路径不旁写 agent 审计（HTTP 面已由 OperationAuditInterceptor 覆盖）
        verify(operationAuditRepository, never()).save(any());
    }

    @Test
    @DisplayName("agent 审计旁写（3-3.6）：requestedBy=auto 的 AUTO 终态落 sys_operation_audit")
    void agentPathWritesOperationAudit() {
        when(gate.decide(any())).thenReturn(
                HealingGate.GateDecision.auto("mock.disk.cleanup", "mock", 3, 300));

        orchestrator.handle(action()); // requestedBy = auto

        verify(operationAuditRepository, times(1)).save(any());
    }
}
