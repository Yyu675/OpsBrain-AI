package com.devops.agent.application.runtime;

import com.devops.agent.domain.alert.AlertSignal;
import com.devops.agent.domain.alert.repository.AlertRepository;
import com.devops.agent.domain.alert.service.AlertService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;

/**
 * 管道心跳调度器（PRD FR-1.6，2026-09-25）。
 *
 * <p>
 * 背景：监控管道（Prometheus → Alertmanager → OpsBrain webhook）断流时
 * 没有任何告警可发——「没有告警」恰恰是最大的告警。昨天真实发生过：
 * Alertmanager 推 webhook 撞 401 重试到放弃，期间平台对告警全盲而无人知晓。
 * </p>
 *
 * <p>机制：Prometheus 有一条恒真看门狗规则 {@code OpsBrainPipelineWatchdog}
 * （alert.rules.yml），Alertmanager 按 repeat_interval 周期性推送给本系统。
 * 本调度器盯它的 {@code last_occurred_at}：</p>
 * <ul>
 *   <li>超时未跳 → 通过告警单写者（AlertService.processSignals）构造
 *       {@code OpsBrainPipelineSilent} 元告警（critical→P0，建单+强提醒），
 *       复用去重/建单/通知全链，重复触发只递增计数不重复建单；</li>
 *   <li>心跳恢复且存在活跃的静默元告警 → 提交 resolved 信号自动关闭它。</li>
 * </ul>
 *
 * <p>启动宽限：进程刚起来时看门狗可能还没送到（repeat_interval 量级），
 * 宽限期内不报警，避免每次重启都误报一次「管道断了」。</p>
 */
@Component
public class PipelineHeartbeatScheduler {

    private static final Logger log = LoggerFactory.getLogger(PipelineHeartbeatScheduler.class);

    /** 看门狗告警名（保留告警名归 domain 层 ReservedAlertNames，此处别名便于阅读） */
    static final String WATCHDOG_NAME = com.devops.agent.domain.alert.ReservedAlertNames.PIPELINE_WATCHDOG;

    /** 管道静默元告警名 */
    static final String SILENT_NAME = com.devops.agent.domain.alert.ReservedAlertNames.PIPELINE_SILENT;

    private final AlertRepository alertRepository;
    private final AlertService alertService;
    private final Instant startedAt = Instant.now();

    /** 总开关。数据迁移/演练期间可临时停用，避免把可预知的静默报成故障。 */
    @Value("${devops.alert.pipeline-heartbeat.enabled:true}")
    private boolean enabled;

    /**
     * 静默阈值（分钟）：超过它没收到看门狗即判管道中断。
     * 必须大于 Alertmanager 的 repeat_interval（dev=1m，生产常见 4h）——
     * 阈值小于重发间隔会全程误报。改 repeat_interval 时同步改这里。
     */
    @Value("${devops.alert.pipeline-heartbeat.silence-minutes:3}")
    private int silenceMinutes;

    public PipelineHeartbeatScheduler(AlertRepository alertRepository, AlertService alertService) {
        this.alertRepository = alertRepository;
        this.alertService = alertService;
    }

    @Scheduled(fixedDelayString = "${devops.alert.pipeline-heartbeat.scan-interval-ms:120000}")
    public void scan() {
        if (!enabled) {
            return;
        }
        try {
            Optional<LocalDateTime> lastSeen = alertRepository.findLatestOccurredAtByName(WATCHDOG_NAME);
            boolean fresh = lastSeen.isPresent()
                    && lastSeen.get().isAfter(LocalDateTime.now().minusMinutes(silenceMinutes));

            if (fresh) {
                resolveIfSilentAlertActive();
                return;
            }

            // 启动宽限：进程起来不到一个阈值周期，看门狗可能还在路上
            if (Instant.now().isBefore(startedAt.plusSeconds(silenceMinutes * 60L))) {
                log.debug("[Heartbeat] 启动宽限期内，暂不判定管道静默");
                return;
            }

            raiseSilentAlert(lastSeen.orElse(null));
        } catch (Exception e) {
            // 定时任务异常不外抛——否则 Spring 停止后续调度，心跳自己先死
            log.error("❌ [Heartbeat] 心跳扫描异常: {}", e.getMessage(), e);
        }
    }

    /** 心跳恢复且存在活跃静默告警 → 提交 resolved 信号关闭它（闭环自愈）。 */
    private void resolveIfSilentAlertActive() {
        if (alertRepository.findActiveByName(SILENT_NAME).isEmpty()) {
            return;
        }
        alertService.processSignals(java.util.List.of(new AlertSignal(
                SILENT_NAME, "monitoring-pipeline", "critical", "OTHER",
                "pipeline-silent", "opsbrain-internal",
                Map.of("alertname", SILENT_NAME, "service", "monitoring-pipeline",
                        "severity", "critical"),
                Map.of(),
                "监控管道已恢复", OffsetDateTime.now(), true, OffsetDateTime.now())));
        log.info("✅ [Heartbeat] 心跳恢复，静默元告警已关闭");
    }

    /** 静默超阈值 → 通过告警单写者构造元告警（critical→P0：建单+强提醒）。 */
    private void raiseSilentAlert(LocalDateTime lastSeen) {
        String lastSeenText = lastSeen == null ? "从未收到" : lastSeen.toString();
        log.error("🚨 [Heartbeat] 监控管道静默超 {} 分钟（看门狗最后一次：{}），上报元告警",
                silenceMinutes, lastSeenText);
        alertService.processSignals(java.util.List.of(new AlertSignal(
                SILENT_NAME, "monitoring-pipeline", "critical", "OTHER",
                "pipeline-silent", "opsbrain-internal",
                Map.of("alertname", SILENT_NAME, "service", "monitoring-pipeline",
                        "severity", "critical"),
                Map.of(),
                "看门狗告警超过 " + silenceMinutes + " 分钟未送达（最后一次："
                        + lastSeenText + "）。Prometheus→Alertmanager→webhook 链路疑似中断，"
                        + "此刻的告警正在丢失。排查顺序：Alertmanager 容器与日志 → webhook 鉴权（X-Webhook-Token）→ 后端接收端点。",
                OffsetDateTime.now(), false, null)));
    }
}
