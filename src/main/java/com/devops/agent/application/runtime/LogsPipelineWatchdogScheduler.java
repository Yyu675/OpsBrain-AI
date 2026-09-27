package com.devops.agent.application.runtime;

import com.devops.agent.domain.alert.AlertSignal;
import com.devops.agent.domain.alert.repository.AlertRepository;
import com.devops.agent.domain.alert.service.AlertService;
import com.devops.agent.infrastructure.logs.LokiLogQueryClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 日志管道看门狗（与告警管道心跳同族，2026-09-25）。
 *
 * <p>
 * 背景：诊断的日志证据突然全部 UNAVAILABLE 时，问题往往不在服务而在采集管道——
 * Docker Desktop 重启/容器重建会让 Promtail 的 docker_sd 目标抓着失效的旧容器 ID，
 * 进程不退、日志静默断流。这类「不报错但不工作」是监控栈最典型的自故障形态。
 * </p>
 *
 * <p>机制：定时问 Loki「最新一条日志是多久前」——Promtail 抓的容器 stdout
 * 持续在写（PG checkpoint、Prometheus 自监控），无新行即采集管道失效。
 * 停滞超阈值 → 构造 {@code OpsBrainLogsSilent} 元告警走告警单写者全链。</p>
 */
@Component
public class LogsPipelineWatchdogScheduler {

    private static final Logger log = LoggerFactory.getLogger(LogsPipelineWatchdogScheduler.class);

    /** 日志静默元告警名 */
    static final String SILENT_NAME = "OpsBrainLogsSilent";

    private final LokiLogQueryClient lokiClient;
    private final AlertRepository alertRepository;
    private final AlertService alertService;
    private final Instant startedAt = Instant.now();

    /**
     * 最近一次探测结果（效能大盘「日志管道」卡读它）。volatile 单字段读写，
     * 探测是低频旁路（5 分钟一轮），不值得为它上锁。
     * null = 还没探过/探测失败（按「未知」呈现，不误报静默）。
     */
    private volatile Instant lastFreshLogAt;

    /** 总开关。未启用 Loki 时本调度器自动静默（探针返回 empty，按未知处理不报警）。 */
    @Value("${devops.alert.logs-watchdog.enabled:true}")
    private boolean enabled;

    /** 静默阈值（分钟）：容器 stdout 的正常产出间隔远低于此，超过即判采集失效 */
    @Value("${devops.alert.logs-watchdog.silence-minutes:10}")
    private int silenceMinutes;

    public LogsPipelineWatchdogScheduler(LokiLogQueryClient lokiClient,
                                         AlertRepository alertRepository,
                                         AlertService alertService) {
        this.lokiClient = lokiClient;
        this.alertRepository = alertRepository;
        this.alertService = alertService;
    }

    @Scheduled(fixedDelayString = "${devops.alert.logs-watchdog.scan-interval-ms:300000}")
    public void scan() {
        if (!enabled) {
            return;
        }
        try {
            Optional<Instant> latest = lokiClient.freshestLogAt();
            latest.ifPresent(t -> lastFreshLogAt = t);
            if (latest.isEmpty()) {
                // 未启用/探测失败/无数据：一律按「未知」处理——探测不通不等于日志断了，
                // 误报「日志管道断了」比漏报更伤信任
                return;
            }
            boolean fresh = latest.get().isAfter(Instant.now().minusSeconds(silenceMinutes * 60L));
            if (fresh) {
                resolveIfSilentActive();
                return;
            }

            // 启动宽限：进程起来不到一个阈值周期，采集可能还在追存量
            if (Instant.now().isBefore(startedAt.plusSeconds(silenceMinutes * 60L))) {
                log.debug("[LogsWatchdog] 启动宽限期内，暂不判定日志管道静默");
                return;
            }

            raiseSilent(latest.get());
        } catch (Exception e) {
            log.error("❌ [LogsWatchdog] 心跳扫描异常: {}", e.getMessage(), e);
        }
    }

    /** 日志恢复且存在活跃静默告警 → 提交 resolved 关闭它。 */
    private void resolveIfSilentActive() {
        if (alertRepository.findActiveByName(SILENT_NAME).isEmpty()) {
            return;
        }
        alertService.processSignals(List.of(new AlertSignal(
                SILENT_NAME, "logs-pipeline", "warning", "OTHER",
                "logs-pipeline-silent", "opsbrain-internal",
                Map.of("alertname", SILENT_NAME, "service", "logs-pipeline", "severity", "warning"),
                "日志采集管道已恢复", OffsetDateTime.now(), true)));
        log.info("✅ [LogsWatchdog] 日志恢复，静默元告警已关闭");
    }

    /**
     * 日志管道心跳快照（效能大盘「日志管道」卡用）。
     *
     * @return {freshestLogAt, silent, silenceMinutes, probed}；
     *         probed=false 表示还没探过（启动宽限内），前端显示「未知」而非误报静默
     */
    public Map<String, Object> snapshot() {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("freshestLogAt", lastFreshLogAt == null ? null : lastFreshLogAt.toString());
        boolean silent = lastFreshLogAt != null
                && lastFreshLogAt.isBefore(Instant.now().minusSeconds(silenceMinutes * 60L));
        body.put("silent", silent);
        body.put("silenceMinutes", silenceMinutes);
        body.put("probed", lastFreshLogAt != null);
        body.put("lokiEnabled", lokiClient.isEnabled());
        return body;
    }

    /** 静默超阈值 → 通过告警单写者构造元告警（warning 级：采集断流要紧但不致命）。 */
    private void raiseSilent(Instant lastSeen) {
        log.warn("🚨 [LogsWatchdog] 日志采集静默超 {} 分钟（最新一行：{}），上报元告警", silenceMinutes, lastSeen);
        alertService.processSignals(List.of(new AlertSignal(
                SILENT_NAME, "logs-pipeline", "warning", "OTHER",
                "logs-pipeline-silent", "opsbrain-internal",
                Map.of("alertname", SILENT_NAME, "service", "logs-pipeline", "severity", "warning"),
                "日志采集管道超过 " + silenceMinutes + " 分钟无新日志（最新一行："
                        + lastSeen + "）。诊断的日志证据正在变空。排查顺序：Promtail 目标健康度"
                        + "（docker_sd 是否抓着失效容器）→ Loki 容器 → Docker Desktop 是否刚重启。",
                OffsetDateTime.now(), false)));
    }
}
