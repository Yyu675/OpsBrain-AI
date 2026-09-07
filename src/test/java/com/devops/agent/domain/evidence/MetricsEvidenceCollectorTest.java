package com.devops.agent.domain.evidence;

import com.devops.agent.support.AbstractIntegrationTest;
import com.devops.agent.infrastructure.metrics.MetricsUnavailableException;
import com.devops.agent.infrastructure.metrics.PrometheusClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * S1-1 采集器四态测试：容器基座上跑（与 S0-3 的 CB 测试同谱系——
 * SpringBootTest + dev profile + MOCK 遮盖 + MockWebServer 演 Prometheus，
 * @DynamicPropertySource 把 base-url 指向本地 mock）。
 *
 * <p>四态判据：未启用=UNAVAILABLE（零网络调用）、故障=FAILED、
 * 空结果=NO_DATA（有效排除，不混同故障）、有数据=SUCCESS；
 * sourceRef 必须是<b>实渲染 PromQL</b>（验收「可粘贴复现」的测试化）。</p>
 */
@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "devops.ai.mode=MOCK",
        // 与 CB 专修测试隔离：本类不关熔断状态机的事，全部取默认
        "devops.metrics.prometheus.enabled=true",
        // 注意：目录绑定路径是 devops.metrics.query-catalog.templates.<metric>
        // ——裸根键（少了 templates. 层）会被静默忽略、目录整体为空（实测），
        // 本类直接用 yml 目录全集，不玩逐 key 覆盖。
})
@DisplayName("S1-1 指标取证采集器：四态与 sourceRef 可复现")
class MetricsEvidenceCollectorTest extends AbstractIntegrationTest {

    private static final MockWebServer SERVER;

    static {
        SERVER = new MockWebServer();
        try {
            SERVER.start();
        } catch (IOException e) {
            throw new UncheckedIOException("MockWebServer 启动失败", e);
        }
    }

    @DynamicPropertySource
    static void registerPrometheusBaseUrl(DynamicPropertyRegistry registry) {
        registry.add("devops.metrics.prometheus.base-url",
                () -> SERVER.url("/").toString().replaceAll("/$", ""));
    }

    @AfterAll
    static void stopServer() throws IOException {
        SERVER.shutdown();
    }

    @Autowired
    private MetricsEvidenceCollector collector;

    @Autowired
    private PrometheusClient prometheusClient;

