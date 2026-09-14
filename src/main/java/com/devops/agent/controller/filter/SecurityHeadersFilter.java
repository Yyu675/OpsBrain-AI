package com.devops.agent.controller.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 安全响应头过滤器（P3-3 批 86）
 * <p>
 * 为所有响应添加安全响应头，防护 XSS、点击劫持、MIME 嗅探等攻击。
 * 仅生产环境启用（开发环境可能因本地调试需要宽松策略）。
 * </p>
 */
@Slf4j
@Component
@Order(1)  // 早于业务过滤器执行
@ConditionalOnProperty(
        prefix = "devops.security",
        name = "headers-enabled",
        havingValue = "true",
        matchIfMissing = false  // 默认关闭，生产显式开启
)
public class SecurityHeadersFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        // X-Frame-Options：防止被恶意站点嵌入 iframe（点击劫持防护）
        // DENY = 完全禁止嵌入，SAMEORIGIN = 仅允许同源嵌入
        response.setHeader("X-Frame-Options", "DENY");

        // X-Content-Type-Options：禁止浏览器 MIME 嗅探
        // 防止浏览器将非脚本文件当作脚本执行（例如上传的 .txt 被识别为 .js）
        response.setHeader("X-Content-Type-Options", "nosniff");

        // X-XSS-Protection：启用浏览器 XSS 过滤器（现代浏览器已内置）
        // 1; mode=block = 检测到 XSS 时阻止渲染而非净化
        response.setHeader("X-XSS-Protection", "1; mode=block");

        // Referrer-Policy：控制 Referer 头泄露
        // strict-origin-when-cross-origin = 同源时发送完整 URL，跨域只发送源
        response.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");

        // Permissions-Policy：限制浏览器特性访问（替代旧的 Feature-Policy）
        // 禁用不必要的特性：地理位置、摄像头、麦克风、支付等
        response.setHeader("Permissions-Policy",
                "geolocation=(), camera=(), microphone=(), payment=(), usb=()");

        // Content-Security-Policy：内容安全策略（防 XSS 核心）
        // default-src 'self' = 默认只允许同源资源
        // script-src 'self' = 脚本只能来自同源（禁止内联脚本和 eval）
        // style-src 'self' 'unsafe-inline' = 样式允许同源和内联（Element Plus 需要）
        // img-src 'self' data: https: = 图片允许同源、data URI 和 HTTPS
        // connect-src 'self' = AJAX/WebSocket 只能连同源
        // font-src 'self' data: = 字体允许同源和 data URI
        // object-src 'none' = 禁止 <object>、<embed>、<applet>
        // frame-ancestors 'none' = 禁止被任何站点嵌入（与 X-Frame-Options 重复但更强）
        //
        // ⚠️ 注意：此 CSP 较严格，若前端使用 CDN 或第三方脚本需要调整
        response.setHeader("Content-Security-Policy",
                "default-src 'self'; " +
                        "script-src 'self'; " +
                        "style-src 'self' 'unsafe-inline'; " +
                        "img-src 'self' data: https:; " +
                        "connect-src 'self'; " +
                        "font-src 'self' data:; " +
                        "object-src 'none'; " +
                        "frame-ancestors 'none'; " +
                        "base-uri 'self'; " +
                        "form-action 'self'");

        // Strict-Transport-Security：强制 HTTPS（HSTS）
        // max-age=31536000 = 1 年内浏览器只通过 HTTPS 访问
        // includeSubDomains = 子域名也强制 HTTPS
        // preload = 允许加入浏览器预加载列表
        //
        // ⚠️ 注意：仅在 HTTPS 环境下生效，HTTP 下浏览器会忽略此头
        if (request.isSecure()) {
            response.setHeader("Strict-Transport-Security",
                    "max-age=31536000; includeSubDomains; preload");
        }

        filterChain.doFilter(request, response);
    }
}
