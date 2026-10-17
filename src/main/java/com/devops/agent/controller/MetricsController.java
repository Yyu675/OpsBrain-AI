package com.devops.agent.controller;

import com.devops.agent.common.dto.ApiResponse;
import com.devops.agent.controller.dto.MetricCatalogResponse;
import com.devops.agent.controller.dto.MetricDatasourceDto;
import com.devops.agent.controller.dto.MetricDatasourceResponse;
import com.devops.agent.controller.dto.MetricInstantResponse;
import com.devops.agent.controller.dto.MetricMetaDto;
import com.devops.agent.controller.dto.MetricOverviewResponse;
import com.devops.agent.controller.dto.MetricRangeResponse;
import com.devops.agent.controller.dto.MetricSampleDto;
import com.devops.agent.controller.dto.MetricSeriesDto;
import com.devops.agent.controller.dto.OverviewCardDto;
import com.devops.agent.controller.dto.SeriesPointDto;
import com.devops.agent.infrastructure.metrics.MetricsCatalog;
import com.devops.agent.infrastructure.metrics.PromQuery;
import com.devops.agent.infrastructure.metrics.PrometheusClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 指标查询接口（L2 · B2）。
 *
 * <h3>端点</h3>
 * <ul>
 *   <li>GET /api/v1/metrics/catalog        —— 内置指标目录（前端渲染选择器）</li>
 *   <li>GET /api/v1/metrics/instant        —— 瞬时值（实时监控页）</li>
 *   <li>GET /api/v1/metrics/range          —— 时间序列（趋势分析页）</li>
 *   <li>GET /api/v1/metrics/overview       —— 总览卡片（实时监控页首屏）</li>
 *   <li>GET /api/v1/metrics/datasource     —— 数据源健康（接入管理页）</li>
 * </ul>
 *
 * <h3>只暴露指标 ID，不接受任意 PromQL</h3>
 * 见 {@link MetricsCatalog} 的类注释：任意 PromQL 等于把 Prometheus
 * 的算力开放给前端，一条高基数聚合就能拖垮整个监控体系。
 *
 * <h3>权限：登录即可，不限 ADMIN</h3>
 * 与治理配置页不同——指标是<b>只读</b>的运维态势数据，
 * 一线运维本来就该看得到；限 ADMIN 会让值班工程师无法排障。
 * 真正敏感的是「改数据源配置」，那部分在接入管理页单独限权。
 *
 * @author OpsBrain AI
 * @since 2026-08-25
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/metrics")
public class MetricsController {

    /** 区间查询的最大时间跨度（天）。超过它的查询对 Prometheus 压力过大 */
    private static final long MAX_RANGE_DAYS = 31;

    private final PrometheusClient prometheus;

    public MetricsController(PrometheusClient prometheus) {
        this.prometheus = prometheus;
    }

    /** 内置指标目录 */
    @GetMapping("/catalog")
    public ApiResponse<MetricCatalogResponse> catalog() {
        List<MetricMetaDto> metaList = MetricsCatalog.describeAll().stream()
                .map(m -> new MetricMetaDto(
                        (String) m.get("id"),
                        (String) m.get("name"),
                        (String) m.get("unit"),
                        (String) m.get("describe")))
                .toList();
        return ApiResponse.success(new MetricCatalogResponse(metaList, prometheus.isEnabled()));
    }

    /**
     * 瞬时查询。
     *
     * @param metric 指标 ID，见 {@code /catalog}
     */
    @GetMapping("/instant")
    public ApiResponse<MetricInstantResponse> instant(@RequestParam String metric) {
        String promql = MetricsCatalog.promqlOf(metric);
        List<PromQuery.Sample> samples = prometheus.query(promql);

        List<MetricSampleDto> sampleDtos = samples.stream()
                .map(s -> new MetricSampleDto(s.labels(), s.hasValue() ? s.value() : null, s.timestampMs()))
                .toList();
        return ApiResponse.success(new MetricInstantResponse(metric, sampleDtos));
    }

