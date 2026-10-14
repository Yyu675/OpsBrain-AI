package com.devops.agent.infrastructure.guard;

import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 指标抓取面（{@code /actuator/prometheus}）的令牌身份。
 *
 * <h3>为什么需要它（2026-10-15 监控对账的硬缺口）</h3>
 * {@code WebConfig} 对 {@code /actuator/**} 加了登录校验（health 豁免）——这是对的，
 * 内部拓扑与依赖可达性不该裸奔公网。但 <b>Prometheus 没有浏览器，不会登录</b>：
 * 抓取恒 401 的后果不是"少几个数字"，而是——
 * <ul>
 *   <li>{@code up{job="opsbrain"}} 恒 0 → 宕机规则 {@code OpsBrainDown} <b>常亮误报</b>（P0 级）；</li>
 *   <li>依赖该 job 的另外 6 条规则（业务水位 / JVM / 5xx / 送达失败）恒无数据 → <b>永不触发</b>。</li>
 * </ul>
 * 而这一切在平台内部毫无痕迹：告警表里只有一条永远 FIRING 的 OpsBrainDown，
 * 值班人会慢慢学会无视它。<b>监控自己失真，比没有监控更坏。</b>
 *
 * <h3>为什么是「令牌」而不是「放行 /actuator/**」</h3>
 * 放行让健康详情、bean 清单、依赖地址全部可匿名读取；令牌是"给机器一把最小的钥匙"。
 * 三条纪律：
 * <ol>
 *   <li><b>最小权限</b>：令牌只对 {@code /actuator/prometheus} 一个路径生效，
 *       其余 actuator 端点一律仍需登录（拿抓取令牌读不到别的）；</li>
 *   <li><b>默认关（fail-closed）</b>：没配令牌就是现在的行为（需登录 → 401），
 *       不会因为本类存在而意外开面；配置错也只错在"抓不到"，不会错在"敞开了"；</li>
 *   <li><b>常量时间比较</b>：避免按字符逐位比较泄漏令牌前缀。</li>
 * </ol>
 *
 * <h3>密钥从哪来</h3>
 * 优先 {@code devops.security.scrape-token-file}（建议：与 Prometheus 的
 * {@code authorization.credentials_file} 指向同一个文件，一处生成两侧共用），
 * 其次 {@code devops.security.scrape-token}（环境变量 {@code SCRAPE_TOKEN}）。
 * 文件每次请求重读——轮换不需要重启应用（Prometheus 侧也支持热加载 credential 文件）。
 *
 * @author OpsBrain AI
 * @since 2026-10-15
 */
@Slf4j
@Component
public class ScrapeTokenGuard {

    /** 令牌唯一生效路径（不含 context-path；比较时用 servletPath）。 */
    public static final String SCRAPE_PATH = "/actuator/prometheus";

    /** 抓取身份请求头（Prometheus 的 authorization 配置发的是前者）。 */
    private static final String AUTH_HEADER = "Authorization";
    private static final String ALT_HEADER = "X-Scrape-Token";
    private static final String BEARER_PREFIX = "bearer ";

    @Value("${devops.security.scrape-token:}")
    private String scrapeToken;

    @Value("${devops.security.scrape-token-file:}")
    private String scrapeTokenFile;

    /** 文件读取失败的告警去重（抓取每 15s 一次，缺文件不能刷屏）。 */
    private final AtomicReference<String> lastFileWarn = new AtomicReference<>();

    @PostConstruct
    void logState() {
        String configured = effectiveToken();
        if (configured.isEmpty()) {
            log.warn("📊 [Scrape] 抓取面未配置令牌（SCRAPE_TOKEN / SCRAPE_TOKEN_FILE 均为空）"
                    + " → {} 仍需登录，Prometheus 会 401：宕机规则恒亮、其余应用规则恒哑。"
                    + "修法见 docs/09-operations/部署运维手册.md「指标抓取面」", SCRAPE_PATH);
        } else {
            log.info("📊 [Scrape] 抓取面令牌认证已启用（path={}，来源={}）",
                    SCRAPE_PATH, scrapeTokenFile != null && !scrapeTokenFile.isBlank() ? "file" : "env");
        }
    }

    /**
     * 当前请求是否携带了正确的抓取令牌。
     *
     * @return true = 放行（调用方跳过登录校验）；false = 交给原有登录校验
     */
    public boolean allows(HttpServletRequest request) {
        if (request == null || !SCRAPE_PATH.equals(pathOf(request))) {
            return false;                       // 最小权限：令牌不覆盖其余 actuator 端点
        }
        String expected = effectiveToken();
        if (expected.isEmpty()) {
            return false;                       // 未配置 = 关
        }
        String presented = presentedToken(request);
        return presented != null && constantTimeEquals(expected, presented);
    }

    /** 令牌优先级：文件（可热轮换）> 内联配置/环境变量。 */
    private String effectiveToken() {
        if (scrapeTokenFile != null && !scrapeTokenFile.isBlank()) {
            String fromFile = readTokenFile(scrapeTokenFile.trim());
            if (!fromFile.isEmpty()) {
                return fromFile;
            }
            // 文件配了但读不到：**不静默回落到 env**——否则轮换期间会拿旧令牌放行，
            // 表现为"旧钥匙还能开锁"，这在安全上比抓不到指标严重得多。
            return "";
        }
        return scrapeToken == null ? "" : scrapeToken.trim();
    }

    private String readTokenFile(String path) {
        try {
            return Files.readString(Path.of(path), StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            // 静默 catch 契约：吞可以，留线索是底线——按「文件+原因」去重，避免刷屏
            String key = path + "|" + e.getClass().getSimpleName() + "|" + e.getMessage();
            if (!key.equals(lastFileWarn.getAndSet(key))) {
                log.warn("📊 [Scrape] 令牌文件读取失败，抓取面按未配置处理（fail-closed）| file={} | cause={}: {}",
                        path, e.getClass().getSimpleName(), e.getMessage());
            }
            return "";
        }
    }

    /**
     * 从请求头取令牌。支持 {@code Authorization: Bearer <token>}（Prometheus 的标准写法）
     * 与 {@code X-Scrape-Token: <token>}（curl 自测方便）。
     * 不接受 query 参数——URL 会进访问日志与浏览器历史，令牌在 URL 里等于泄漏。
     */
    private String presentedToken(HttpServletRequest request) {
        String auth = request.getHeader(AUTH_HEADER);
        if (auth != null && !auth.isBlank()) {
            String trimmed = auth.trim();
            if (trimmed.length() > BEARER_PREFIX.length()
                    && trimmed.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
                return trimmed.substring(BEARER_PREFIX.length()).trim();
            }
            return null;                        // 有 Authorization 但不是 Bearer：不认（避免把 Basic 当令牌比）
        }
        String alt = request.getHeader(ALT_HEADER);
        return alt == null || alt.isBlank() ? null : alt.trim();
    }

    /** 路径取 servletPath，回落到「requestURI 去掉 context-path」——两种部署形态都对。 */
    private String pathOf(HttpServletRequest request) {
        String servletPath = request.getServletPath();
        if (servletPath != null && !servletPath.isEmpty()) {
            return servletPath;
        }
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

    /** 常量时间比较（长度不同也走满长度，避免长度侧信道）。 */
    private static boolean constantTimeEquals(String expected, String presented) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8));
    }
}
