package com.devops.agent.application.runtime;

import com.devops.agent.domain.alert.AlertSignal;
import com.devops.agent.domain.alert.ReservedAlertNames;
import com.devops.agent.domain.alert.repository.AlertRepository;
import com.devops.agent.domain.alert.service.AlertService;
import com.devops.agent.domain.notify.Notifier;
import com.devops.agent.infrastructure.metrics.BusinessMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 通知链静默看门狗（方案③，与 {@link LogsPipelineWatchdogScheduler} 同族，2026-10-01）。
 *
 * <p>
 * 背景：平台自身的告警靠「发通知」触达，而通知链故障的形态恰恰是
 * 「没有任何东西发出来」——自监控被自己要监控的对象卡死。没有这道看门狗，
 * 渠道全挂时平台只是悄悄把通知降级成日志，P0 提醒一样发不出去且无人知晓。
 * </p>
 *
 * <p>
 * 机制：每轮比对 {@link BusinessMetrics} 的通知原子账 delta——
 * 窗口内有发送尝试且成功数为 0（有渠道在却全军覆没）→ 构造
 * {@code OpsBrainNotifySilent} 元告警走告警单写者全链；成功恢复即提交
 * resolved 信号自动关闭。**未配置任何渠道（本地/未填 webhook）不算故障**：
 * {@code available()==false} 是「未知」而非「坏」，degraded 会计数但看门狗
 * 不开火——与 Loki 未启用按未知呈现同一取向（不误报）。
 * </p>
 *
 * <p>
 * 已知边界（诚实声明）：判定基于「发送受理」而非「送达回执」——渠道配错
 * token 时 HTTP 400 发生在 delegate 异步内部，受理面看不到。送达级自监控
 * 需渠道回执/死人开关，属后续批次。
 * </p>
 */
@Component
public class NotifySilentWatchdogScheduler {

    private static final Logger log = LoggerFactory.getLogger(NotifySilentWatchdogScheduler.class);

    /** 通知静默元告警名（语义归属 domain，见 ReservedAlertNames） */
    static final String SILENT_NAME = ReservedAlertNames.NOTIFY_SILENT;

    private final Notifier notifier;
    private final AlertService alertService;
    private final AlertRepository alertRepository;

    private final boolean enabled;

    /**
     * 最近一轮扫描结果（告警页/效能卡读它）。null = 尚未扫描过（按未知呈现）。
     */
    private volatile Boolean silent;
    /** 最近一次读数并完成判定的时刻（null=尚未扫描过） */
    private volatile java.time.Instant lastScanAt;
    private volatile long prevAttempts = -1;
    private volatile long prevSuccesses;

    /** 扫描间隔即健康窗口——与 @Scheduled 表达式同配置键，快照 windowMinutes 不说谎 */
    @Value("${devops.alert.notify-watchdog.scan-interval-ms:300000}")
    private long scanIntervalMs;

    /** RED 流速面可缺席（@ConditionalOnProperty）——缺席时看门狗整个按未知停摆 */
    @Autowired(required = false)
    private BusinessMetrics metrics;

    public NotifySilentWatchdogScheduler(Notifier notifier, AlertService alertService,
                                         AlertRepository alertRepository,
                                         @Value("${devops.alert.notify-watchdog.enabled:true}") boolean enabled) {
        this.notifier = notifier;
        this.alertService = alertService;
        this.alertRepository = alertRepository;
        this.enabled = enabled;
    }

    /**
     * 扫描周期即健康窗口：窗口内 {@code attempts>0 && successes==0} 判静默。
     * delta 基线首轮只记录不判定（进程刚起没有可比前值）。
     */
    @Scheduled(fixedDelayString = "${devops.alert.notify-watchdog.scan-interval-ms:300000}")
    public void checkNotifySilence() {
        if (!enabled || metrics == null) {
            return;
        }
        if (!notifier.available()) {
            silent = null; // 未配置渠道 = 未知，不误报（本地/未填 webhook 是常态）
            return;
        }
        long attempts = metrics.notifyAttempts();
        long successes = metrics.notifySuccesses();
        lastScanAt = Instant.now();
        if (prevAttempts < 0) {
            prevAttempts = attempts;
            prevSuccesses = successes;
            return;
        }
        long dAttempts = attempts - prevAttempts;
        long dSuccesses = successes - prevSuccesses;
        prevAttempts = attempts;
        prevSuccesses = successes;

        boolean active = alertRepository.findActiveByName(SILENT_NAME).isPresent();
        if (dAttempts > 0 && dSuccesses == 0 && !active) {
            silent = true;
            log.error("🔕 [NotifyWatchdog] 通知链静默 | 窗口内 {} 次发送 0 成功 | 上报元告警", dAttempts);
            alertService.processSignals(List.of(buildSignal(false,
                    "近一个窗口内 " + dAttempts + " 次通知发送 0 成功（有可用渠道但全部未受理），"
                            + "P0 提醒可能发不出去。排查：渠道配置/网络出连通/DingTalk webhook 有效性。")));
        } else if (dSuccesses > 0) {
            if (active) {
                silent = false;
                log.info("✅ [NotifyWatchdog] 通知链恢复 | 窗口内成功 {} 次，关闭元告警", dSuccesses);
                alertService.processSignals(List.of(buildSignal(true,
                        "通知渠道已恢复（窗口内成功 " + dSuccesses + " 次）")));
            } else {
                silent = false;
            }
        }
    }

    private AlertSignal buildSignal(boolean resolved, String description) {
        return new AlertSignal(
                SILENT_NAME, "notification-pipeline", "warning", "OTHER",
                "notify-pipeline-silent", "opsbrain-internal",
                Map.of("alertname", SILENT_NAME, "service", "notification-pipeline",
                        "severity", "warning"),
                Map.of(),
                description, OffsetDateTime.now(), resolved, OffsetDateTime.now());
    }

    /** 最近一轮扫描快照（告警页「通知链」卡读它，与 /logs-heartbeat 同族） */
    public Map<String, Object> snapshot() {
        Map<String, Object> m = metrics != null ? metrics.notifySnapshot() : Map.of();
        m = new java.util.LinkedHashMap<>(m);
        m.put("configured", notifier.available());
        m.put("enabled", enabled);
        m.put("silent", Boolean.TRUE.equals(silent));
        m.put("lastScanAt", lastScanAt != null ? lastScanAt.toString() : null);
        m.put("windowMinutes", Math.max(1, scanIntervalMs / 60_000));
        return m;
    }
}
