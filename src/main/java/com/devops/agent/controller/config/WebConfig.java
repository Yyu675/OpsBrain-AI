package com.devops.agent.controller.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
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
 * SSE（/api/v1/chat/stream）与其余端点均需登录；SSE 因 EventSource 不支持自定义头，
 * 前端把 token 放 query 参数，Sa-Token 的 token-read-from-params 会读取。
 * WebSocket /ws/** 不在 /api/** 下，本拦截器不管（握手鉴权另做）。
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
        registry.addInterceptor(new SaInterceptor(handle -> StpUtil.checkLogin()))
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
                .allowedOrigins(
                        "http://localhost:5173",      // Vite 开发服务器
                        "http://localhost:5174",      // 备用端口
                        "http://127.0.0.1:5173"       // 本地回环地址
                )
                // 补 PATCH：工单状态/负责人更新用 PATCH，此前遗漏会导致预检失败
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")             // 含 satoken token 头
                .allowCredentials(true)          // 允许携带 Cookie
                .maxAge(3600);                   // 预检请求缓存 1 小时
    }
}
