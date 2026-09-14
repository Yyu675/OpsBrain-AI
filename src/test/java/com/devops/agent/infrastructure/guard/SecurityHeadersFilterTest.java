package com.devops.agent.infrastructure.guard;

import com.devops.agent.controller.filter.SecurityHeadersFilter;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SecurityHeadersFilter 的 CSP connect-src 派生（批 88 P2-2）。
 * 分域部署时前端域名来自 CORS 配置——派生错误会让浏览器静默拦掉所有
 * API 调用（后端日志无痕迹），故此处单测守住。
 */
class SecurityHeadersFilterTest {

    private SecurityHeadersFilter filterWithOrigins(String origins) {
        SecurityHeadersFilter f = new SecurityHeadersFilter();
        ReflectionTestUtils.setField(f, "corsAllowedOrigins", origins);
        return f;
    }

    private String cspOf(String origins) {
        return (String) ReflectionTestUtils.invokeMethod(filterWithOrigins(origins), "cspConnectSources");
    }

    @Test
    @DisplayName("通配 * 不追加任何源（同源 'self' 已覆盖）")
    void wildcardAddsNothing() {
        assertThat(cspOf("*")).isEmpty();
    }

    @Test
    @DisplayName("分域白名单追加到 connect-src，localhost 不追加")
    void splitDomainsAppended() {
        String out = cspOf("https://ops.example.com, https://admin.example.com, http://localhost:5173");
        assertThat(out).contains("https://ops.example.com")
                .contains("https://admin.example.com")
                .doesNotContain("localhost");
    }

    @Test
    @DisplayName("MUST_SET 占位符不追加（防配置泄漏到 CSP 头）")
    void placeholderNotAppended() {
        assertThat(cspOf("__MUST_SET_CORS_ALLOWED_ORIGINS__")).isEmpty();
    }
}
