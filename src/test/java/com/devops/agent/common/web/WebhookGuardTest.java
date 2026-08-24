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
    private WebhookGuard guard;
    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        limiter = mock(SlidingWindowRateLimiter.class);
        when(limiter.tryAcquire(anyString(), anyString(), anyInt(), anyLong())).thenReturn(true);

        request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("10.0.0.1");

        guard = new WebhookGuard(limiter);
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

    @Test
    @DisplayName("优先用 X-Forwarded-For 首段作为限流主体，代理后仍能区分真实来源")
    void usesForwardedForAsIdentity() {
        when(request.getHeader("X-Forwarded-For")).thenReturn("203.0.113.7, 10.0.0.9");

        guard.verify(request);

        org.mockito.Mockito.verify(limiter)
                .tryAcquire(org.mockito.ArgumentMatchers.eq("webhook"),
                        org.mockito.ArgumentMatchers.eq("203.0.113.7"), anyInt(), anyLong());
    }

    @Test
    @DisplayName("无 X-Forwarded-For 时回退 remoteAddr")
    void fallsBackToRemoteAddr() {
        when(request.getHeader("X-Forwarded-For")).thenReturn(null);

        guard.verify(request);

        org.mockito.Mockito.verify(limiter)
                .tryAcquire(anyString(), org.mockito.ArgumentMatchers.eq("10.0.0.1"), anyInt(), anyLong());
    }
}
