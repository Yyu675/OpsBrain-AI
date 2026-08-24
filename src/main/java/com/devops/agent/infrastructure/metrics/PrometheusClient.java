package com.devops.agent.infrastructure.metrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Prometheus HTTP API 客户端（L2 · B1）。
 *
 * <h3>关键取舍：代理查询，不自建时序存储</h3>
 * 蓝图要求「实时监控 / 趋势分析」，直觉做法是把指标抓下来存进自己的库。
 * <b>本项目刻意不这么做</b>，理由：
 * <ul>
 *   <li>自建 TSDB（降采样、保留策略、乱序写入、高基数标签）是数月工程量，
 *       而它解决的问题 Prometheus 已经解决得很好；</li>
 *   <li>运维场景下 Prometheus 已是事实标准，<b>用户本来就有</b>，
 *       再存一份意味着两套数据可能对不上——届时「以哪个为准」无解；</li>
 *   <li>存储成本翻倍，且我们的副本必然滞后于源。</li>
 * </ul>
 * 代价是「Prometheus 挂了本页就没数据」，但这是<b>正确的失败模式</b>：
 * 监控数据本就该以监控系统为准，而不是让用户看着一份陈旧的副本
 * 以为系统正常。
 *
 * <h3>为什么用 JDK HttpClient 而不是 RestTemplate/WebClient</h3>
 * 项目已排除了 langchain4j 的 JDK HTTP 客户端依赖，且没有引入 WebFlux。
 * 为了一个只发 GET 的客户端再拉一套 HTTP 栈不划算；
 * JDK 内置的 {@link HttpClient} 足够，且自带超时与连接池。
 *
 * <h3>超时必须设，且要短</h3>
 * 这是被前端页面同步等待的调用。Prometheus 上一个写错的 PromQL
 * （比如没加时间窗的高基数聚合）可能跑几十秒，
 * 不设超时会把 Web 线程全部占满，拖垮整个后端——
 * 监控页把主站拖挂是运维系统最难堪的失败。
 *
 * @author OpsBrain AI
 * @since 2026-08-25
 */
@Slf4j
@Component
public class PrometheusClient {

    /** Prometheus 基址。默认对齐 docker-compose.dev.yml 的宿主机映射端口 */
    private final String baseUrl;

    /** 单次查询超时。默认 5 秒——页面同步等待，超过这个时间用户已经在刷新了 */
    private final Duration timeout;

    private final boolean enabled;

    private final HttpClient http;
    private final ObjectMapper mapper;

