package com.devops.agent.application.runtime;

import com.devops.agent.domain.alert.entity.Alert;
import com.devops.agent.domain.alert.AlertSignal;
import com.devops.agent.domain.alert.repository.AlertRepository;
import com.devops.agent.domain.alert.service.AlertService;
import com.devops.agent.infrastructure.logs.LokiLogQueryClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 日志管道看门狗：守护「采集断流能被看见」。
 *
 * 关键口径：探测失败/未启用一律按「未知」处理不报警——
 * 探测不通 ≠ 日志断了，误报「日志断了」比漏报更伤信任。
 */
@DisplayName("日志管道看门狗（Promtail/Loki 采集静默 → 元告警）")
class LogsPipelineWatchdogSchedulerTest {

    private LokiLogQueryClient lokiClient;
    private AlertRepository alertRepository;
    private AlertService alertService;
    private LogsPipelineWatchdogScheduler scheduler;

    @BeforeEach
    void setUp() {
        lokiClient = mock(LokiLogQueryClient.class);
        alertRepository = mock(AlertRepository.class);
        alertService = mock(AlertService.class);
        scheduler = new LogsPipelineWatchdogScheduler(lokiClient, alertRepository, alertService);
        ReflectionTestUtils.setField(scheduler, "enabled", true);
        ReflectionTestUtils.setField(scheduler, "silenceMinutes", 10);
    }

    @Test
    @DisplayName("日志新鲜：不发任何信号")
    void freshLogsStayQuiet() {
        when(lokiClient.freshestLogAt()).thenReturn(Optional.of(Instant.now()));
        when(alertRepository.findActiveByName(LogsPipelineWatchdogScheduler.SILENT_NAME))
                .thenReturn(Optional.empty());

        scheduler.scan();

        verify(alertService, never()).processSignals(anyList());
    }

    @Test
    @DisplayName("日志停滞超阈值：发出 warning 级静默元告警")
    void staleLogsRaiseMetaAlert() {
        ReflectionTestUtils.setField(scheduler, "silenceMinutes", 0);
        when(lokiClient.freshestLogAt())
                .thenReturn(Optional.of(Instant.now().minusSeconds(3600)));

        scheduler.scan();

        ArgumentCaptor<List<AlertSignal>> captor = ArgumentCaptor.forClass(List.class);
        verify(alertService).processSignals(captor.capture());
        assertThat(captor.getValue().get(0).alertName()).isEqualTo(LogsPipelineWatchdogScheduler.SILENT_NAME);
        assertThat(captor.getValue().get(0).resolved()).isFalse();
    }

    @Test
    @DisplayName("探测失败（empty）：按「未知」处理，不报警——探测不通 ≠ 日志断了")
    void probeFailureDoesNotAlarm() {
        ReflectionTestUtils.setField(scheduler, "silenceMinutes", 0);
        when(lokiClient.freshestLogAt()).thenReturn(Optional.empty());

        scheduler.scan();

        verify(alertService, never()).processSignals(anyList());
    }

    @Test
    @DisplayName("日志恢复且静默告警还开着：自动 resolved 闭环")
    void recoveryResolvesSilentAlert() {
        when(lokiClient.freshestLogAt()).thenReturn(Optional.of(Instant.now()));
        when(alertRepository.findActiveByName(LogsPipelineWatchdogScheduler.SILENT_NAME))
                .thenReturn(Optional.of(new Alert()));

        scheduler.scan();

        ArgumentCaptor<List<AlertSignal>> captor = ArgumentCaptor.forClass(List.class);
        verify(alertService).processSignals(captor.capture());
        assertThat(captor.getValue().get(0).resolved()).isTrue();
    }

    @Test
    @DisplayName("开关关闭：整轮跳过")
    void disabledSkipsEntirely() {
        ReflectionTestUtils.setField(scheduler, "enabled", false);
        scheduler.scan();
        verify(lokiClient, never()).freshestLogAt();
    }
}
