package com.devops.agent.infrastructure.web;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

import com.devops.agent.common.web.ClientIpResolver;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * API 入口限流过滤器（S5-3.5，批 45 / 报告 148）。
 *
 * <h3>位置与理由</h3>
 * 排在 {@code TraceIdFilter}（HIGHEST_PRECEDENCE）之后——既然进了限流判席，
 * 响应头就要能串回 traceId;429 被拒的请求恰恰是需要审计的那批。
 * 与鉴权 Filter 的相对序无关：本滤不靠登录态，键全走 IP。
 *
 * <h3>判定模型</h3>
 * 规则前缘命中（去除 context-path 后比对）→ 按 (规则, IP) 分桶发 resilience4j
 * RateLimiter(timeoutDuration=ZERO，即「不等」）。
 * acquirePermission=false → 429 + Retry-After + JSON 拒收单；
 * 不等是对的：HTTP 滴灌必伤线程，让客户端自己 123 再来。
 *
 * <h3>已知边界（焊在车身上，不当隐病）</h3>
 * <ol>
 *   <li><b>IP 桶而非用户桶</b>——登录态按人计费需要 Sa-Token 上下文钉序，
 *       顺序一错游客吃用户配额，故本版仅 IP；用户级配额列入后续增强。</li>
 *   <li><b>桶表常驻不枯萎</b>——键数上 20000 时整表换重（摊还式粗回收，
 *       代价是全部桶重置计数；成本上限明确，长期精益待真窗统计）。</li>
 *   <li><b>XFF 辨伪归口</b>——客户端 IP 一律经 {@link ClientIpResolver}
 *       （受信代理口径）；本滤不自带一套辨伪逻辑，两套并生是双皮配方。</li>
 *   <li><b>单机口径</b>——多副本部署时每副本各自计数，全局配额 ≈ 配额 × 副本数；
 *       真到多副本，桶要迁 Redis（列入多副本时代清单，不在本批出厂）。</li>
 * </ol>
 *
 * <h3>注册方式（不标 @Component 的原因）</h3>
 * 若用 {@code @Component}，{@code @WebMvcTest} 切片会把 Filter 扫进来，
 * 却扫不到 {@link ClientIpResolver}——25 个 web 测试类会集体上下文装配失败。
 * 改由 {@code RateLimitWebConfig} {@code FilterRegistrationBean} 登记：
 * 纯 @Configuration 不进 web 切片，全上下文照常生效——冲击半径归零。
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    /** 桶表抗病换新的阈值：超过即整表丢弃重建（粗、廉价、上限明确）。 */
    private static final int BUCKET_TABLE_HARD_CAP = 20000;

    private final RateLimitProperties properties;
    private final ClientIpResolver clientIpResolver;
    /** (规则名 + "|" + IP) → 独立 RateLimiter；按需铸，窗口随配置。 */
    private final ConcurrentHashMap<String, RateLimiter> buckets = new ConcurrentHashMap<>();

    public RateLimitFilter(RateLimitProperties properties, ClientIpResolver clientIpResolver) {
        this.properties = properties;
        this.clientIpResolver = clientIpResolver;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!properties.isEnabled()) {
            return true;
        }
        // 只审 /api/ 前缀：actuator / 静态资源 / 健康探针一律不课税
        return !strippedPath(request).startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        RateLimitProperties.Rule rule = matchRule(strippedPath(request));
        if (rule == null) {
            filterChain.doFilter(request, response);
            return;
        }

        String bucketKey = rule.getName() + "|" + clientIpResolver.resolve(request);
        if (buckets.size() > BUCKET_TABLE_HARD_CAP) {
            buckets.clear();
            log.warn("⚠️ [RateLimit] 桶表超硬顶重建 | cap={} | 全部桶计数归零（摊还式粗回收）",
                    BUCKET_TABLE_HARD_CAP);
        }
        RateLimiter bucket = buckets.computeIfAbsent(bucketKey, k -> newLimiter(rule));

        if (bucket.acquirePermission()) {
            filterChain.doFilter(request, response);
            return;
        }

        reject(response, rule);
    }

    /** 路径比对：去除 context-path（含 /ai 前缀）后按前缀命中首条规则。 */
    private String strippedPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String ctx = request.getContextPath();
        if (ctx != null && !ctx.isEmpty() && uri.startsWith(ctx)) {
            uri = uri.substring(ctx.length());
        }
        return uri.isEmpty() ? "/" : uri;
    }

    private RateLimitProperties.Rule matchRule(String path) {
        List<RateLimitProperties.Rule> rules = properties.getRules();
        if (rules == null) {
            return null;
        }
        for (RateLimitProperties.Rule rule : rules) {
            if (rule.getPaths() == null) {
                continue;
            }
            for (String prefix : rule.getPaths().split(",")) {
                String p = prefix.trim();
                if (!p.isEmpty() && path.startsWith(p)) {
                    return rule;
                }
            }
        }
        return null;
    }

    /** 每键独立铸闸：窗口配额随规则，timeout=ZERO（HTTP 面子不排队）。 */
    private RateLimiter newLimiter(RateLimitProperties.Rule rule) {
        RateLimiterConfig config = RateLimiterConfig.custom()
                .limitForPeriod(Math.max(1, rule.getPermits()))
                .limitRefreshPeriod(Duration.ofSeconds(Math.max(1, rule.getPeriodSeconds())))
                .timeoutDuration(Duration.ZERO)
                .build();
        return RateLimiter.of("http-" + rule.getName(), config);
    }

    /** 拒收：429 + Retry-After + JSON 拒收单（不含规则配置细节，防攻击方探边界）。 */
    private void reject(HttpServletResponse response, RateLimitProperties.Rule rule) throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader("Retry-After", String.valueOf(rule.getPeriodSeconds()));
        response.setContentType("application/json;charset=UTF-8");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        try (PrintWriter writer = response.getWriter()) {
            writer.write("{\"code\":\"RATE_LIMITED\",\"message\":\"请求过于频繁，请稍后重试\"}");
        }
        log.info("🚦 [RateLimit] 拒收 | rule={} | permits={}/{}s", rule.getName(), rule.getPermits(),
                rule.getPeriodSeconds());
    }
}
