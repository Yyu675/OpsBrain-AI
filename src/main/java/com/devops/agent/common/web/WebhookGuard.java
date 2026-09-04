package com.devops.agent.common.web;

import com.devops.agent.common.exception.WebhookRejectedException;
import com.devops.agent.infrastructure.cache.SlidingWindowRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 告警 Webhook 端点防护（A6 修复）。
 *
 * <h3>背景</h3>
 * {@code /api/v1/alerts/webhook} 在 {@code WebConfig} 的鉴权白名单里
 * （Alertmanager 无法携带 Sa-Token），且它<b>直接写库、可触发自动建单</b>。
 * 修复前该端点完全无保护，意味着：
 * <ul>
 *   <li>任何人只要能访问到服务，就能无限灌入伪造告警并触发建单；</li>
 *   <li>一次网络分区导致的告警风暴（真实场景可达上万条）会直接压垮数据库。</li>
 * </ul>
 *
 * <h3>两道防线</h3>
 * <ol>
 *   <li><b>共享密钥</b>：配置 {@code devops.alert.webhook.secret} 后，
 *       请求必须携带匹配的 {@code X-Webhook-Token}。用<b>常量时间比较</b>，
 *       避免按字符早退的比较被用于逐位猜测密钥（时序侧信道）。</li>
 *   <li><b>限流</b>：按来源 IP 滑动窗口限流，挡住告警风暴与暴力尝试。</li>
 * </ol>
 *
 * <h3>默认行为的取舍</h3>
 * 密钥默认<b>为空 = 不校验</b>，只打一次告警日志。这是刻意的向后兼容：
 * 现有 {@code monitoring/alertmanager.yml} 尚未配置该请求头，
 * 若默认强制校验会让本地开发与既有部署的告警链路立刻中断。
 * <b>生产部署必须设置 {@code ALERT_WEBHOOK_SECRET}</b>，
 * 相关说明已写入 .env.example 与 AGENTS.md。
 *
 * @author OpsBrain AI
 * @since 2026-08-24
 */
@Slf4j
@Component
public class WebhookGuard {

    public static final String TOKEN_HEADER = "X-Webhook-Token";

    /** 共享密钥。为空则跳过校验（向后兼容，生产必须配置） */
    @Value("${devops.alert.webhook.secret:}")
    private String secret;

    /** 限流窗口内允许的最大请求数；<= 0 关闭限流 */
    @Value("${devops.alert.webhook.rate-limit:300}")
    private int rateLimit;

    /** 限流窗口（毫秒） */
    @Value("${devops.alert.webhook.rate-window-ms:60000}")
    private long rateWindowMs;

    private final SlidingWindowRateLimiter rateLimiter;

    private final ClientIpResolver clientIpResolver;

    public WebhookGuard(SlidingWindowRateLimiter rateLimiter, ClientIpResolver clientIpResolver) {
        this.rateLimiter = rateLimiter;
        this.clientIpResolver = clientIpResolver;
    }

    /**
     * 校验一次 webhook 请求。
     *
     * @throws WebhookRejectedException 校验不通过
     */
    public void verify(HttpServletRequest request) {
        String clientIp = resolveClientIp(request);

        // 1) 限流（先于密钥校验：密钥错误的暴力尝试同样要被限流挡住）
        if (!rateLimiter.tryAcquire("webhook", clientIp, rateLimit, rateWindowMs)) {
            log.warn("🚫 [WebhookGuard] 触发限流 | ip={} | limit={}/{}ms", clientIp, rateLimit, rateWindowMs);
            // 429 + Retry-After：让 Alertmanager 退避重投，告警最终不丢。
            // 若返回 200 静默丢弃，风暴期的告警会永久消失——对运维平台不可接受。
            throw WebhookRejectedException.rateLimited((int) Math.ceil(rateWindowMs / 1000.0));
        }

        // 2) 共享密钥
        if (secret == null || secret.isBlank()) {
            // 只在首次提醒，避免每条告警刷屏
            if (WARNED.compareAndSet(false, true)) {
                log.warn("⚠️ [WebhookGuard] 未配置 devops.alert.webhook.secret，"
                        + "告警端点当前无鉴权。生产环境请务必设置 ALERT_WEBHOOK_SECRET。");
            }
            return;
        }

        String provided = request.getHeader(TOKEN_HEADER);
        if (provided == null || !constantTimeEquals(provided, secret)) {
            log.warn("🚫 [WebhookGuard] 密钥校验失败 | ip={} | hasHeader={}", clientIp, provided != null);
            throw WebhookRejectedException.unauthorized();
        }
    }

    private static final java.util.concurrent.atomic.AtomicBoolean WARNED =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * 常量时间字符串比较。
     * <p>{@code String.equals} 在首个不同字符处就返回，攻击者可通过测量响应耗时
     * 逐位推断密钥。{@link MessageDigest#isEqual} 不early-return。</p>
     */
    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 解析真实客户端 IP，委托给 {@link ClientIpResolver}。
     *
     * <p>原实现无条件取 X-Forwarded-For 的第一段，并把它当作限流主体。
     * 那一段恰恰是<b>客户端可以随意伪造</b>的：每次请求换一个值，
     * 限流键就换一个，ZSET 计数永远到不了阈值——<b>限流被完全绕过</b>。
     * 而本端点免鉴权且直接写库、可触发建单，限流是它唯一的防线。
     *
     * <p>原注释说「不构成鉴权绕过（鉴权靠密钥）」——这点没错，
     * 但它低估了后果：密钥泄露或内部误用时，限流本该是第二道闸门，
     * 而当时那道闸门形同虚设。</p>
     */
    private String resolveClientIp(HttpServletRequest request) {
        return clientIpResolver.resolve(request);
    }
}