    public PrometheusClient(
            @Value("${devops.metrics.prometheus.base-url:http://localhost:29090}") String baseUrl,
            @Value("${devops.metrics.prometheus.timeout-ms:5000}") long timeoutMs,
            @Value("${devops.metrics.prometheus.enabled:true}") boolean enabled,
            ObjectMapper mapper) {
        // 去掉尾斜杠，避免拼出 //api/v1/query 这种双斜杠路径
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.timeout = Duration.ofMillis(timeoutMs);
        this.enabled = enabled;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder()
                // 连接超时与读超时分开：连不上要快速失败（Prometheus 没起），
                // 而不是等满整个查询超时
                .connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        log.info("[Prometheus] 客户端初始化 | baseUrl={} | timeout={}ms | enabled={}",
                this.baseUrl, timeoutMs, enabled);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String baseUrl() {
        return baseUrl;
    }

    // ==================================================================
    // 查询
    // ==================================================================

    /**
     * 瞬时查询（instant query）。
     *
     * @param promql PromQL 表达式
     * @return 结果样本；查询无匹配时返回空列表（<b>不是异常</b>——
     *         「当前没有满足条件的实例」是正常业务结果）
     * @throws MetricsUnavailableException Prometheus 不可达、超时或返回错误
     */
    public List<PromQuery.Sample> query(String promql) {
        requireEnabled();
        String url = baseUrl + "/api/v1/query?query=" + encode(promql);
        JsonNode data = getData(url, promql);
        return parseInstant(data);
    }

    /**
     * 区间查询（range query）。
     *
     * @param promql PromQL 表达式
     * @param from   起始时刻
     * @param to     结束时刻
     * @param stepSeconds 采样步长（秒）
     */
    public List<PromQuery.Series> queryRange(String promql, Instant from, Instant to,
                                             int stepSeconds) {
        requireEnabled();

        // 步长下限保护：step=0 会让 Prometheus 直接报错；
        // 过小的 step 在长时间窗上会产出几十万个点，把响应体撑爆、前端也画不动。
        // 这里按「最多约 1500 个点」反推最小步长——1500 点已远超屏幕像素密度，
        // 再密对用户没有任何信息增益。
        long spanSeconds = Math.max(1, to.getEpochSecond() - from.getEpochSecond());
        int minStep = (int) Math.max(1, spanSeconds / 1500);
        int step = Math.max(stepSeconds, minStep);
        if (step != stepSeconds) {
            log.debug("[Prometheus] 步长已上调 | 请求={}s -> 实际={}s | 时间跨度={}s",
                    stepSeconds, step, spanSeconds);
        }

        String url = baseUrl + "/api/v1/query_range"
                + "?query=" + encode(promql)
                + "&start=" + from.getEpochSecond()
                + "&end=" + to.getEpochSecond()
                + "&step=" + step;

        JsonNode data = getData(url, promql);
        return parseRange(data);
    }

    /**
     * 连通性与版本探测，供「接入管理」页做健康检查。
     *
     * <p>用 {@code /-/healthy}（Prometheus 的存活探针）而非发一条真实查询：
     * 后者会因为 PromQL 写错而失败，把「连不上」与「查询有问题」混为一谈，
     * 而这两件事的处置动作完全不同。</p>
     *
     * @return {@code {reachable, latencyMs, baseUrl, error?}}
     */
    public Map<String, Object> health() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("baseUrl", baseUrl);
        result.put("enabled", enabled);

        if (!enabled) {
            result.put("reachable", false);
            result.put("error", "Prometheus 集成未启用（devops.metrics.prometheus.enabled=false）");
            return result;
        }

        long start = System.currentTimeMillis();
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/-/healthy"))
                    .timeout(timeout)
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            long cost = System.currentTimeMillis() - start;

            boolean ok = resp.statusCode() >= 200 && resp.statusCode() < 300;
            result.put("reachable", ok);
            result.put("latencyMs", cost);
            if (!ok) {
                result.put("error", "HTTP " + resp.statusCode());
            }
        } catch (Exception e) {
            result.put("reachable", false);
            result.put("latencyMs", System.currentTimeMillis() - start);
            // 只给类名 + 消息，不带堆栈：这个结果要显示在页面上
            result.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
            log.warn("[Prometheus] 健康检查失败 | baseUrl={} | {}", baseUrl, e.toString());
        }
        return result;
    }

    // ==================================================================
    // 内部
    // ==================================================================

    private void requireEnabled() {
        if (!enabled) {
            throw new MetricsUnavailableException(
                    "Prometheus 集成未启用。请配置 devops.metrics.prometheus.enabled=true 与 base-url");
        }
    }

    /**
     * 发请求并取出 {@code data} 节点。
     *
     * <p>Prometheus 的错误有两种，必须分别处理：
     * <ol>
     *   <li><b>HTTP 4xx/5xx</b> —— 通常是 PromQL 语法错误（400）或服务异常；</li>
     *   <li><b>HTTP 200 但 {@code status != "success"}</b> —— Prometheus
     *       部分错误走这条路径。只看状态码会把它当成功，
     *       然后在解析 data 时拿到 null 空指针，报错位置离真实原因十万八千里。</li>
     * </ol>
     */
    private JsonNode getData(String url, String promql) {
        long start = System.currentTimeMillis();
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(timeout)
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            long cost = System.currentTimeMillis() - start;

            JsonNode root;
            try {
                root = mapper.readTree(resp.body());
            } catch (Exception parseError) {
                throw new MetricsUnavailableException(
                        "Prometheus 返回了非 JSON 响应（HTTP " + resp.statusCode()
                                + "），可能 base-url 指向了错误的服务", parseError);
            }

            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                // Prometheus 的错误体里 error 字段是人类可读的，直接透出比状态码有用得多
                String detail = root.path("error").asText("");
                throw new MetricsUnavailableException(
                        "Prometheus 查询失败（HTTP " + resp.statusCode() + "）："
                                + (detail.isBlank() ? resp.body() : detail));
            }

            String status = root.path("status").asText("");
            if (!"success".equals(status)) {
                throw new MetricsUnavailableException(
                        "Prometheus 查询失败：" + root.path("error").asText("未知错误"));
            }

            if (cost > 1000) {
                log.warn("[Prometheus] 慢查询 | {}ms | promql={}", cost, promql);
            }
            return root.path("data");

        } catch (MetricsUnavailableException e) {
            throw e;
        } catch (java.net.http.HttpTimeoutException e) {
            throw new MetricsUnavailableException(
                    "Prometheus 查询超时（" + timeout.toMillis() + "ms）。"
                            + "可能是查询过重或服务负载高", e);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                // 恢复中断标志：吞掉它会让上层的取消逻辑失效
                Thread.currentThread().interrupt();
            }
            throw new MetricsUnavailableException(
                    "无法连接 Prometheus（" + baseUrl + "）：" + e.getMessage(), e);
        }
    }

    /** 解析 instant query 的 {@code result} 数组 */
    private List<PromQuery.Sample> parseInstant(JsonNode data) {
        List<PromQuery.Sample> out = new ArrayList<>();
        for (JsonNode item : data.path("result")) {
            Map<String, String> labels = readLabels(item.path("metric"));
            JsonNode value = item.path("value");   // [ts, "value"]
            if (!value.isArray() || value.size() < 2) {
                continue;
            }
            out.add(new PromQuery.Sample(
                    labels,
                    parseDouble(value.get(1).asText()),
                    (long) (value.get(0).asDouble() * 1000)));
        }
        return out;
    }

    /** 解析 range query 的 {@code result} 数组 */
    private List<PromQuery.Series> parseRange(JsonNode data) {
        List<PromQuery.Series> out = new ArrayList<>();
        for (JsonNode item : data.path("result")) {
            Map<String, String> labels = readLabels(item.path("metric"));
            List<PromQuery.Point> points = new ArrayList<>();
            for (JsonNode v : item.path("values")) {   // [[ts, "value"], ...]
                if (!v.isArray() || v.size() < 2) {
                    continue;
                }
                points.add(new PromQuery.Point(
                        (long) (v.get(0).asDouble() * 1000),
                        parseDouble(v.get(1).asText())));
            }
            out.add(new PromQuery.Series(labels, points));
        }
        return out;
    }

    private Map<String, String> readLabels(JsonNode metric) {
        Map<String, String> labels = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> it = metric.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> f = it.next();
            labels.put(f.getKey(), f.getValue().asText());
        }
        return labels;
    }

    /**
     * Prometheus 的值是字符串，且可能是 {@code "NaN"} / {@code "+Inf"} / {@code "-Inf"}。
     *
     * <p>统一转成 {@code Double.NaN} 而不是抛异常：这些值表示
     * 「此刻没有有效数据」（如刚重启的实例、除零的 rate），是正常的业务状态，
     * 不该让整个查询失败。调用方用 {@code Sample.hasValue()} 判定。</p>
     */
    static double parseDouble(String raw) {
        if (raw == null || raw.isBlank()) {
            return Double.NaN;
        }
        String v = raw.trim();
        if ("NaN".equalsIgnoreCase(v) || "+Inf".equalsIgnoreCase(v) || "-Inf".equalsIgnoreCase(v)
                || "Inf".equalsIgnoreCase(v)) {
            return Double.NaN;
        }
        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
