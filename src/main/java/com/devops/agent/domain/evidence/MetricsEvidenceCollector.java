package com.devops.agent.domain.evidence;

import com.devops.agent.infrastructure.metrics.MetricsUnavailableException;
import com.devops.agent.infrastructure.metrics.PromQuery;
import com.devops.agent.infrastructure.metrics.PrometheusClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 指标取证装配（S1-1 核心）：Prometheus 查询 → IQR 异常检测 → {@link Evidence}。
 * <p>
 * 三/四态落点（路线图 §5.2 1-1.5，§5.3 📌）：
 * <ul>
 *   <li>数据源未启用 → {@code UNAVAILABLE}（部署形态，非运行故障）；</li>
 *   <li>调用抛 {@link MetricsUnavailableException}（含熔断打开）→ {@code FAILED}
 *       ——证据缺口，绝不可伪装成「指标正常」；</li>
 *   <li>查询成功但返回空序列 → {@code NO_DATA}；</li>
 *   <li>有数据 → {@code SUCCESS}（含异常清单，可能为空——无异常≠查询失败）。</li>
 * </ul>
 * 时间窗解析：支持 {@code 30m / 2h / 1d}，默认 30m，上限 7d（防止模型传 365d 把 Prometheus 打爆）。
 * </p>
 */
@Component
public class MetricsEvidenceCollector {

    private static final Logger log = LoggerFactory.getLogger(MetricsEvidenceCollector.class);

    private static final Pattern RANGE_PATTERN = Pattern.compile("^(\\d+)([mhd])$");
    private static final long MAX_RANGE_MINUTES = 7 * 24 * 60;

    private final PrometheusClient prometheusClient;
    private final MetricsQueryCatalog catalog;

    public MetricsEvidenceCollector(PrometheusClient prometheusClient, MetricsQueryCatalog catalog) {
        this.prometheusClient = prometheusClient;
        this.catalog = catalog;
    }

