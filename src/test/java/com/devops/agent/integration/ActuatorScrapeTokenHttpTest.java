package com.devops.agent.integration;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code /actuator/**} 访问闸门的<b>真实 HTTP</b> 验收（2026-10-15）。
 *
 * <h3>为什么必须是真实 HTTP</h3>
 * 这条链上有三处"各自都对、拼起来是坏"的接缝，单测一个都覆盖不到：
 * <ol>
 *   <li>闸门装在哪里（Filter vs MVC 拦截器）——<b>本轮就是栽在这里</b>：MVC 拦截器对
 *       actuator 端点整条链都不生效，只有真发请求才看得见；</li>
 *   <li>路径匹配（context-path {@code /ai} 与 servletPath / requestURI 的差别）；</li>
 *   <li>端点是否真的存在（缺 micrometer 时是 404 而不是 401——两种"不通"混在一起，
 *       曾让一个 401 假象被当成"鉴权已生效"）。</li>
 * </ol>
 * 两个方向的失败都是静默的：抓不到指标 → {@code up{job="opsbrain"}} 恒 0 → 宕机规则
 * 常亮误报；端点匿名可读 → 内部拓扑与依赖地址裸奔。两面都要钉住。
 *
 * <h3>失败要能自解释</h3>
 * 断言消息里带 {@link #diagnostics()}：端点路径清单、注册器、暴露配置。
 * 这是上一轮 CI 红测的教训——只回一句 "expected 200 but was 404" 时，无法区分
 * "闸门装错位置" 与 "端点压根没装配"，白白多花一轮往返。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        // 令牌走内联配置（文件模式的行为由 ScrapeTokenGuardTest 覆盖）
        "devops.security.scrape-token=" + ActuatorScrapeTokenHttpTest.TOKEN,
        "devops.security.scrape-token-file=",
        "devops.security.rate-limit.enabled=false"
})
@DisplayName("actuator 闸门：未带凭据 401 / 抓取令牌 200 / 越权路径仍 401")
class ActuatorScrapeTokenHttpTest {

    static final String TOKEN = "ci-scrape-token-2f8a1c9d4e6b";

    @LocalServerPort
    private int port;

    /** 与生产一致的 context-path（{@code /ai}）——不带它请求会 404，正是"看着像没配好"的经典假象。 */
    @Value("${server.servlet.context-path:}")
    private String contextPath;

    @Autowired
    private ApplicationContext context;

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

    /**
     * 现场快照——全部走公开 API。
     * <p>下面四行是一条判定链，任何一环断掉都能一眼看出是哪一环：
     * <ol>
     *   <li>{@code PrometheusMeterRegistry} 类不在类路径 → 依赖缺件；</li>
     *   <li>类在但没有注册器 Bean → 自动配置没生效（Clock/开关/排除）；</li>
     *   <li>有注册器但没有端点 Bean → {@code @ConditionalOnAvailableEndpoint} 没过（enabled/exposure）；</li>
     *   <li>有端点 Bean 但路径没进 handler mapping → 映射或顺序问题。</li>
     * </ol>
     */
    private String diagnostics() {
        StringBuilder sb = new StringBuilder("\n【现场】");
        sb.append("\n- exposure.include=")
                .append(context.getEnvironment().getProperty("management.endpoints.web.exposure.include"));
        Map<String, MeterRegistry> registries = context.getBeansOfType(MeterRegistry.class);
        sb.append("\n- MeterRegistry Bean=").append(registries.isEmpty() ? "（无 —— 计量器未装配）" : registries.values()
                .stream().map(r -> r.getClass().getSimpleName()).distinct().toList());
        sb.append("\n- micrometer-registry-prometheus 类在类路径=")
                .append(isClassPresent("io.micrometer.prometheusmetrics.PrometheusMeterRegistry"));
        sb.append("\n- prometheus-metrics-core（传递依赖）类在类路径=")
                .append(isClassPresent("io.prometheus.metrics.model.registry.PrometheusRegistry"));
        boolean hasPrometheusRegistry = registries.values().stream()
                .anyMatch(r -> "PrometheusMeterRegistry".equals(r.getClass().getSimpleName()));
        sb.append("\n- Prometheus 注册器 Bean=").append(hasPrometheusRegistry ? "在" : "**不在**");
        sb.append("\n- prometheus 端点 Bean=")
                .append(context.containsBean("prometheusScrapeEndpoint") || context.containsBean("prometheusEndpoint")
                        ? "在" : "**不在**（则 404 是「端点不存在」，不是「闸门装错了」）");
        List<String> endpoints = context.getBeansOfType(
                        org.springframework.boot.actuate.endpoint.web.servlet.WebMvcEndpointHandlerMapping.class)
                .values().stream()
                .flatMap(m -> m.getHandlerMethods().keySet().stream())
                .map(Object::toString)
                .distinct()
                .toList();
        sb.append("\n- 已注册的 actuator 端点路径=").append(endpoints.isEmpty() ? "（一个都没有）" : endpoints);
        return sb.toString();
    }

    private static boolean isClassPresent(String name) {
        try {
            Class.forName(name, false, ActuatorScrapeTokenHttpTest.class.getClassLoader());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    @Test
    @DisplayName("不带令牌抓取 → 401（抓取方必须证明身份）")
    void withoutTokenIsRejected() throws Exception {
        HttpResponse<String> resp = get("/actuator/prometheus");
        assertThat(resp.statusCode())
                .as("无凭据抓取必须被拒——否则内部拓扑与依赖可达性裸奔%s", diagnostics())
                .isEqualTo(401);
    }

    @Test
    @DisplayName("配置的令牌 → 200 且返回 opsbrain_* 业务指标（Prometheus 真能抓到东西）")
    void withTokenGetsMetrics() throws Exception {
        HttpResponse<String> resp = get("/actuator/prometheus", "Authorization", "Bearer " + TOKEN);
        assertThat(resp.statusCode())
                .as("带正确令牌必须放行且**真能抓到**：404 = 端点不存在、403 = 闸门放行判定错、"
                        + "200 但无指标 = 计量器没接上；任一种都会让 up{job=opsbrain} 恒 0 → 宕机规则常亮误报%s",
                        diagnostics())
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
        assertThat(resp.statusCode()).as("错令牌必须被拒%s", diagnostics()).isEqualTo(401);
    }

    @Test
    @DisplayName("最小权限：同一令牌对其它 actuator 端点无效（仍 401）")
    void tokenIsScopedToScrapePath() throws Exception {
        assertThat(get("/actuator/info", "Authorization", "Bearer " + TOKEN).statusCode())
                .as("/actuator/info 含构建与依赖信息，不该被抓取令牌打开%s", diagnostics())
                .isEqualTo(401);
    }

    @Test
    @DisplayName("★ 闸门真的在拦：/actuator/info 无凭据也必须 401（本批 CI 红测暴露的真缺口）")
    void infoIsNotAnonymouslyReadable() throws Exception {
        // 本批最值钱的一条断言：此前 MVC 拦截器对 actuator 端点不生效，
        // /actuator/info 匿名可读——而这在 CI 之前从未被任何测试覆盖过。
        assertThat(get("/actuator/info").statusCode())
                .as("actuator 端点在真实部署里必须不可匿名读；这里变 200 就说明闸门又被绕过"
                        + "（MVC 拦截器管不到 actuator 的 handler mapping）%s", diagnostics())
                .isEqualTo(401);
    }

    @Test
    @DisplayName("令牌不放 URL 查询串（防访问日志/历史泄漏）")
    void tokenInQueryRejected() throws Exception {
        assertThat(get("/actuator/prometheus?token=" + TOKEN).statusCode())
                .as("URL 里的令牌不进任何日志就不会泄漏——所以不支持这种传法%s", diagnostics())
                .isEqualTo(401);
    }

    @Test
    @DisplayName("健康探针不受影响：无令牌仍 200（K8s 探针口径不能被本次修复破坏）")
    void healthStaysOpen() throws Exception {
        HttpResponse<String> resp = get("/actuator/health");
        assertThat(resp.statusCode())
                .as("探针不带凭据，拦下来 Pod 会一直 NotReady%s", diagnostics())
                .isEqualTo(200);
        assertThat(resp.body()).contains("UP");
    }
}
