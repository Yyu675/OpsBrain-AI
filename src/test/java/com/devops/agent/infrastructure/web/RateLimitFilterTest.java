package com.devops.agent.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.devops.agent.common.web.ClientIpResolver;

/**
 * S5-3.5 API 入口限流过滤器单元测试（批 45）。
 *
 * <p>用 Spring mock 请求/响应直测滤件本体——不拉起 web 容器，
 * 不触鉴权链；瞄准的只有一件事：配额判定按（规则, IP）键守得住。
 */
class RateLimitFilterTest {

    private RateLimitProperties properties;
    private RateLimitFilter filter;

    /** 直连式解析器：不受信任何 XFF，键 = 请求的 remoteAddr（与生产默认同口径）。 */
    private ClientIpResolver directResolver() {
        return new ClientIpResolver("");
    }

    private RateLimitProperties.Rule rule(String name, String paths, int permits, int periodSeconds) {
        RateLimitProperties.Rule rule = new RateLimitProperties.Rule();
        rule.setName(name);
        rule.setPaths(paths);
        rule.setPermits(permits);
        rule.setPeriodSeconds(periodSeconds);
        return rule;
    }

    private MockHttpServletRequest request(String contextPath, String uri, String ip) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContextPath(contextPath == null ? "" : contextPath);
        request.setRequestURI((contextPath == null ? "" : contextPath) + uri);
        request.setRemoteAddr(ip);
        return request;
    }

    @BeforeEach
    void setUp() {
        properties = new RateLimitProperties();
        properties.setEnabled(true);
        properties.setRules(List.of(
                rule("login", "/api/v1/auth/login", 2, 60),
                rule("chat", "/api/v1/chat", 2, 60)));
        filter = new RateLimitFilter(properties, directResolver());
    }

    @Test
    @DisplayName("配额内放行，超额 429 + Retry-After + 拒收单 JSON")
    void overQuotaShouldBeRejected() throws Exception {
        MockHttpServletResponse first = new MockHttpServletResponse();
        filter.doFilter(request("/ai", "/api/v1/auth/login", "10.0.0.7"), first, new MockFilterChain());
        assertThat(first.getStatus()).isEqualTo(HttpStatus.OK.value());

        MockHttpServletResponse second = new MockHttpServletResponse();
        filter.doFilter(request("/ai", "/api/v1/auth/login", "10.0.0.7"), second, new MockFilterChain());
        assertThat(second.getStatus()).isEqualTo(HttpStatus.OK.value());

        MockHttpServletResponse third = new MockHttpServletResponse();
        filter.doFilter(request("/ai", "/api/v1/auth/login", "10.0.0.7"), third, new MockFilterChain());
        assertThat(third.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        assertThat(third.getHeader("Retry-After")).isEqualTo("60");
        assertThat(third.getContentAsString()).contains("RATE_LIMITED");
    }

    @Test
    @DisplayName("IP 分桶独立：A 机耗尽不影响 B 机，规则之间互不通气")
    void bucketsAreIsolatedByIpAndRule() throws Exception {
        // 10.0.0.1 把 login 配额打完
        filter.doFilter(request("/ai", "/api/v1/auth/login", "10.0.0.1"),
                new MockHttpServletResponse(), new MockFilterChain());
        MockHttpServletResponse last = new MockHttpServletResponse();
        filter.doFilter(request("/ai", "/api/v1/auth/login", "10.0.0.1"), last, new MockFilterChain());
        MockHttpServletResponse exhausted = new MockHttpServletResponse();
        filter.doFilter(request("/ai", "/api/v1/auth/login", "10.0.0.1"), exhausted, new MockFilterChain());
        assertThat(exhausted.getStatus()).isEqualTo(429);
        assertThat(last.getStatus()).isEqualTo(200);

        // 同 IP 换个规则：chat 配额全新
        MockHttpServletResponse chat = new MockHttpServletResponse();
        filter.doFilter(request("/ai", "/api/v1/chat", "10.0.0.1"), chat, new MockFilterChain());
        assertThat(chat.getStatus()).isEqualTo(200);

        // 同规则换个 IP：同样全新
        MockHttpServletResponse other = new MockHttpServletResponse();
        filter.doFilter(request("/ai", "/api/v1/auth/login", "10.0.0.2"), other, new MockFilterChain());
        assertThat(other.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("context-path 剥离后才比对：/ai 前缀不影响规则命中")
    void contextPathIsStrippedBeforeMatching() throws Exception {
        filter.doFilter(request("/ai", "/api/v1/auth/login", "9.9.9.9"),
                new MockHttpServletResponse(), new MockFilterChain());
        filter.doFilter(request("/ai", "/api/v1/auth/login", "9.9.9.9"),
                new MockHttpServletResponse(), new MockFilterChain());
        MockHttpServletResponse third = new MockHttpServletResponse();
        filter.doFilter(request("/ai", "/api/v1/auth/login", "9.9.9.9"), third, new MockFilterChain());
        assertThat(third.getStatus()).isEqualTo(429);
    }

    @Test
    @DisplayName("非 /api/ 与规则外前缀一律放行；disabled 时全放行")
    void unrelatedAndDisabledPathsPassThrough() throws Exception {
        // actuator 免税
        MockHttpServletResponse actuator = new MockHttpServletResponse();
        filter.doFilter(request("", "/actuator/health", "1.1.1.1"), actuator, new MockFilterChain());
        assertThat(actuator.getStatus()).isEqualTo(200);

        // 规则外 api 前缀免税
        MockHttpServletResponse otherApi = new MockHttpServletResponse();
        filter.doFilter(request("", "/api/v1/tickets", "1.1.1.1"), otherApi, new MockFilterChain());
        assertThat(otherApi.getStatus()).isEqualTo(200);

        // disabled：同样是 login 也不拦
        properties.setEnabled(false);
        MockHttpServletResponse off = new MockHttpServletResponse();
        filter.doFilter(request("", "/api/v1/auth/login", "1.1.1.1"), off, new MockFilterChain());
        assertThat(off.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("窗口过期后配额回补（1s 窗实测，不是永远拒）")
    void windowRefillsAfterPeriod() throws Exception {
        properties.setRules(List.of(rule("login", "/api/v1/auth/login", 1, 1)));
        RateLimitFilter oneShot = new RateLimitFilter(properties, directResolver());

        MockHttpServletResponse denied = new MockHttpServletResponse();
        oneShot.doFilter(request("", "/api/v1/auth/login", "3.3.3.3"),
                new MockHttpServletResponse(), new MockFilterChain());
        oneShot.doFilter(request("", "/api/v1/auth/login", "3.3.3.3"), denied, new MockFilterChain());
        assertThat(denied.getStatus()).isEqualTo(429);

        Thread.sleep(1100);   // 跨窗口（窗长 1s）——限流是配速不是封号
        MockHttpServletResponse after = new MockHttpServletResponse();
        oneShot.doFilter(request("", "/api/v1/auth/login", "3.3.3.3"), after, new MockFilterChain());
        assertThat(after.getStatus()).isEqualTo(200);
    }
}
