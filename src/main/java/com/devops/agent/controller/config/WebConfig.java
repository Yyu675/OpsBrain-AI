package com.devops.agent.controller.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.router.SaHttpMethod;
import cn.dev33.satoken.router.SaRouter;
import cn.dev33.satoken.stp.StpUtil;
import com.devops.agent.common.context.TraceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;

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
@Slf4j
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final com.devops.agent.common.audit.OperationAuditInterceptor auditInterceptor;

    public WebConfig(com.devops.agent.common.audit.OperationAuditInterceptor auditInterceptor) {
        this.auditInterceptor = auditInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // C5：通用写操作审计。放在鉴权拦截器**之前**注册，
        // 使「未登录尝试写操作」这类事件同样留痕——这恰恰是安全排查最想看的记录。
        // 它只在 afterCompletion 做事，不影响请求放行。
        registry.addInterceptor(auditInterceptor).addPathPatterns("/api/**");

        // Sa-Token 登录校验拦截器：未登录访问受保护端点抛 NotLoginException，
        // 由 GlobalExceptionHandler 统一映射为 401 + 业务码 40101。
        //
        // notMatch(OPTIONS) 放过 CORS 预检——预检不带自定义头，校验必然失败，
        // 且预检必须返回 2xx，返回 401 一样会被浏览器判为 CORS 失败（详见类注释）。
        //
        // ⚠️ 曾于 2026-08-26 被注释掉（「为 UI 预览临时关闭」），已恢复。
        //
        // 那次改动让全部 /api/** 变成免鉴权：GovernanceRoleGuardIntegrationTest
        // 立刻报 "Status expected:<403> but was:<200>"——OPS 角色访问
        // Saga 逆向补偿、审计日志等治理端点被直接放行。
        //
        // 注释掉认证是**不可接受的临时手段**：它没有开关语义（一旦忘了恢复
        // 就随代码合入生产），而且被注释的同时连 @SaCheckRole 的角色判定
        // 也一并失效——登录拦截器不跑，Sa-Token 上下文里没有会话，
        // 角色注解拿不到身份，等于整条鉴权链被摘除。
        //
        // 若确需本地免登录预览，正确做法是走**配置开关**而非改代码，例如
        // 加 @ConditionalOnProperty(name = "devops.security.auth-enabled",
        // havingValue = "true", matchIfMissing = true)，
        // 由 application-dev.yml 显式关闭——这样生产默认安全、意图可审计，
        // 且集成测试仍走真实鉴权链。
        registry.addInterceptor(new SaInterceptor(handle ->
                        SaRouter.notMatch(SaHttpMethod.OPTIONS).check(() -> StpUtil.checkLogin())))
                .addPathPatterns("/api/**")
                .excludePathPatterns(
                        "/api/v1/auth/**",         // 登录/取当前用户/登出
                        "/api/v1/health/**",       // K8s 探针
                        "/api/v1/alerts/webhook"   // Prometheus/Alertmanager 推送
                );

        // ==================== P2 自身可观测性：/actuator/** 必须显式鉴权 ====================
        // /actuator/** 不在 /api/** 下，上面的拦截器覆盖不到——不加这条，
        // 健康详情与 Prometheus 指标（含内部拓扑、依赖可达性）会裸奔公网。
        //
        // /actuator/health 放行给 K8s 探针：探针不带 token，
        // 拦下来会让 Pod 一直处于 NotReady。健康端点本身已配置为
        // 不暴露敏感明细（management.endpoint.health.show-details=never 时只回 UP/DOWN）。
        registry.addInterceptor(new SaInterceptor(handle ->
                        SaRouter.match("/actuator/**")
                                .notMatch("/actuator/health", "/actuator/health/**")
                                .check(() -> StpUtil.checkLogin())))
                .addPathPatterns("/actuator/**");
    }

    /**
     * 允许跨域的来源（A6/D3）。
     * <p>
     * 默认 {@code *} 仅适用于本地开发。<b>生产必须通过环境变量
     * {@code CORS_ALLOWED_ORIGINS} 显式列举域名</b>，例如
     * {@code https://ops.example.com,https://admin.example.com}。
     * </p>
     * <p>
     * 为什么不能在生产用 {@code *}：本项目 {@code allowCredentials(true)}，
     * 而 {@code allowedOriginPatterns("*")} 会<b>回显请求方的 Origin</b>，
     * 等价于「任意站点都可以带着用户凭证调用本站 API」。
     * 用户只要在登录状态下访问一个恶意页面，该页面就能以其身份
     * 读知识库、建工单、走审批。这不是理论风险，是标准的 CSRF/跨站读取路径。
     * </p>
     */
    @Value("${devops.security.cors.allowed-origins:*}")
    private String allowedOrigins;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String[] origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);

        if (origins.length == 1 && "*".equals(origins[0])) {
            log.warn("⚠️ [WebConfig] CORS 允许任意来源且允许携带凭证——仅可用于本地开发。"
                    + "生产环境请设置 CORS_ALLOWED_ORIGINS 为具体域名列表。");
        } else {
            log.info("🔒 [WebConfig] CORS 白名单已启用 | origins={}", String.join(", ", origins));
        }

        registry.addMapping("/api/**")  // 允许所有 /api 开头的接口跨域
                // allowedOriginPatterns 支持通配符且会回显实际 Origin 而非返回 *，
                // 因此与 allowCredentials(true) 兼容（allowedOrigins("*") 则不兼容）。
                .allowedOriginPatterns(origins)
                // 补 PATCH：工单状态/负责人更新用 PATCH，此前遗漏会导致预检失败
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")             // 含 satoken token 头
                // 暴露 traceId 响应头（A5）：跨域下前端默认<b>读不到</b>自定义响应头，
                // 必须显式 expose，否则前端拿不到 X-Request-Id，报错时无法回传给后端排查。
                .exposedHeaders(TraceContext.HEADER)
                .allowCredentials(true)          // 允许携带 Cookie
                .maxAge(3600);                   // 预检请求缓存 1 小时
    }
}