    /**
     * 采集（无治理注解——治理归工具层 @ToolMeta，超时/重试由 ToolRuntimeManager 执行）。
     *
     * @param service 服务名（先经 catalog 白名单校验，外部由工具层保证）
     * @param range   时间窗字符串，null/空 → 30m
     * @param metrics 逗号分隔指标名，null/空 → 目录默认全部
     */
    public Evidence collect(String service, String range, String metrics) {
        // —— 三态前置 ——
        if (!prometheusClient.isEnabled()) {
            return new Evidence(Evidence.EvidenceStatus.UNAVAILABLE, Evidence.Type.METRICS,
                    "Prometheus 未启用（devops.metrics.prometheus.enabled=false）",
                    Map.of("hint", "配置 enabled=true 与 base-url 后重试"), null, null, Instant.now());
        }
        long rangeMinutes;
        try {
            rangeMinutes = parseRangeMinutes(range);
        } catch (IllegalArgumentException e) {
            return new Evidence(Evidence.EvidenceStatus.FAILED, Evidence.Type.METRICS,
                    "时间窗参数解析失败: " + e.getMessage(), Map.of("input", String.valueOf(range)),
                    null, null, Instant.now());
        }

        List<String> metricNames = resolveMetrics(metrics);
        if (metricNames.isEmpty()) {
            return new Evidence(Evidence.EvidenceStatus.FAILED, Evidence.Type.METRICS,
                    "指标名均不在查询目录中",
                    Map.of("requested", String.valueOf(metrics),
                            "available", String.join(",", catalog.availableMetrics())),
                    null, null, Instant.now());
        }

        Instant to = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant from = to.minus(rangeMinutes, ChronoUnit.MINUTES);
        // 采样步长：目标 ~60 点/序列（30m→30s；2h→2m；1d→24m），小样本够 IQR，响应体可控
        int stepSeconds = (int) Math.max(15, rangeMinutes * 60 / 60);

        List<Map<String, Object>> anomalies = new ArrayList<>();
        Map<String, Object> perMetric = new LinkedHashMap<>();
        List<String> promqls = new ArrayList<>();
        int seriesTotal = 0;
        try {
            for (String name : metricNames) {
                String promql = catalog.render(name, service);
                if (promql == null) continue;
                promqls.add(promql);
                List<PromQuery.Series> series = prometheusClient.queryRange(promql, from, to, stepSeconds);
                seriesTotal += series.size();
                List<Map<String, Object>> metricAnomalies = new ArrayList<>();
                for (PromQuery.Series s : series) {
                    List<Double> values = s.points().stream().map(PromQuery.Point::value).toList();
                    MetricsAnomalyDetector.SeriesVerdict v = MetricsAnomalyDetector.analyze(
                            labelsToString(s.labels()), values);
                    if (v.anomalous()) {
                        Map<String, Object> a = new LinkedHashMap<>();
                        a.put("series", v.metricLabels());
                        a.put("baseline", v.baseline());
                        a.put("currentValue", v.currentValue());
                        a.put("deviationFactor", v.deviationFactor());
                        a.put("anomalyCount", v.anomalyCount());
                        a.put("samples", v.samples());
                        metricAnomalies.add(a);
                    }
                }
                Map<String, Object> summary = new LinkedHashMap<>();
                summary.put("series", series.size());
                summary.put("anomalies", metricAnomalies);
                summary.put("promql", promql);
                perMetric.put(name, summary);
                anomalies.addAll(metricAnomalies);
            }
        } catch (MetricsUnavailableException e) {
            log.warn("[S1-1] 指标取证 FAILED | service={} | {}", service, e.getMessage());
            return new Evidence(Evidence.EvidenceStatus.FAILED, Evidence.Type.METRICS,
                    "Prometheus 查询失败: " + e.getMessage(),
                    Map.of("service", service, "attemptedPromql", String.join(" ; ", promqls)),
                    promqls.isEmpty() ? null : String.join(" ; ", promqls),
                    null, Instant.now());
        }

        if (seriesTotal == 0) {
            return new Evidence(Evidence.EvidenceStatus.NO_DATA, Evidence.Type.METRICS,
                    "时间窗内无匹配序列（服务名是否真实存在？label 约定是否匹配？）",
                    Map.of("service", service, "metrics", String.join(",", metricNames),
                            "rangeMinutes", rangeMinutes),
                    String.join(" ; ", promqls), null, Instant.now());
        }

        Map<String, Object> content = new LinkedHashMap<>();
        content.put("service", service);
        content.put("rangeMinutes", rangeMinutes);
        content.put("stepSeconds", stepSeconds);
        content.put("anomalyCount", anomalies.size());
        content.put("anomalies", anomalies);
        content.put("perMetric", perMetric);
        return new Evidence(Evidence.EvidenceStatus.SUCCESS, Evidence.Type.METRICS,
                anomalies.isEmpty()
                        ? "指标无显著异常（" + metricNames.size() + " 项，IQR 判定）"
                        : "检出 " + anomalies.size() + " 个异常序列（" + String.join(",", metricNames) + "）",
                content, String.join(" ; ", promqls), null, Instant.now());
    }

    /** "30m"/"2h"/"1d" → 分钟；null/空=30；超上限抛参错。 */
    static long parseRangeMinutes(String range) {
        if (range == null || range.isBlank()) return 30;
        Matcher m = RANGE_PATTERN.matcher(range.trim().toLowerCase());
        if (!m.matches()) {
            throw new IllegalArgumentException("只支持数字+m/h/d 后缀（如 30m、2h、1d），收到: " + range);
        }
        long v = Long.parseLong(m.group(1));
        long minutes = switch (m.group(2)) {
            case "m" -> v;
            case "h" -> v * 60;
            case "d" -> v * 1440;
            default -> throw new IllegalStateException("不可达");
        };
        if (minutes <= 0 || minutes > MAX_RANGE_MINUTES) {
            throw new IllegalArgumentException("时间窗需在 1m ~ 7d 之间，收到: " + range);
        }
        return minutes;
    }

    private List<String> resolveMetrics(String metrics) {
        if (metrics == null || metrics.isBlank()) {
            return List.copyOf(catalog.availableMetrics());
        }
        List<String> out = new ArrayList<>();
        for (String raw : metrics.split(",")) {
            String name = raw.trim();
            if (!name.isEmpty() && catalog.getTemplates().containsKey(name)) out.add(name);
        }
        return out;
    }

    private static String labelsToString(Map<String, String> labels) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> e : labels.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append(e.getKey()).append("=\"").append(e.getValue()).append("\"");
        }
        return sb.append("}").toString();
    }
}
