package com.devops.agent.infrastructure.logs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loki 数据源实现（S1-3，路线图 §5.4 1-3.1 「配置化实现」之首落地）。
 * <p>
 * 选 Loki 作首实现：LogQL 与 PromQL 语法同构（团队零学习成本）、
 * HTTP API 稳定（{@code /loki/api/v1/query_range}）、自建/SaaS 覆盖最广。
 * 服务名 → LogQL 流选择器模式与 metrics 目录同套路：
 * {@code {app=~"{service}.*"}}，label 键名可配（现场常为 app/job/container）。
 * </p>
 * <p>
 * 熔断挂载 {@code logs} 实例（S0-3 挂账槽，本轮接线）：
 * 打开时真错误透传、CallNotPermittedException 译 {@link LogsUnavailableException}
 * （译码语义与 prometheus 客户端成对）。
 * </p>
 */
@Component
public class LokiLogQueryClient implements LogQueryClient {

    private static final Logger log = LoggerFactory.getLogger(LokiLogQueryClient.class);

    private final String baseUrl;
    private final Duration timeout;
    private final boolean enabled;
    /** LogQL 流选择器的 label 键（现场 label 约定不同时由配置改写）。 */
    private final String serviceLabel;
    private final HttpClient http;
    private final ObjectMapper mapper;

    public LokiLogQueryClient(
            @Value("${devops.logs.loki.base-url:http://localhost:23100}") String baseUrl,
            @Value("${devops.logs.loki.timeout-ms:5000}") long timeoutMs,
            @Value("${devops.logs.loki.enabled:false}") boolean enabled,
            @Value("${devops.logs.loki.service-label:app}") String serviceLabel,
            ObjectMapper mapper) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.timeout = Duration.ofMillis(timeoutMs);
        this.enabled = enabled;
        this.serviceLabel = serviceLabel;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        if (enabled) {
            log.info("[Loki] 日志客户端初始化 | baseUrl={} | timeout={}ms | label={}",
                    this.baseUrl, timeoutMs, this.serviceLabel);
        }
    }

    @Override
    public boolean isEnabled() { return enabled; }

    /** 构造 LogQL（sourceRef 可溯源全靠它原样出门）。 */
    public String buildLogQL(LogQuery q) {
        StringBuilder sb = new StringBuilder();
        sb.append('{').append(serviceLabel).append("=~\"").append(q.service()).append(".*\"}");
        if (q.level() != null && !q.level().isBlank() && !"ALL".equalsIgnoreCase(q.level())) {
            sb.append(" |~ \"(?i)").append(q.level()).append("\"");
        }
        if (q.keyword() != null && !q.keyword().isBlank()) {
            sb.append(" |= \"").append(escapeLogQL(q.keyword())).append("\"");
        }
        return sb.toString();
    }

    @CircuitBreaker(name = "logs", fallbackMethod = "queryFallback")
    @Override
    public List<LogEntry> query(LogQuery q) {
        requireEnabled();
        String logql = buildLogQL(q);
        String url = baseUrl + "/loki/api/v1/query_range"
                + "?query=" + URLEncoder.encode(logql, StandardCharsets.UTF_8)
                + "&start=" + q.from().toEpochMilli() * 1_000_000L
                + "&end=" + q.to().toEpochMilli() * 1_000_000L
                + "&limit=" + q.maxEntries()
                + "&direction=forward";
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(timeout)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                throw new LogsUnavailableException(
                        "Loki 响应 " + resp.statusCode() + " | LogQL: " + logql);
            }
            return parseStreams(mapper.readTree(resp.body()));
        } catch (LogsUnavailableException e) {
            throw e;
        } catch (IOException e) {
            throw new LogsUnavailableException("Loki 响应解析失败 | LogQL: " + logql + " | " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LogsUnavailableException("Loki 查询被中断 | LogQL: " + logql, e);
        } catch (Exception e) {
            throw new LogsUnavailableException("Loki 查询失败 | LogQL: " + logql + " | " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unused") // 由 resilience4j AOP 在熔断/异常路径调用
    private List<LogEntry> queryFallback(LogQuery q, Throwable t) {
        if (t instanceof LogsUnavailableException lue) throw lue;
        if (t instanceof io.github.resilience4j.circuitbreaker.CallNotPermittedException) {
            throw new LogsUnavailableException("Loki 熔断器打开中（logs 实例），稍候自动半开探测");
        }
        throw new LogsUnavailableException("Loki 查询失败（熔断包装）: " + t.getMessage(), t);
    }

    private void requireEnabled() {
        // 禁用态不允许被熔断器计入失败：抛"配置态"异常并列入 ignore-exceptions（yml 声明）
        if (!enabled) {
            throw new LogsIntegrationDisabledException(
                    "Loki 日志集成未启用（devops.logs.loki.enabled=false）");
        }
    }

    /** 兼容 streams/matrix 两种 resultType；按时间毫秒排序输出。 */
    private List<LogEntry> parseStreams(JsonNode root) {
        JsonNode data = root.path("data");
        JsonNode result = data.path("result");
        List<LogEntry> out = new ArrayList<>();
        String resultType = data.path("resultType").asText("streams");
        for (JsonNode stream : result) {
            Map<String, String> labels = new LinkedHashMap<>();
            JsonNode streamLabels = stream.has("stream") ? stream.path("stream") : stream.path("metric");
            streamLabels.fields().forEachRemaining(e -> labels.put(e.getKey(), e.getValue().asText()));
            JsonNode values = stream.has("values") ? stream.path("values")
                    : stream.path("values"); // streams 与 matrix 同名容积
            for (JsonNode pair : values) {
                if (!pair.isArray() || pair.size() < 2) continue;
                long tsNanos = "streams".equals(resultType)
                        ? Long.parseLong(pair.get(0).asText())
                        : (long) (pair.get(0).asDouble() * 1_000_000_000L);
                String line = pair.get(1).asText("");
                out.add(new LogEntry(Instant.ofEpochMilli(tsNanos / 1_000_000),
                        labels.getOrDefault("level", guessLevel(line)), line, labels));
            }
        }
        out.sort(Comparator.comparing(LogEntry::timestamp));
        log.debug("[Loki] 解析 {} 条日志行", out.size());
        return out;
    }

    /** 级别粗判：行首 60 字符找常见级别词；找不到记 INFO。仅用于客户端未带 level label 的情形。 */
    static String guessLevel(String line) {
        String head = line.length() > 60 ? line.substring(0, 60) : line;
        String up = head.toUpperCase();
        if (up.contains("ERROR") || up.contains("SEVERE") || up.contains("FATAL")) return "ERROR";
        if (up.contains("WARN")) return "WARN";
        if (up.contains("DEBUG") || up.contains("TRACE")) return "DEBUG";
        return "INFO";
    }

    private static String escapeLogQL(String kw) {
        return kw.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
