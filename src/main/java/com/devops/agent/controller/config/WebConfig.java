package com.devops.agent.controller.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.router.SaHttpMethod;
import cn.dev33.satoken.router.SaRouter;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 全局配置 - CORS 跨域 + Sa-Token 鉴权拦截器
 * <p>
 * 用途：允许前端（Vue3 开发服务器）跨域访问后端接口；对业务端点做 Sa-Token 登录校验。
 * </p>
 *
 * <h3>鉴权白名单（excludePathPatterns）</h3>
 * <ul>
 *   <li>/api/v1/auth/** —— 登录/取当前用户/登出（登录本身不需 token；me/logout 内部自判）</li>
 *   <li>/api/v1/health/** —— K8s 探针，高频拉取，不能要求 token</li>
 *   <li>/api/v1/alerts/webhook —— Prometheus/Alertmanager 推送，无 token</li>
 * </ul>
 * SSE（/api/v1/chat/stream）与其余端点均需登录；前端用 {@code @microsoft/fetch-event-source}
 * （基于 fetch，支持自定义头）在 satoken 请求头里带 token，无需退化到 query 参数。
 * WebSocket /ws/** 不在 /api/** 下，本拦截器不管（握手鉴权另做）。
 *
 * <h3>为何必须放过 OPTIONS（CORS 预检）</h3>
 * Spring 的 {@code AbstractHandlerMapping#getCorsHandlerExecutionChain} 在预检请求上
 * 只把 handler 换成内部的 {@code PreFlightHandler}，<b>却保留整条拦截器链</b>。
 * 而浏览器的预检请求按规范<b>不携带自定义请求头</b>（satoken 头正在其中），
 * 于是 {@code checkLogin()} 必然抛 {@code NotLoginException} → 预检返回非 2xx →
 * 浏览器判定 CORS 失败并阻止真实请求，表现为全站接口挂掉。
 * <p>
 * 放过 OPTIONS 不构成安全缺口：预检不携带业务数据，紧随其后的真实请求
 * （GET/POST/PUT/PATCH/DELETE）仍会被完整校验。
 * </p>
 *
 * @author OpsBrain AI
 * @since 2026-07-15
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // Sa-Token 登录校验拦截器：未登录访问受保护端点抛 NotLoginException，
        // 由 GlobalExceptionHandler 统一映射为 401 + 业务码 40101。
        //
        // notMatch(OPTIONS) 放过 CORS 预检——预检不带自定义头，校验必然失败，
        // 且预检必须返回 2xx，返回 401 一样会被浏览器判为 CORS 失败（详见类注释）。
        registry.addInterceptor(new SaInterceptor(handle ->
                        SaRouter.notMatch(SaHttpMethod.OPTIONS).check(() -> StpUtil.checkLogin())))
                .addPathPatterns("/api/**")
                .excludePathPatterns(
                        "/api/v1/auth/**",         // 登录/取当前用户/登出
                        "/api/v1/health/**",       // K8s 探针
                        "/api/v1/alerts/webhook"   // Prometheus/Alertmanager 推送
                );
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")  // 允许所有 /api 开头的接口跨域
                // allowedOriginPatterns("*")：允许任意来源（localhost/127.0.0.1/LAN IP）。
                // 与 allowedOrigins 不同，allowedOriginPatterns 支持通配符且会回显实际 Origin
                // 而非返回 *，因此与 allowCredentials(true) 兼容。
                // 开发阶段接受任意来源；生产如需收紧，可改为具体域名列表。
                .allowedOriginPatterns("*")
                // 补 PATCH：工单状态/负责人更新用 PATCH，此前遗漏会导致预检失败
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")             // 含 satoken token 头
                .allowCredentials(true)          // 允许携带 Cookie
                .maxAge(3600);                   // 预检请求缓存 1 小时
    }
}
