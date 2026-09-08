package com.devops.agent.infrastructure.metrics;

import com.devops.agent.support.AbstractIntegrationTest;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
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
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Prometheus 数据源熔断行为集成测试（S0-3，路线图 §4.3）。
 *
 * <h3>为什么需要 MockWebServer 计数</h3>
 * 熔断的核心承诺是「打开后请求不再打网络」——只断言「返回了兜底结果」
 * 无法区分两种实现：真熔断（不打网络）与假熔断（每次都打了再吞掉）。
 * 后者等于没有熔断。MockWebServer 的请求计数器让这句话可被验证：
 * 打开前后各调 N 次，真正走到服务器的一共应该只有打开前那几次。
 *
 * <h3>与窗口参数的刻意取舍</h3>
 * sliding-window-size/minimum-number-of-calls 用测试值（6/3）缩短触发链，
 * 但 <b>failure-rate-threshold 不覆盖</b>——就用 application.yml 的生产值（50），
 * 这样本测试同时是「阈值红线」的载体：J2 探针把阈值注入成 1% 时，
 * 「低于阈值保持闭合」用例必须变红。
 *
 * @author OpsBrain AI
 * @since 2026-09-07（S0-3）
 */
@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "devops.ai.mode=MOCK",
        "resilience4j.circuitbreaker.instances.prometheus.sliding-window-size=6",
        "resilience4j.circuitbreaker.instances.prometheus.minimum-number-of-calls=3",
        // 探测期不自动半开：恢复用例自行驱动状态机，消除时间因素（CI 不该拼时序运气）
        "resilience4j.circuitbreaker.instances.prometheus.wait-duration-in-open-state=300s",
        "resilience4j.circuitbreaker.instances.prometheus.permitted-number-of-calls-in-half-open-state=2",
        "resilience4j.circuitbreaker.instances.prometheus.automatic-transition-from-open-to-half-open-enabled=false"
})
@DisplayName("Prometheus 熔断器：失败开窗→静默→半开恢复全链路")
class PrometheusCircuitBreakerIntegrationTest extends AbstractIntegrationTest {

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
    private PrometheusClient prometheus;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    private CircuitBreaker breaker;

    @BeforeEach
    void resetBreaker() {
        breaker = circuitBreakerRegistry.circuitBreaker("prometheus");
        breaker.reset(); // 每个用例自带独立开场白
    }

