package com.devops.agent.controller.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.router.SaHttpMethod;
import cn.dev33.satoken.router.SaRouter;
import cn.dev33.satoken.stp.StpUtil;
import com.devops.agent.common.context.TraceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import jakarta.annotation.PostConstruct;
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
 *   <li>/api/v1/alerts/webhook/** —— Prometheus/Alertmanager 与 Zabbix 推送，无 token
 *       （调用方是机器，鉴权由 WebhookGuard 的 token/HMAC/限流承担）</li>
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
    private final Environment environment;

    /**
     * 鉴权总开关。<b>默认 true（开启）</b>，仅允许在本地开发环境显式关闭。
     *
     * <p><b>为什么需要它</b>：2026-08-26 出现过「为 UI 预览把登录拦截器整段注释掉」
     * 的改动并被提交。注释代码没有开关语义——一旦忘了恢复就随代码合入生产，
     * 而且 review 时极不显眼。更隐蔽的是：登录拦截器不注册时 Sa-Token 上下文里
     * 没有会话，{@code @SaCheckRole} 的角色判定<b>也一并失效</b>，
     * 等于整条鉴权链被摘除，而不只是「少了一层登录」。</p>
     *
     * <p>改成配置开关后：生产默认安全、意图可审计（yml 里写着而不是藏在注释里）、
     * 关闭时启动日志会打醒目告警、集成测试仍走真实鉴权链。</p>
     */
    @Value("${devops.security.auth-enabled:true}")
    private boolean authEnabled;

    /**
     * 指标抓取面的令牌身份（2026-10-15）。
     *
     * <p>{@code /actuator/**} 需要登录是对的，但 Prometheus 不会登录：抓取恒 401 会让
     * {@code up{job="opsbrain"}} 恒 0 → 宕机规则常亮误报，其余应用规则恒哑（详见
     * {@link com.devops.agent.infrastructure.guard.ScrapeTokenGuard} 类注释）。
     * 守卫只对 {@code /actuator/prometheus} 一个路径生效，且默认关（未配令牌行为不变）。
     */
    private final com.devops.agent.infrastructure.guard.ScrapeTokenGuard scrapeTokenGuard;

    public WebConfig(
            com.devops.agent.common.audit.OperationAuditInterceptor auditInterceptor,
            com.devops.agent.infrastructure.guard.ScrapeTokenGuard scrapeTokenGuard,
            Environment environment) {
        this.auditInterceptor = auditInterceptor;
        this.scrapeTokenGuard = scrapeTokenGuard;
        this.environment = environment;
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
        // ⚠️ 需要本地免登录预览时，请设 devops.security.auth-enabled=false，
        // **不要注释这段代码**（2026-08-26 发生过一次，见 authEnabled 字段注释）。
        if (!authEnabled) {
            log.error("🚨🚨🚨 [WebConfig] 鉴权已关闭（devops.security.auth-enabled=false）"
                    + " —— 全部 /api/** 与 /actuator/** 免登录，且 @SaCheckRole 角色判定同时失效。"
                    + " 此配置仅供本地开发，出现在生产环境即为严重安全事故。");
            return;
        }

        registry.addInterceptor(new SaInterceptor(handle ->
                        SaRouter.notMatch(SaHttpMethod.OPTIONS).check(() -> StpUtil.checkLogin())))
                .addPathPatterns("/api/**")
                .excludePathPatterns(
                        "/api/v1/auth/**",         // 登录/取当前用户/登出
                        "/api/v1/health/**",       // K8s 探针
                        "/api/v1/alerts/webhook",    // Prometheus/Alertmanager 推送
                        // FR-1.2 的 /{system} 与 Zabbix 的 /zabbix 都落在这条通配下（前缀齐备，
                        // 不为单个来源再加一行——多一行就多一处漏改的机会）
                        "/api/v1/alerts/webhook/**"
                );

        // ==================== P2 自身可观测性：/actuator/** 必须显式鉴权 ====================
        // /actuator/** 不在 /api/** 下，上面的拦截器覆盖不到——不加这条，
        // 健康详情与 Prometheus 指标（含内部拓扑、依赖可达性）会裸奔公网。
        //
        // /actuator/health 放行给 K8s 探针：探针不带 token，
        // 拦下来会让 Pod 一直处于 NotReady。健康端点本身已配置为
        // 不暴露敏感明细（management.endpoint.health.show-details=never 时只回 UP/DOWN）。
        // 令牌身份先行放行（只对 /actuator/prometheus 生效，见 guard 类注释）：
        // 抓取方（Prometheus）带令牌直接通过；不带令牌或令牌不对，一律回落到登录校验——
        // 即"未配置令牌"与"配错令牌"的表现都是 401，不会意外敞开。
        registry.addInterceptor(new SaInterceptor(handle ->
                        SaRouter.match("/actuator/**")
                                .notMatch("/actuator/health", "/actuator/health/**")
                                .check(this::checkActuatorAccess)))
                .addPathPatterns("/actuator/**");
    }

    /**
     * actuator 端点放行判定：抓取令牌命中即放行，否则仍以登录身份为准。
     * <p>抽成方法而非内联三元，只为一件事：<b>让"谁能进"这一行读起来就是一句话</b>——
     * 安全判定的可读性就是它的可审计性。
     */
    private void checkActuatorAccess() {
        if (scrapeTokenGuard.allows(currentRequest())) {
            return;
        }
        StpUtil.checkLogin();
    }

    /** 取当前请求（拦截器阶段 Spring 已把请求放进 RequestContextHolder）。 */
    private static jakarta.servlet.http.HttpServletRequest currentRequest() {
        org.springframework.web.context.request.RequestAttributes attrs =
                org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        if (attrs instanceof org.springframework.web.context.request.ServletRequestAttributes servletAttrs) {
            return servletAttrs.getRequest();
        }
        return null;
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

    /**
     * 生产环境 CORS 白名单检查（启动时校验）— P3-1 批 86 加固
     * <p>
     * 强制生产环境必须配置具体域名，拒绝通配符 * 或占位符启动。
     * 防止「本地开发配置泄露到生产」导致的 CSRF/跨站读取风险。
     * </p>
     */
    @PostConstruct
    public void validateCorsConfig() {
        boolean isProd = Arrays.asList(environment.getActiveProfiles()).contains("prod");
        String trimmed = allowedOrigins.trim();
        boolean isWildcard = "*".equals(trimmed);
        boolean isPlaceholder = trimmed.contains("__MUST_SET_");

        if (isProd && (isWildcard || isPlaceholder)) {
            String message = """
                    ❌ [WebConfig] 生产环境检测到不安全的 CORS 配置！

                    当前配置：devops.security.cors.allowed-origins = %s
                    风险等级：P0（CSRF / 跨站读取攻击面）

                    生产环境必须配置具体域名白名单，例如：
                      CORS_ALLOWED_ORIGINS=https://ops.example.com,https://admin.example.com

                    或在 application-prod.yml 中：
                      devops.security.cors.allowed-origins: https://ops.example.com,https://admin.example.com

                    启动已终止，请修复配置后重启。
                    """.formatted(trimmed);
            log.error(message);
            throw new IllegalStateException("生产环境禁止使用 CORS 通配符或占位符配置");
        }

        if (isWildcard) {
            log.warn("⚠️ [WebConfig] CORS 允许任意来源 (*)，仅可用于本地开发");
        }
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String[] origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);

        if (origins.length == 1 && "*".equals(origins[0])) {
            log.warn("⚠️ [WebConfig] CORS 允许任意来源且允许携带凭证——仅可用于本地开发。");
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