    /**
     * 区间查询（趋势分析）。
     *
     * @param metric  指标 ID
     * @param hours   回看小时数，默认 1
     * @param step    采样步长（秒），默认 60。客户端给的值可能被上调，
     *                见 {@code PrometheusClient.queryRange} 的点数上限保护
     */
    @GetMapping("/range")
    public ApiResponse<MetricRangeResponse> range(
            @RequestParam String metric,
            @RequestParam(defaultValue = "1") int hours,
            @RequestParam(defaultValue = "60") int step) {

        int safeHours = Math.max(1, Math.min(hours, (int) (MAX_RANGE_DAYS * 24)));
        int safeStep = Math.max(1, Math.min(step, 3600));

        Instant to = Instant.now();
        Instant from = to.minusSeconds((long) safeHours * 3600);

        String promql = MetricsCatalog.promqlOf(metric);
        List<PromQuery.Series> series = prometheus.queryRange(promql, from, to, safeStep);

        List<MetricSeriesDto> seriesDtos = series.stream()
                .map(s -> new MetricSeriesDto(
                        s.labels(),
                        s.points().stream()
                                .map(p -> new SeriesPointDto(p.timestampMs(), Double.isNaN(p.value()) ? null : p.value()))
                                .toList()))
                .toList();

        return ApiResponse.success(new MetricRangeResponse(
                metric,
                from.toEpochMilli(),
                to.toEpochMilli(),
                safeStep,
                safeHours,
                seriesDtos));
    }

    /**
     * 总览：实时监控页首屏的几张卡片。
     *
     * <p>做成一个端点而非让前端并发发 5 个请求：首屏 5 个并发查询会让
     * Prometheus 在页面刷新时承受 5 倍峰值，而这些查询本来就该一起成功
     * 或一起失败（数据源挂了就全挂）。</p>
     *
     * <p><b>单条指标失败不影响其余</b>——某个 exporter 没起时，
     * 不该让整个总览页空白。失败项以 {@code error} 字段如实标注。</p>
     */
    @GetMapping("/overview")
    public ApiResponse<MetricOverviewResponse> overview() {
        List<String> ids = List.of("cpu.usage", "memory.usage", "disk.usage",
                "load.avg1", "target.up");

        Map<String, OverviewCardDto> cards = new LinkedHashMap<>();
        for (String id : ids) {
            MetricsCatalog.Metric meta = MetricsCatalog.get(id);
            try {
                List<PromQuery.Sample> samples = prometheus.query(meta.promql());
                List<MetricSampleDto> sampleDtos = samples.stream()
                        .map(s -> new MetricSampleDto(s.labels(), s.hasValue() ? s.value() : null, s.timestampMs()))
                        .toList();
                cards.put(id, new OverviewCardDto(meta.name(), meta.unit(), meta.describe(), true, null, sampleDtos));
            } catch (Exception e) {
                cards.put(id, new OverviewCardDto(meta.name(), meta.unit(), meta.describe(), false, e.getMessage(), List.of()));
                log.debug("[Metrics] 总览单项失败 | metric={} | {}", id, e.getMessage());
            }
        }

        return ApiResponse.success(new MetricOverviewResponse(cards, System.currentTimeMillis()));
    }

    /**
     * 数据源健康检查（接入管理页）。
     *
     * <p>不抛异常——「连不上」正是这个端点要报告的<b>结果</b>，
     * 把它变成 503 会让接入管理页自己也打不开，那就本末倒置了。</p>
     */
    @GetMapping("/datasource")
    public ApiResponse<MetricDatasourceResponse> datasource() {
        Map<String, Object> health = prometheus.health();

        String baseUrl = (String) health.get("baseUrl");
        boolean enabled = Boolean.TRUE.equals(health.get("enabled"));
        boolean reachable = Boolean.TRUE.equals(health.get("reachable"));
        Long latencyMs = health.get("latencyMs") instanceof Number n ? n.longValue() : null;
        String error = (String) health.get("error");

        MetricDatasourceDto dto = new MetricDatasourceDto(
                "prometheus",
                "Prometheus",
                baseUrl,
                enabled,
                reachable,
                latencyMs,
                error);

        return ApiResponse.success(new MetricDatasourceResponse(List.of(dto), 1));
    }
}