    /** instant query 的成功响应骨架：空结果（业务上的「无匹配」，不是错误）。 */
    private static MockResponse okEmptyVector() {
        return new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":[]}}");
    }

    @Test
    @DisplayName("连续失败达阈值后熔断打开，后续请求抛出显式不可用且请求计数不再增长")
    void failuresOpenCircuitAndLaterCallsSkipNetwork() {
        int before = SERVER.getRequestCount();
        for (int i = 0; i < 3; i++) {
            SERVER.enqueue(new MockResponse().setResponseCode(500));
        }

        // 前 3 次：真打到网络、真失败（消息里不是「熔断器」而是真实错误）
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> prometheus.query("up"))
                    .isInstanceOf(MetricsUnavailableException.class)
                    .hasMessageNotContaining("熔断器");
        }
        assertThat(breaker.getState())
                .as("3/3 失败率 100% ≥ 阈值 50%，熔断器应已打开")
                .isEqualTo(CircuitBreaker.State.OPEN);

        // 再打 3 次：熔断器应把请求挡在网络之外，并以显式熔断语义失败
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> prometheus.query("up"))
                    .isInstanceOf(MetricsUnavailableException.class)
                    .hasMessageContaining("熔断器处于打开状态");
        }
        int networkHits = SERVER.getRequestCount() - before;
        assertThat(networkHits)
                .as("熔断打开后的请求不得再打网络：总命中应恰好是打开前的 3 次，而不是 6 次")
                .isEqualTo(3);
    }

    @Test
    @DisplayName("失败率低于阈值时保持闭合并持续打网络（J2 阈值探针的红线载体）")
    void belowFailureThresholdStaysClosedAndKeepsCalling() {
        int before = SERVER.getRequestCount();
        // 窗口 6：先 4 次成功垫窗，再 1 败 1 胜 → 失败率 1/6≈17%，远低于生产阈值 50%
        for (int i = 0; i < 4; i++) {
            SERVER.enqueue(okEmptyVector());
        }
        SERVER.enqueue(new MockResponse().setResponseCode(500));
        SERVER.enqueue(okEmptyVector());

        int failures = 0;
        for (int i = 0; i < 6; i++) {
            try {
                prometheus.query("up");
            } catch (MetricsUnavailableException expectedForTheSeeded500) {
                failures++;
            }
        }

        assertThat(failures).as("只有人为播种的那一次 500 应失败").isEqualTo(1);
        assertThat(breaker.getState())
                .as("失败率 17% < 阈值 50% 时必须保持闭合；"
                        + "若此处为 OPEN，说明阈值被改小（或统计窗口失效）——"
                        + "这正是 J2 探针要验证的红线（阈值 1% 时本断言必须失败）")
                .isEqualTo(CircuitBreaker.State.CLOSED);
        int networkHits = SERVER.getRequestCount() - before;
        assertThat(networkHits)
                .as("闭合状态下 6 次调用都应真实打到网络；熔断被误触发时这里会 < 6")
                .isEqualTo(6);
    }

    @Test
    @DisplayName("熔断打开时 health() 返回显式不可用 Map 且不发健康探测请求")
    void healthFallbackIsExplicitAndSilentWhenCircuitOpen() {
        for (int i = 0; i < 3; i++) {
            SERVER.enqueue(new MockResponse().setResponseCode(500));
        }
        for (int i = 0; i < 3; i++) {
            try {
                prometheus.query("up");
            } catch (MetricsUnavailableException ignored) {
                // 只为把熔断器打到 OPEN
            }
        }
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        int before = SERVER.getRequestCount();
        Map<String, Object> health = prometheus.health();

        assertThat(health.get("reachable")).isEqualTo(false);
        assertThat(String.valueOf(health.get("error")))
                .as("健康 fallback 必须显式标注「熔断器打开」，而不是让页面显示别的错误")
                .contains("熔断器打开");
        assertThat(SERVER.getRequestCount() - before)
                .as("熔断中做健康探测不得发网络请求（否则探测本身也在消耗故障中的数据源）")
                .isEqualTo(0);
    }

    @Test
    @DisplayName("半开探测成功后熔断自动闭合，查询恢复打网络")
    void halfOpenSuccessClosesCircuit() {
        for (int i = 0; i < 3; i++) {
            SERVER.enqueue(new MockResponse().setResponseCode(500));
        }
        for (int i = 0; i < 3; i++) {
            try {
                prometheus.query("up");
            } catch (MetricsUnavailableException ignored) {
                // 打进 OPEN
            }
        }
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // 手动驱动到半开（等效于 wait-duration 到期的自动转半开，去掉时间不确定性）
        breaker.transitionToHalfOpenState();
        int before = SERVER.getRequestCount();
        SERVER.enqueue(okEmptyVector());
        SERVER.enqueue(okEmptyVector());

        prometheus.query("up");
        prometheus.query("up");

        assertThat(breaker.getState())
                .as("半开窗内 2 次探测成功，熔断器应自动闭合")
                .isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(SERVER.getRequestCount() - before)
                .as("闭合后的查询必须恢复打网络")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("queryRange 经同一熔断器守护，fallback 同为显式不可用（实例级共享窗口）")
    void queryRangeSharesTheSameCircuitBreaker() {
        for (int i = 0; i < 3; i++) {
            SERVER.enqueue(new MockResponse().setResponseCode(500));
        }
        Instant to = Instant.now();
        Instant from = to.minusSeconds(3600);
        for (int i = 0; i < 3; i++) {
            try {
                prometheus.queryRange("up", from, to, 60);
            } catch (MetricsUnavailableException ignored) {
                // 打进 OPEN
            }
        }

        int before = SERVER.getRequestCount();
        assertThatThrownBy(() -> prometheus.query("up"))
                .as("queryRange 打开的熔断器必须同时挡住 query——共享 prometheus 实例，"
                        + "否则指标页一半面板熔断、另一半仍在消耗故障数据源")
                .isInstanceOf(MetricsUnavailableException.class)
                .hasMessageContaining("熔断器处于打开状态");
        assertThat(SERVER.getRequestCount() - before).isEqualTo(0);
    }
}
