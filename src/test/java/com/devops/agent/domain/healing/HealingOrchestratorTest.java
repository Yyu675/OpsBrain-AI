package com.devops.agent.domain.healing;

import com.devops.agent.domain.approval.ApprovalService;
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
        orchestrator = new HealingOrchestrator(
                gate, new ExecutorRegistry(List.of(new MockActionExecutor())),
                repository, approvalService);
        when(repository.insert(any())).thenReturn(1001L);
    }

    @Test
    @DisplayName("AUTO_EXECUTE：dryRun → execute → 一行终态 SUCCEEDED（带快照/撤销凭据）")
    void autoExecuteRunsAndPersists() {
        when(gate.decide(any())).thenReturn(
                HealingGate.GateDecision.auto("mock.disk.cleanup", "mock", 3, 300));

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
}
