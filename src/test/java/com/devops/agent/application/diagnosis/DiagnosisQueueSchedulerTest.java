package com.devops.agent.application.diagnosis;

import com.devops.agent.domain.biz.repository.DiagnosisSessionRepository;
import com.devops.agent.domain.biz.repository.DiagnosisSessionRepository.QueuedSessionRow;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DiagnosisQueueSchedulerTest {

    @Test
    void doesNotQueryWhileExecutorQueueIsFull() {
        DiagnosisOrchestrator orchestrator = mock(DiagnosisOrchestrator.class);
        DiagnosisSessionRepository repository = mock(DiagnosisSessionRepository.class);
        when(orchestrator.remainingQueueCapacity()).thenReturn(0);

        new DiagnosisQueueScheduler(orchestrator, repository).drainQueued();

        verify(repository, never()).findQueued(org.mockito.ArgumentMatchers.anyInt());
        verify(orchestrator, never()).resumeQueued(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void limitsBatchToCapacityAndResumesOnlyRowsThatWinCas() {
        DiagnosisOrchestrator orchestrator = mock(DiagnosisOrchestrator.class);
        DiagnosisSessionRepository repository = mock(DiagnosisSessionRepository.class);
        when(orchestrator.remainingQueueCapacity()).thenReturn(3);
        when(repository.findQueued(3)).thenReturn(List.of(
                new QueuedSessionRow(11L, "trace-a", 21L, "ticket-a", "payments"),
                new QueuedSessionRow(12L, "trace-b", 22L, "ticket-b", "orders")));
        when(repository.markQueuedRunning(11L)).thenReturn(1);
        when(repository.markQueuedRunning(12L)).thenReturn(0);

        new DiagnosisQueueScheduler(orchestrator, repository).drainQueued();

        verify(repository).findQueued(3);
        verify(repository).markQueuedRunning(11L);
        verify(repository).markQueuedRunning(12L);
        verify(orchestrator).resumeQueued("trace-a", 21L, "ticket-a", "payments");
        verify(orchestrator, never()).resumeQueued("trace-b", 22L, "ticket-b", "orders");
    }

    @Test
    void capsRequestedBatchAtTwenty() {
        DiagnosisOrchestrator orchestrator = mock(DiagnosisOrchestrator.class);
        DiagnosisSessionRepository repository = mock(DiagnosisSessionRepository.class);
        when(orchestrator.remainingQueueCapacity()).thenReturn(100);
        when(repository.findQueued(20)).thenReturn(List.of());

        new DiagnosisQueueScheduler(orchestrator, repository).drainQueued();

        verify(repository).findQueued(20);
    }
}
