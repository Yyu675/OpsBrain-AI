package com.devops.agent.application.runtime;

import com.devops.agent.domain.alert.AlertSignal;
import com.devops.agent.domain.alert.repository.AlertRepository;
import com.devops.agent.domain.alert.service.AlertService;
import com.devops.agent.domain.notify.Notifier;
import com.devops.agent.infrastructure.metrics.BusinessMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 通知链静默看门狗（方案③）行为测试。
 *
 * <p>保护的契约：通知系统自身的故障必须能被「发不出去」这一事实暴露——
 * 自监控不能被自己要监控的对象卡死；同时「未配置渠道」是未知态而非故障，
 * 绝不能对本地/未填 webhook 环境误报。</p>
 */
@DisplayName("通知链静默看门狗（方案③）")
class NotifySilentWatchdogSchedulerTest {

    private Notifier notifier;
    private AlertService alertService;
    private AlertRepository alertRepository;
    private BusinessMetrics metrics;
    private NotifySilentWatchdogScheduler scheduler;

    @BeforeEach
    void setUp() {
        notifier = mock(Notifier.class);
        alertService = mock(AlertService.class);
        alertRepository = mock(AlertRepository.class);
        metrics = mock(BusinessMetrics.class);
        when(notifier.available()).thenReturn(true);
        when(alertRepository.findActiveByName(anyString())).thenReturn(Optional.empty());
        // snapshot 会拷贝 notifySnapshot 的结果——mock 默认返回 null 会 NPE，必须给空 Map
        when(metrics.notifySnapshot()).thenReturn(new LinkedHashMap<>());
        scheduler = new NotifySilentWatchdogScheduler(notifier, alertService, alertRepository, true);
        org.springframework.test.util.ReflectionTestUtils.setField(scheduler, "metrics", metrics);
    }

    /** 两轮调用：首轮只记基线，次轮产生 delta 判定 */
    private void twoScans(long attemptsBefore, long attemptsAfter,
                          long successBefore, long successAfter, boolean active) {
        when(metrics.notifyAttempts()).thenReturn(attemptsBefore, attemptsAfter);
        when(metrics.notifySuccesses()).thenReturn(successBefore, successAfter);
        when(alertRepository.findActiveByName(anyString())).thenReturn(
                active ? Optional.of(new com.devops.agent.domain.alert.entity.Alert())
                       : Optional.empty());
        scheduler.checkNotifySilence(); // 基线轮
        scheduler.checkNotifySilence();  // 判定轮
    }

    @SuppressWarnings("unchecked")
    private List<AlertSignal> capturedSignals() {
        ArgumentCaptor<List<AlertSignal>> cap = ArgumentCaptor.forClass(List.class);
        verify(alertService).processSignals(cap.capture());
        return cap.getValue();
    }

    @Test
    @DisplayName("首轮只记基线不误报")
    void firstRunOnlyBaselines() {
        when(metrics.notifyAttempts()).thenReturn(5L);
        when(metrics.notifySuccesses()).thenReturn(2L);
        scheduler.checkNotifySilence();

        verify(alertService, never()).processSignals(any());
    }

    @Test
    @DisplayName("窗口内有发送 0 成功 + 渠道可用 → 上报 OpsBrainNotifySilent 元告警")
    void silentWindowRaisesMetaAlert() {
        twoScans(5L, 9L, 2L, 2L, false);

        AlertSignal s = capturedSignals().get(0);
        assertFalse(s.resolved());
        assertEquals(NotifySilentWatchdogScheduler.SILENT_NAME, s.alertName());
        assertEquals("notification-pipeline", s.service());
        assertTrue(Boolean.TRUE.equals(scheduler.snapshot().get("silent")));
    }

    @Test
    @DisplayName("成功恢复 + 元告警活跃 → 提交 resolved 自动关闭")
    void recoveryResolves() {
        twoScans(9L, 12L, 2L, 5L, true);

        assertTrue(capturedSignals().get(0).resolved());
        assertFalse(Boolean.TRUE.equals(scheduler.snapshot().get("silent")));
    }

    @Test
    @DisplayName("未配置渠道（available=false）= 未知态，不开火不误报")
    void unconfiguredNeverFires() {
        when(notifier.available()).thenReturn(false);
        twoScans(1L, 4L, 0L, 0L, false);

        verify(alertService, never()).processSignals(any());
        assertFalse(Boolean.TRUE.equals(scheduler.snapshot().get("silent")));
    }
}
