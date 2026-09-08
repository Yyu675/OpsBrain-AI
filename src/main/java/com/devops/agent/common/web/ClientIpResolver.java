package com.devops.agent.common.web;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 客户端真实 IP 解析（统一入口）。
 *
 * <h3>要解决什么</h3>
 * 项目里有两处各自读 {@code X-Forwarded-For}，且都无条件信任它：
 * <ul>
 *   <li>{@link WebhookGuard#verify} —— 用它做限流主体；</li>
 *   <li>{@code OperationAuditInterceptor} —— 用它写审计记录的来源 IP。</li>
 * </ul>
 *
 * <p><b>而这个头是客户端完全可控的。</b>后果不是「粒度不准」这么轻：
 *
 * <ol>
 *   <li><b>限流被彻底绕过</b>：每次请求换一个伪造的 XFF，限流键就换一个，
 *       ZSET 计数永远到不了阈值。免鉴权的 webhook 端点直接写库并可触发建单，
 *       限流是它唯一的防线，绕过等于没有。</li>
 *   <li><b>审计证据被污染</b>：攻击者可以把来源 IP 写成任意值，
 *       事后追溯会指向无辜的内网地址。审计表恰恰是最不能被伪造的地方——
 *       它存在的意义就是「事后能查」。</li>
 * </ol>
 *
 * <h3>正确做法</h3>
 * XFF 只有在<b>请求确实来自受信任的反向代理</b>时才可信。
 * 判据是 {@code getRemoteAddr()}（TCP 层对端地址，无法伪造）
 * 落在信任列表内；否则一律以 remoteAddr 为准。
 *
 * <p>信任列表通过 {@code devops.security.trusted-proxies} 配置。
 * <b>默认为空</b>——即默认不信任任何 XFF。这是刻意的：
 * 未部署代理时直接可用且安全；部署了代理再显式配置，
 * 比「默认信任、忘了收紧」安全得多。</p>
 *
 * <h3>为什么取最后一段而非第一段</h3>
 * XFF 的格式是 {@code 客户端, 代理1, 代理2...}，<b>左侧由客户端伪造、
 * 右侧由每一跳代理追加</b>。取第一段拿到的正是攻击者可写的部分。
 * 在只有一层可信代理的部署下，真实来源是<b>最后一段</b>
 * （由该代理写入，攻击者够不着）。
 *
 * @author OpsBrain AI
 * @since 2026-08-24
 */
@Slf4j
@Component
public class ClientIpResolver {

    /** IPv6 最长文本形式 45 字符，超过必是垃圾数据 */
    private static final int MAX_IP_LENGTH = 45;

    private static final String UNKNOWN = "unknown";

    /**
     * 受信任的反向代理地址列表（逗号分隔）。
     *
     * <p>默认空 = 不信任任何 XFF。部署在 Nginx / K8s Ingress 之后时，
     * 配置为代理的出口地址，例如：
     * {@code devops.security.trusted-proxies=127.0.0.1,10.0.0.0/8}
     * （当前实现只做前缀匹配，CIDR 写法按字符串前缀生效，
     * 如 {@code 10.} 可覆盖整个 10 段）。</p>
     */
    private final Set<String> trustedProxies;

    public ClientIpResolver(
            @Value("${devops.security.trusted-proxies:}") String trustedProxiesConfig) {
        this.trustedProxies = parseTrusted(trustedProxiesConfig);
        if (trustedProxies.isEmpty()) {
            log.info("🔒 [ClientIp] 未配置受信任代理，X-Forwarded-For 一律忽略（直连部署下这是正确的）");
        } else {
            log.info("🔒 [ClientIp] 受信任代理前缀：{}", String.join(", ", trustedProxies));
        }
    }

    /**
     * 解析客户端真实 IP。
     *
     * @return 永不为 null；无法判定时返回 {@code "unknown"}
     */
    public String resolve(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();

        // 直连（或来自不受信任的地址）：XFF 全部忽略。
        // 这条分支覆盖了「攻击者直接打服务端口并伪造 XFF」的情形。
        if (!isTrustedProxy(remoteAddr)) {
            return sanitize(remoteAddr);
        }

        String xff = request.getHeader("X-Forwarded-For");
        if (xff == null || xff.isBlank()) {
            return sanitize(remoteAddr);
        }

        /*
         * 取最后一段：它由紧邻的可信代理写入，客户端够不着。
         * 取第一段（常见写法）拿到的恰好是客户端可伪造的部分。
         */
        String[] hops = xff.split(",");
        for (int i = hops.length - 1; i >= 0; i--) {
            String candidate = hops[i].trim();
            if (isPlausibleIp(candidate)) {
                return candidate;
            }
        }
        return sanitize(remoteAddr);
    }

    // ==================== 内部 ====================

    private boolean isTrustedProxy(String remoteAddr) {
        if (remoteAddr == null || trustedProxies.isEmpty()) {
            return false;
        }
        for (String prefix : trustedProxies) {
            if (remoteAddr.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 粗校验：只接受由 IP 合法字符组成且长度合理的串。
     *
     * <p>不做完整 IP 解析——这里的目的是<b>拦住注入与超长垃圾</b>
     * （该值会落库、会进日志），而非精确校验地址格式。
     * 逗号已在上层按分隔符切开，此处若仍含逗号说明数据异常。</p>
     */
    private boolean isPlausibleIp(String s) {
        if (s == null || s.isEmpty() || s.length() > MAX_IP_LENGTH) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= '0' && c <= '9')
                    || (c >= 'a' && c <= 'f')
                    || (c >= 'A' && c <= 'F')
                    || c == '.' || c == ':' || c == '%';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    private String sanitize(String addr) {
        if (addr == null || addr.isBlank()) {
            return UNKNOWN;
        }
        return addr.length() <= MAX_IP_LENGTH ? addr : addr.substring(0, MAX_IP_LENGTH);
    }

    private static Set<String> parseTrusted(String config) {
        if (config == null || config.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(config.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }
}
