package com.devops.agent.controller;

import com.devops.agent.common.dto.ApiResponse;
import com.devops.agent.infrastructure.metrics.MetricsCatalog;
import com.devops.agent.infrastructure.metrics.PromQuery;
import com.devops.agent.infrastructure.metrics.PrometheusClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
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
    public ApiResponse<Map<String, Object>> catalog() {
        return ApiResponse.success(Map.of(
                "metrics", MetricsCatalog.describeAll(),
                "enabled", prometheus.isEnabled()));
    }

    /**
     * 瞬时查询。
     *
     * @param metric 指标 ID，见 {@code /catalog}
     */
    @GetMapping("/instant")
    public ApiResponse<Map<String, Object>> instant(@RequestParam String metric) {
        String promql = MetricsCatalog.promqlOf(metric);
        List<PromQuery.Sample> samples = prometheus.query(promql);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("metric", metric);
        data.put("samples", samples.stream().map(PromQuery::toView).toList());
        return ApiResponse.success(data);
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
    public ApiResponse<Map<String, Object>> range(
            @RequestParam String metric,
            @RequestParam(defaultValue = "1") int hours,
            @RequestParam(defaultValue = "60") int step) {

        // 参数钳制：负数或超大跨度都会打到 Prometheus 上。
        // 不静默纠正而是先夹紧再执行——返回错误会让「手滑填了 0」变成一次失败请求，
        // 而这里给出一个合理的最小窗口对用户更友好
        int safeHours = Math.max(1, Math.min(hours, (int) (MAX_RANGE_DAYS * 24)));
        int safeStep = Math.max(1, Math.min(step, 3600));

        Instant to = Instant.now();
        Instant from = to.minusSeconds((long) safeHours * 3600);

        String promql = MetricsCatalog.promqlOf(metric);
        List<PromQuery.Series> series = prometheus.queryRange(promql, from, to, safeStep);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("metric", metric);
        data.put("from", from.toEpochMilli());
        data.put("to", to.toEpochMilli());
        data.put("step", safeStep);
        // 如实回报生效值：用户传了 hours=999 却只看到 31 天数据时，
        // 得能从响应里看出被夹紧了，而不是以为数据缺失
        data.put("hours", safeHours);
        data.put("series", series.stream().map(PromQuery::toView).toList());
        return ApiResponse.success(data);
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
    public ApiResponse<Map<String, Object>> overview() {
        List<String> ids = List.of("cpu.usage", "memory.usage", "disk.usage",
                "load.avg1", "target.up");

        Map<String, Object> cards = new LinkedHashMap<>();
        for (String id : ids) {
            Map<String, Object> card = new LinkedHashMap<>();
            MetricsCatalog.Metric meta = MetricsCatalog.get(id);
            card.put("name", meta.name());
            card.put("unit", meta.unit());
            card.put("describe", meta.describe());
            try {
                List<PromQuery.Sample> samples = prometheus.query(meta.promql());
                card.put("samples", samples.stream().map(PromQuery::toView).toList());
                card.put("ok", true);
            } catch (Exception e) {
                // 逐条兜底：一个 exporter 挂了不该让整页空白
                card.put("ok", false);
                card.put("error", e.getMessage());
                card.put("samples", List.of());
                log.debug("[Metrics] 总览单项失败 | metric={} | {}", id, e.getMessage());
            }
            cards.put(id, card);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("cards", cards);
        data.put("timestamp", System.currentTimeMillis());
        return ApiResponse.success(data);
    }

    /**
     * 数据源健康检查（接入管理页）。
     *
     * <p>不抛异常——「连不上」正是这个端点要报告的<b>结果</b>，
     * 把它变成 503 会让接入管理页自己也打不开，那就本末倒置了。</p>
     */
    @GetMapping("/datasource")
    public ApiResponse<Map<String, Object>> datasource() {
        Map<String, Object> health = prometheus.health();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", "prometheus");
        data.put("name", "Prometheus");
        data.putAll(health);
        // 数据源接入是「有几个」的概念，用列表包一层，
        // 将来接 K8s / 云平台时结构不用变
        return ApiResponse.success(Map.of(
                "datasources", List.of(data),
                "total", 1));
    }
}
