package com.devops.agent.controller.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 全局配置 - CORS 跨域配置
 * <p>
 * 用途：允许前端（Vue3 开发服务器）跨域访问后端接口
 * </p>
 * <p>
 * 开发环境：http://localhost:5173 （Vite 默认端口）
 * 生产环境：根据实际部署调整 allowedOrigins
 * </p>
 *
 * @author OpsBrain AI
 * @since 2026-07-15
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")  // 允许所有 /api 开头的接口跨域
                .allowedOrigins(
                        "http://localhost:5173",      // Vite 开发服务器
                        "http://localhost:5174",      // 备用端口
                        "http://127.0.0.1:5173"       // 本地回环地址
                )
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)  // 允许携带 Cookie
                .maxAge(3600);           // 预检请求缓存 1 小时
    }
}
