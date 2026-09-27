package com.devops.agent.application.runtime;

import com.devops.agent.domain.alert.AlertSignal;
import com.devops.agent.domain.alert.entity.Alert;
import com.devops.agent.domain.alert.repository.AlertRepository;
import com.devops.agent.domain.alert.service.AlertService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 管道心跳调度器（PRD FR-1.6）：「没有告警」本身必须是可告警事件。
 *
 * 守护的行为：
 *   看门狗停跳超阈值 → 通过告警单写者构造静默元告警（critical→P0 建单强提醒）；
 *   心跳恢复且存在活跃静默告警 → 自动提交 resolved 关闭它（闭环，不留僵尸单）；
 *   启动宽限期内不误报（重启 ≠ 管道故障）。
 */
@DisplayName("管道心跳调度器（看门狗静默 → 元告警）")
class PipelineHeartbeatSchedulerTest {

    private AlertRepository alertRepository;
    private AlertService alertService;
    private PipelineHeartbeatScheduler scheduler;

    @BeforeEach
    void setUp() {
        alertRepository = mock(AlertRepository.class);
        alertService = mock(AlertService.class);
        scheduler = new PipelineHeartbeatScheduler(alertRepository, alertService);
        ReflectionTestUtils.setField(scheduler, "enabled", true);
        ReflectionTestUtils.setField(scheduler, "silenceMinutes", 3);
    }

    @Test
    @DisplayName("看门狗在阈值内跳动着：不发任何信号")
    void freshWatchdogStaysQuiet() {
        when(alertRepository.findLatestOccurredAtByName(PipelineHeartbeatScheduler.WATCHDOG_NAME))
                .thenReturn(Optional.of(LocalDateTime.now()));
        when(alertRepository.findActiveByName(PipelineHeartbeatScheduler.SILENT_NAME))
                .thenReturn(Optional.empty());

        scheduler.scan();

        verify(alertService, never()).processSignals(anyList());
    }

    @Test
    @DisplayName("看门狗停跳超阈值：经告警单写者发出 critical 静默元告警")
    void staleWatchdogRaisesMetaAlert() {
        // silenceMinutes=0：跳过启动宽限与新鲜度窗口，直接命中「停跳」分支
        ReflectionTestUtils.setField(scheduler, "silenceMinutes", 0);
        when(alertRepository.findLatestOccurredAtByName(PipelineHeartbeatScheduler.WATCHDOG_NAME))
                .thenReturn(Optional.of(LocalDateTime.now().minusMinutes(10)));

        scheduler.scan();

        ArgumentCaptor<List<AlertSignal>> captor = ArgumentCaptor.forClass(List.class);
        verify(alertService).processSignals(captor.capture());
        AlertSignal signal = captor.getValue().get(0);
        assertThat(signal.alertName()).isEqualTo(PipelineHeartbeatScheduler.SILENT_NAME);
        assertThat(signal.severity()).isEqualTo("critical");   // → P0，建单+强提醒
        assertThat(signal.resolved()).isFalse();
    }

    @Test
    @DisplayName("心跳恢复且静默告警还开着：自动提交 resolved 闭环，不留僵尸单")
    void recoveredHeartbeatResolvesSilentAlert() {
        when(alertRepository.findLatestOccurredAtByName(PipelineHeartbeatScheduler.WATCHDOG_NAME))
                .thenReturn(Optional.of(LocalDateTime.now()));
        Alert activeSilent = new Alert();
        when(alertRepository.findActiveByName(PipelineHeartbeatScheduler.SILENT_NAME))
                .thenReturn(Optional.of(activeSilent));

        scheduler.scan();

        ArgumentCaptor<List<AlertSignal>> captor = ArgumentCaptor.forClass(List.class);
        verify(alertService).processSignals(captor.capture());
        assertThat(captor.getValue().get(0).resolved()).isTrue();
    }

    @Test
    @DisplayName("从未见过看门狗且已过宽限期：按「从未送达」报警")
    void neverSeenWatchdogAlarmsAfterGrace() {
        ReflectionTestUtils.setField(scheduler, "silenceMinutes", 0);
        when(alertRepository.findLatestOccurredAtByName(PipelineHeartbeatScheduler.WATCHDOG_NAME))
                .thenReturn(Optional.empty());

        scheduler.scan();

        verify(alertService).processSignals(anyList());
    }

    @Test
    @DisplayName("开关关闭：整轮跳过，连库都不查")
    void disabledSkipsEntirely() {
        ReflectionTestUtils.setField(scheduler, "enabled", false);

        scheduler.scan();

        verify(alertRepository, never()).findLatestOccurredAtByName(PipelineHeartbeatScheduler.WATCHDOG_NAME);
        verify(alertService, never()).processSignals(anyList());
    }
}
