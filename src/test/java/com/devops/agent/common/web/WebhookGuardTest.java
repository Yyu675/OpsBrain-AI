package com.devops.agent.common.web;

import com.devops.agent.common.exception.WebhookRejectedException;
import com.devops.agent.infrastructure.cache.SlidingWindowRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link WebhookGuard} 行为测试。
 *
 * <p>保护的契约：<b>免鉴权端点必须有替代防线</b>。
 * {@code /api/v1/alerts/webhook} 直接写库并可触发自动建单，
 * 一旦防护失效，任何人都能灌入伪造告警。</p>
 */
class WebhookGuardTest {

    private SlidingWindowRateLimiter limiter;
    private ClientIpResolver clientIpResolver;
    private WebhookGuard guard;
    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        limiter = mock(SlidingWindowRateLimiter.class);
        when(limiter.tryAcquire(anyString(), anyString(), anyInt(), anyLong())).thenReturn(true);

        request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("10.0.0.1");

        // WebhookGuard 现在通过 ClientIpResolver 取客户端 IP（不再直接读 getRemoteAddr），
        // 以免 X-Forwarded-For 被无条件信任导致限流可绕过。
        // 这里桩成回落 getRemoteAddr，与「无可信代理」时的真实行为一致，
        // 从而保持本测试原有的 IP 语义不变。
        clientIpResolver = mock(ClientIpResolver.class);
        when(clientIpResolver.resolve(any())).thenReturn("10.0.0.1");

        guard = new WebhookGuard(limiter, clientIpResolver);
        ReflectionTestUtils.setField(guard, "secret", "");
        ReflectionTestUtils.setField(guard, "rateLimit", 300);
        ReflectionTestUtils.setField(guard, "rateWindowMs", 60000L);
    }

    @Test
    @DisplayName("未配置密钥时放行，保持对既有 alertmanager.yml 的向后兼容")
    void allowsWhenSecretNotConfigured() {
        assertDoesNotThrow(() -> guard.verify(request));
    }

    @Test
    @DisplayName("配置密钥后，携带正确 token 放行")
    void allowsCorrectToken() {
        ReflectionTestUtils.setField(guard, "secret", "s3cr3t");
        when(request.getHeader(WebhookGuard.TOKEN_HEADER)).thenReturn("s3cr3t");

        assertDoesNotThrow(() -> guard.verify(request));
    }

    @Test
    @DisplayName("配置密钥后，缺少 token 抛 401——不建议重试，属配置错误")
    void rejectsMissingToken() {
        ReflectionTestUtils.setField(guard, "secret", "s3cr3t");
        when(request.getHeader(WebhookGuard.TOKEN_HEADER)).thenReturn(null);

        var ex = assertThrows(WebhookRejectedException.class, () -> guard.verify(request));

        assertEquals(401, ex.getHttpStatus());
        assertEquals(0, ex.getRetryAfterSeconds(), "鉴权失败重试无意义，不应给 Retry-After");
    }

    @Test
    @DisplayName("token 不匹配抛 401")
    void rejectsWrongToken() {
        ReflectionTestUtils.setField(guard, "secret", "s3cr3t");
        when(request.getHeader(WebhookGuard.TOKEN_HEADER)).thenReturn("wrong");

        var ex = assertThrows(WebhookRejectedException.class, () -> guard.verify(request));

        assertEquals(401, ex.getHttpStatus());
    }

    @Test
    @DisplayName("触发限流抛 429 且带 Retry-After，让 Alertmanager 退避重投而非丢告警")
    void rejectsWhenRateLimited() {
        when(limiter.tryAcquire(anyString(), anyString(), anyInt(), anyLong())).thenReturn(false);

        var ex = assertThrows(WebhookRejectedException.class, () -> guard.verify(request));

        assertEquals(429, ex.getHttpStatus());
        assertTrue(ex.getRetryAfterSeconds() > 0, "429 必须给出退避时长，否则告警可能永久丢失");
    }

    @Test
    @DisplayName("限流先于密钥校验，使错误密钥的暴力尝试同样被挡住")
    void rateLimitAppliesBeforeAuth() {
        ReflectionTestUtils.setField(guard, "secret", "s3cr3t");
        when(request.getHeader(WebhookGuard.TOKEN_HEADER)).thenReturn("wrong");
        when(limiter.tryAcquire(anyString(), anyString(), anyInt(), anyLong())).thenReturn(false);

        var ex = assertThrows(WebhookRejectedException.class, () -> guard.verify(request));

        assertEquals(429, ex.getHttpStatus(), "应先被限流拦下，而不是走到密钥比较");
    }

    /**
     * 限流主体取自 {@link ClientIpResolver}，而不是自己解析 X-Forwarded-For。
     *
     * <p>这两个测试原本直接桩 {@code X-Forwarded-For} 头并断言首段被用作限流键——
     * 那是<b>修复前</b>的行为，且正是当时的缺陷：无条件信任该头意味着
     * 攻击者随手伪造一个就能换一个限流桶，限流形同虚设。</p>
     *
     * <p>修复后 IP 解析职责移入 {@code ClientIpResolver}（只有在请求来自
     * 可信代理时才采信该头），其取舍由 {@code ClientIpResolverTest} 单独覆盖。
     * 这里只需保证 WebhookGuard <b>确实走了解析器</b>——
     * 若哪天有人图省事改回 {@code request.getRemoteAddr()}，本测试会失败。</p>
     */
    @Test
    @DisplayName("限流主体来自 ClientIpResolver，而非自行解析请求头")
    void usesResolverAsIdentity() {
        when(clientIpResolver.resolve(request)).thenReturn("203.0.113.7");

        guard.verify(request);

        org.mockito.Mockito.verify(clientIpResolver).resolve(request);
        org.mockito.Mockito.verify(limiter)
                .tryAcquire(org.mockito.ArgumentMatchers.eq("webhook"),
                        org.mockito.ArgumentMatchers.eq("203.0.113.7"), anyInt(), anyLong());
    }

    @Test
    @DisplayName("不再直接读 X-Forwarded-For——伪造该头不能换限流桶")
    void ignoresForgedForwardedForHeader() {
        // 攻击者伪造头，但解析器（无可信代理配置）仍回落到真实 remoteAddr
        when(request.getHeader("X-Forwarded-For")).thenReturn("1.2.3.4, 5.6.7.8");
        when(clientIpResolver.resolve(request)).thenReturn("10.0.0.1");

        guard.verify(request);

        org.mockito.Mockito.verify(limiter)
                .tryAcquire(anyString(), org.mockito.ArgumentMatchers.eq("10.0.0.1"),
                        anyInt(), anyLong());
    }
}
