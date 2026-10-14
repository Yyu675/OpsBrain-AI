package com.devops.agent.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 指标抓取面的<b>真实 HTTP</b> 行为（2026-10-15 监控接线修复的验收）。
 *
 * <h3>为什么必须是真实 HTTP 而不是单测</h3>
 * 这条链上有三处"各自都对、拼起来是 401"的接缝：
 * <ol>
 *   <li>Sa-Token 拦截器对 {@code /actuator/**} 的注册（{@code WebConfig}）；</li>
 *   <li>抓取令牌守卫的放行判定（{@code ScrapeTokenGuard}）；</li>
 *   <li>Spring Security 之外的 request 匹配（servletPath vs requestURI、context-path {@code /ai}）。</li>
 * </ol>
 * 单元测试只能证明 (2)；只有把请求真的发进容器，才能证明三者接起来是通的。
 * 而这件事的失败形态是全静默的：抓不到指标 → 面板空 + {@code up{job="opsbrain"}==0}
 * → 宕机规则常亮误报（历史上它就是这么被误报了很多次）。
 *
 * <h3>口径</h3>
 * 用 JDK 内置 {@code HttpClient}（与 {@code ChatStreamSseIntegrationTest} 同款做法：
 * 不引入 spring-webflux，也不受 {@code MockMvc} 忽略 context-path 的影响）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        // 令牌走内联配置（文件模式的行为由 ScrapeTokenGuardTest 覆盖）
        "devops.security.scrape-token=" + ActuatorScrapeTokenHttpTest.TOKEN,
        "devops.security.scrape-token-file=",
        "devops.security.rate-limit.enabled=false"
})
@DisplayName("指标抓取面令牌：未带令牌 401 / 带令牌 200 / 越权路径仍 401")
class ActuatorScrapeTokenHttpTest {

    static final String TOKEN = "ci-scrape-token-2f8a1c9d4e6b";

    @LocalServerPort
    private int port;

    /** 与生产一致的 context-path（{@code /ai}）——不带它请求会 404，正是"看着像没配好"的经典假象。 */
    @Value("${server.servlet.context-path:}")
    private String contextPath;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private HttpResponse<String> get(String path, String... headers) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + contextPath + path))
                .timeout(Duration.ofSeconds(15))
                .GET();
        for (int i = 0; i + 1 < headers.length; i += 2) {
            builder.header(headers[i], headers[i + 1]);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("不带令牌抓取 → 401（与修复前一致：抓取方必须证明身份）")
    void withoutTokenIsRejected() throws Exception {
        HttpResponse<String> resp = get("/actuator/prometheus");
        assertThat(resp.statusCode())
                .as("无凭据抓取必须被拒——否则内部拓扑与依赖可达性裸奔")
                .isEqualTo(401);
    }

    @Test
    @DisplayName("配置的令牌 → 200 且返回 opsbrain_* 业务指标（Prometheus 真能抓到东西）")
    void withTokenGetsMetrics() throws Exception {
        HttpResponse<String> resp = get("/actuator/prometheus", "Authorization", "Bearer " + TOKEN);
        assertThat(resp.statusCode())
                .as("带正确令牌必须放行，否则 up{job=opsbrain} 恒 0 → 宕机规则常亮误报")
                .isEqualTo(200);
        assertThat(resp.body())
                .as("只放行不供数也等于没接：必须真的能看到业务指标")
                .contains("opsbrain_")
                .contains("jvm_memory_used_bytes");
    }

    @Test
    @DisplayName("令牌错误 → 401（不会因为'配了令牌'就放宽）")
    void wrongTokenRejected() throws Exception {
        HttpResponse<String> resp = get("/actuator/prometheus", "Authorization", "Bearer wrong-token");
        assertThat(resp.statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("最小权限：同一令牌对其它 actuator 端点无效（仍 401）")
    void tokenIsScopedToScrapePath() throws Exception {
        assertThat(get("/actuator/info", "Authorization", "Bearer " + TOKEN).statusCode())
                .as("/actuator/info 含构建与依赖信息，不该被抓取令牌打开")
                .isEqualTo(401);
    }

    @Test
    @DisplayName("令牌不放 URL 查询串（防访问日志/历史泄漏）")
    void tokenInQueryRejected() throws Exception {
        assertThat(get("/actuator/prometheus?token=" + TOKEN).statusCode())
                .as("URL 里的令牌不进任何日志就不会泄漏——所以不支持这种传法")
                .isEqualTo(401);
    }

    @Test
    @DisplayName("健康探针不受影响：无令牌仍 200（K8s 探针口径不能被本次修复破坏）")
    void healthStaysOpen() throws Exception {
        HttpResponse<String> resp = get("/actuator/health");
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.body()).contains("UP");
    }
}
