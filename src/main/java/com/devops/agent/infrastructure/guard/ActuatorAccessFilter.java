package com.devops.agent.infrastructure.guard;

import cn.dev33.satoken.stp.StpUtil;
import com.devops.agent.common.error.BizError;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * {@code /actuator/**} 的访问闸门。
 *
 * <h3>为什么不是「像原来那样加个 MVC 拦截器」（这是本类存在的全部理由）</h3>
 * 2026-10-15 的 CI 实证推翻了此前的假设。原实现把 actuator 的鉴权放在
 * {@code WebMvcConfigurer.addInterceptors} 注册的 {@code SaInterceptor} 上，
 * 而 <b>Spring MVC 的 {@code HandlerInterceptor} 对 actuator 端点根本不生效</b>：
 * actuator 端点的 handler 由 Boot 自己的 {@code WebMvcEndpointHandlerMapping} 提供
 * （{@code WebMvcEndpointManagementContextConfiguration} 里 {@code new WebMvcEndpointHandlerMapping(...)}，
 * 从不调用 {@code setInterceptors}），拦截器链是空集。因此：
 * <ul>
 *   <li>真实端点上「未登录 401」<b>从来没成立过</b>；</li>
 *   <li>本地见过的 401 是<b>假象</b>：当时 micrometer 缺件 → {@code /actuator/prometheus}
 *       没有 handler → 回落到静态资源处理器（那个是 MVC 管的）→ 拦截器才跑起来。
 *       端点一旦真的存在，拦截器就绕过去了；</li>
 *   <li>后果与「运维手册 §9.5」要修的恰好相反但同样严重：{@code /actuator/info}
 *       （含 {@code show-details: always} 的 health 路由、依赖地址、配置）在真实部署里
 *       <b>匿名可读</b>。</li>
 * </ul>
 * CI 两处红测即证据：{@code /actuator/info} 带令牌 200（拦截器没拦）、
 * {@code /actuator/prometheus} 带令牌 404（该端点当时无 handler，回落静态资源）。
 *
 * <p><b>Filter 在 DispatcherServlet 之前</b>，与 handler 由谁提供无关，所以这是
 * 唯一能真正兜住 actuator 的位置。
 *
 * <h3>放行规则（三条，都是最小必要）</h3>
 * <ol>
 *   <li>{@code /actuator/health} 及其子路径：放行。K8s 探针不带凭据，拦下来 Pod 永远
 *       NotReady——这是探针契约，不是漏洞；</li>
 *   <li>抓取令牌命中：放行。由 {@link ScrapeTokenGuard} 判定，**只对
 *       {@code /actuator/prometheus} 一个路径生效**（拿抓取钥匙读不到别的）；</li>
 *   <li>有效的 Sa-Token 会话：放行。给登录后的运维同学用 curl 排障留一条路。</li>
 * </ol>
 * 其余一律 401，响应体与 {@code GlobalExceptionHandler.handleNotLoginException}
 * 同码同文案（40101）——客户端不需要为 actuator 学第二套错误格式。
 *
 * <h3>为什么守卫用 ObjectProvider 注入（一处踩过的坑）</h3>
 * 切片测试（{@code @WebMvcTest}）的扫描白名单里<b>包含 {@code Filter}</b>，却不包含普通
 * {@code @Component}——也就是说本类会被装进切片上下文，而 {@link ScrapeTokenGuard} 不会。
 * 构造函数强依赖守卫时，切片上下文直接起不来：2026-10-15 的 CI 上，一个个
 * {@code @WebMvcTest} 全部报 "ApplicationContext failure threshold exceeded"
 * （AlertWebhook/Ticket/TeamMember/SessionMemory/HealthCheck 一起红，看起来像"到处都坏了"，
 * 实际只有一个根因）。改成 ObjectProvider 后：切片里守卫缺席 → 等价于"未配置令牌"，
 * 仍然 fail-closed，且只影响 {@code /actuator/**}（切片里本来也没有这些端点）。
 *
 * <h3>为什么不用 StpUtil.checkLogin() 判「登录」</h3>
 * 那个 API 依赖 Sa-Token 的请求上下文（{@code RequestContextHolder}），
 * 而 Filter 阶段 {@code DispatcherServlet} 还没初始化它，会抛
 * {@code SaTokenContextException}。这里改用 {@code StpUtil.getLoginIdByToken(token)}：
 * 只查会话存储、不碰请求上下文，语义等价（会话有效即放行）。
 * 会话存储异常（如 Redis 抖动）时**拒绝**并记日志——闸门故障必须 fail-closed。
 *
 * @author OpsBrain AI
 * @since 2026-10-15
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)   // 晚于 TraceIdFilter（日志要带 traceId），早于业务 Filter
public class ActuatorAccessFilter extends OncePerRequestFilter {

    private static final String ACTUATOR_PREFIX = "/actuator";

    /**
     * 用 {@code ObjectProvider} 而非直接注入（理由见类注释「一处踩过的坑」）：
     * 切片上下文里没有守卫，缺席时按「未配置令牌」处理即可，不该让上下文起不来。
     */
    private final org.springframework.beans.factory.ObjectProvider<ScrapeTokenGuard> scrapeTokenGuard;

    public ActuatorAccessFilter(org.springframework.beans.factory.ObjectProvider<ScrapeTokenGuard> scrapeTokenGuard) {
        this.scrapeTokenGuard = scrapeTokenGuard;
    }

    /** 只审 {@code /actuator/**}：其它路径一个字节都不碰（尤其别插手 SSE 与文件上传）。 */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = pathOf(request);
        return !(path.equals(ACTUATOR_PREFIX) || path.startsWith(ACTUATOR_PREFIX + "/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = pathOf(request);

        if (isHealthProbe(path)) {
            chain.doFilter(request, response);          // ① 探针契约
            return;
        }
        ScrapeTokenGuard guard = scrapeTokenGuard.getIfAvailable();
        if (guard != null && guard.allows(request)) {
            chain.doFilter(request, response);          // ② 抓取令牌（仅 prometheus 路径）
            return;
        }
        if (hasValidSession(request)) {
            chain.doFilter(request, response);          // ③ 登录会话
            return;
        }
        deny(response, path);
    }

    /** {@code /actuator/health} 与 {@code /actuator/health/liveness|readiness} 全放行。 */
    private static boolean isHealthProbe(String path) {
        return path.equals(ACTUATOR_PREFIX + "/health") || path.startsWith(ACTUATOR_PREFIX + "/health/");
    }

    /**
     * 是否带有效 Sa-Token 会话。
     * <p>取 token 名与取值口径（header 名 + cookie 名）都跟随 Sa-Token 配置，
     * 不硬编码 {@code satoken}——配置改名时这里不会变成第二个真相来源。
     */
    private boolean hasValidSession(HttpServletRequest request) {
        try {
            String tokenName = StpUtil.getTokenName();
            String tokenValue = request.getHeader(tokenName);
            if (tokenValue == null || tokenValue.isBlank()) {
                jakarta.servlet.http.Cookie[] cookies = request.getCookies();
                if (cookies != null) {
                    for (jakarta.servlet.http.Cookie cookie : cookies) {
                        if (tokenName.equals(cookie.getName())) {
                            tokenValue = cookie.getValue();
                            break;
                        }
                    }
                }
            }
            return tokenValue != null && !tokenValue.isBlank()
                    && StpUtil.getLoginIdByToken(tokenValue.trim()) != null;
        } catch (Exception e) {
            // 会话存储不可用 → 拒绝（fail-closed）并留痕：闸门坏掉不能变成敞开
            log.warn("🚧 [Actuator闸门] 会话校验失败，按拒绝处理（fail-closed）| path={} | cause={}: {}",
                    pathOf(request), e.getClass().getSimpleName(), e.getMessage());
            return false;
        }
    }

    /** 401，响应体与 {@code GlobalExceptionHandler} 的 NotLoginException 同码同文案。 */
    private void deny(HttpServletResponse response, String path) throws IOException {
        log.debug("🚧 [Actuator闸门] 拒绝匿名访问 | path={}", path);
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        String body = "{\"code\":" + BizError.NOT_LOGIN.code()
                + ",\"message\":\"" + BizError.NOT_LOGIN.defaultMessage()
                + "\",\"data\":null,\"success\":false}";
        response.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 路径取「requestURI 去掉 context-path」。
     * <p>Filter 阶段 servletPath 的可用性依赖容器实现（Tomcat 会先做映射、Jetty/非默认
     * servlet 映射下不一定），requestURI 则是规范保证的；这里不做容器假设。
     */
    private static String pathOf(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null) {
            return "";
        }
        String ctx = request.getContextPath();
        if (ctx != null && !ctx.isEmpty() && uri.startsWith(ctx)) {
            return uri.substring(ctx.length());
        }
        return uri;
    }
}
