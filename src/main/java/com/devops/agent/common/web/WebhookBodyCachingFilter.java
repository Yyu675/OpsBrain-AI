package com.devops.agent.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;

import java.io.IOException;

/**
 * Webhook 原始请求体缓存（方案⑤ HMAC 通道的前置件，2026-10-01）。
 *
 * <p>
 * 为什么必须有过滤器：签名要对「发送方视角的原始字节」做 HMAC，但
 * 控制器方法执行时 Spring 已把 body 消费成反序列化对象——方法内再取
 * {@code getInputStream()} 只会拿到 EOF。{@link ContentCachingRequestWrapper}
 * 在读取过程中顺手缓存字节，控制器拿到的已是包装后的请求，
 * {@link WebhookGuard} 用 {@code getContentAsByteArray()} 取原文验签。
 * </p>
 *
 * <p>
 * 只包装 webhook 路径（含 {@code /{system}} 变体）：其余端点零开销。
 * 未命中本过滤器时 guard 拿到空数组 → 签名通道 fail-closed 拒绝，
 * token/Bearer 通道不受影响（不依赖 body）。
 * </p>
 */
@Component
@Order(100)
public class WebhookBodyCachingFilter extends OncePerRequestFilter {

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return !uri.endsWith("/alerts/webhook") && !uri.contains("/api/v1/alerts/webhook/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        filterChain.doFilter(new ContentCachingRequestWrapper(request), response);
    }
}
