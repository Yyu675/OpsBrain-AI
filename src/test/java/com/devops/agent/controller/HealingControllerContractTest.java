package com.devops.agent.controller;

import com.devops.agent.domain.auth.UserRepository;
import com.devops.agent.domain.healing.ExecutorRegistry;
import com.devops.agent.domain.healing.HealingExecution;
import com.devops.agent.domain.healing.HealingExecutionRepository;
import com.devops.agent.domain.healing.HealingOrchestrator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HealingControllerContractTest {

    @Mock private HealingOrchestrator orchestrator;
    @Mock private HealingExecutionRepository repository;
    @Mock private ExecutorRegistry registry;
    @Mock private UserRepository userRepository;

    @Test
    void detailParsesTimelineIntoTypedStepsWithoutChangingFields() {
        var execution = new HealingExecution(12L, "restart", "prod", "api-1", "{}", null,
                "operator", "ALLOW", null, "k8s", "SUCCEEDED", null, "done", null,
                null, null, null, null, null, null, null);
        when(repository.findById(12L)).thenReturn(Optional.of(execution));
        when(repository.readStepsJson(12L)).thenReturn(
                "[{\"name\":\"DRY_RUN\",\"status\":\"OK\",\"detail\":\"预演成功\",\"at\":\"2026-10-17T12:00:00Z\"}]");

        var response = new HealingController(orchestrator, repository, registry, userRepository).detail(12L);

        assertThat(response.getData().execution()).isEqualTo(execution);
        assertThat(response.getData().steps()).containsExactly(
                new com.devops.agent.controller.dto.HealingExecutionStep(
                        "DRY_RUN", "OK", "预演成功", "2026-10-17T12:00:00Z"));
    }

    @Test
    void malformedTimelineStillDegradesToEmptyList() {
        var execution = new HealingExecution(13L, "restart", "prod", "api-1", "{}", null,
                "operator", "ALLOW", null, "k8s", "SUCCEEDED", null, "done", null,
                null, null, null, null, null, null, null);
        when(repository.findById(13L)).thenReturn(Optional.of(execution));
        when(repository.readStepsJson(13L)).thenReturn("not-json");

        var response = new HealingController(orchestrator, repository, registry, userRepository).detail(13L);

        assertThat(response.getData().steps()).isEmpty();
    }
}