    private static void enqueueMatrix(String seriesBody) {
        SERVER.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\",\"result\":["
                        + seriesBody + "]}}"));
    }

    private static String flatSeries(int n, double v) {
        StringBuilder sb = new StringBuilder("{\"metric\":{\"pod\":\"order-service-a1\"},\"values\":[");
        long base = 1_700_000_000L;
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(',');
            sb.append('[').append(base + i * 30).append(",\"").append(v).append("\"]");
        }
        return sb.append("]}").toString();
    }

    @Test
    @DisplayName("未启用 → UNAVAILABLE，且零网络调用（不伪装 SUCCESS）")
    void disabledYieldsUnavailableWithoutAnyRequest() {
        PrometheusClient disabled = new PrometheusClient("http://unreachable.invalid", 500, false, new ObjectMapper());
        MetricsEvidenceCollector c = new MetricsEvidenceCollector(disabled, new MetricsQueryCatalog());
        var e = c.collect("order-service", "30m", "cpu");
        assertThat(e.status()).as(() -> "evidence_title=" + e.title() + " || " + e.toToolPayload()).isEqualTo(Evidence.EvidenceStatus.UNAVAILABLE);
        assertThatThrownBy(() -> new PrometheusClient("http://x", 500, false, new ObjectMapper()).query("up"))
                .isInstanceOf(MetricsUnavailableException.class); // 佐证未启用是显式异常不是空结果
    }

    @Test
    @DisplayName("Prometheus 5xx → FAILED（证据缺口），绝不伪装指标正常")
    void prometheusDownYieldsFailed() {
        SERVER.enqueue(new MockResponse().setResponseCode(500));
        var e = collector.collect("order-service", "30m", "cpu");
        assertThat(e.status()).as(() -> "evidence_title=" + e.title() + " || " + e.toToolPayload()).isEqualTo(Evidence.EvidenceStatus.FAILED);
        assertThat(e.toToolPayload()).contains("\"status\":\"FAILED\"");
    }

    @Test
    @DisplayName("空结果 → NO_DATA（有效排除证据），sourceRef 为实渲染 PromQL")
    void emptyResultIsNoDataNotFailed() {
        enqueueMatrix("");
        var e = collector.collect("order-service", "30m", "cpu");
        assertThat(e.status()).as(() -> "evidence_title=" + e.title() + " || payload=" + e.toToolPayload()).isEqualTo(Evidence.EvidenceStatus.NO_DATA);
        assertThat(e.sourceRef())
                .as("sourceRef 是实渲染 PromQL，可直接粘贴 Grafana 复现")
                .contains("order-service").contains("container_cpu_usage_seconds_total")
                .doesNotContain("{service}");
    }

    @Test
    @DisplayName("平稳序列 → SUCCESS 且异常清单为空（无异常≠查询失败）")
    void normalSeriesYieldsSuccessNoAnomaly() {
        enqueueMatrix(flatSeries(30, 0.42));
        var e = collector.collect("order-service", "30m", "cpu");
        assertThat(e.status()).as(() -> "evidence_title=" + e.title() + " || " + e.toToolPayload()).isEqualTo(Evidence.EvidenceStatus.SUCCESS);
        assertThat(((Number) e.content().get("anomalyCount")).longValue()).isZero();
    }

    @Test
    @DisplayName("尖峰序列 → SUCCESS 且进 anomalies（判定走 IQR 而非 LLM 直觉）")
    void spikedSeriesYieldsSuccessWithAnomaly() {
        StringBuilder sb = new StringBuilder("{\"metric\":{\"pod\":\"order-service-a1\"},\"values\":[");
        long base = 1_700_000_000L;
        for (int i = 0; i < 29; i++) {
            sb.append('[').append(base + i * 30).append(",\"0.10\"],");
        }
        sb.append('[').append(base + 29L * 30).append(",\"5.00\"]}");
        enqueueMatrix(sb.toString());
        var e = collector.collect("order-service", "30m", "cpu");
        assertThat(e.status()).as(() -> "evidence_title=" + e.title() + " || " + e.toToolPayload()).isEqualTo(Evidence.EvidenceStatus.SUCCESS);
        assertThat(((Number) e.content().get("anomalyCount")).longValue()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("未知指标名 → FAILED 并给出合法清单，且零网络调用")
    void unknownMetricFailsFastWithCatalogHint() {
        var e = collector.collect("order-service", "30m", "disk-io");
        assertThat(e.status()).as(() -> "evidence_title=" + e.title() + " || " + e.toToolPayload()).isEqualTo(Evidence.EvidenceStatus.FAILED);
        assertThat(e.toToolPayload()).contains("cpu");
    }

    @Test
    @DisplayName("非法时间窗 → FAILED 快失败（不到 Prometheus）")
    void badRangeFailsFast() {
        var e = collector.collect("order-service", "3years", "cpu");
        assertThat(e.status()).as(() -> "evidence_title=" + e.title() + " || " + e.toToolPayload()).isEqualTo(Evidence.EvidenceStatus.FAILED);
    }

    @Test
    @DisplayName("工具载荷 JSON 可解析且键序稳定（LLM 消费面契约）")
    void payloadJsonParseableWithStableKeys() throws Exception {
        enqueueMatrix(flatSeries(30, 0.42));
        var e = collector.collect("order-service", "30m", "cpu");
        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = new ObjectMapper().readValue(e.toToolPayload(), Map.class);
        assertThat(parsed).containsKeys("status", "evidenceType", "title", "sourceRef", "collectedAt", "content");
        assertThat(parsed.get("status")).isEqualTo("SUCCESS");
        assertThat(parsed.get("evidenceType")).isEqualTo("metrics");
    }
}
