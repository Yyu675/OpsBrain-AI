package com.devops.agent.common.web;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 客户端 IP 解析测试。
 *
 * <h3>保护的契约</h3>
 * {@code X-Forwarded-For} 是<b>客户端完全可控</b>的请求头。
 * 修复前项目有两处无条件信任它并取第一段：
 *
 * <ul>
 *   <li>{@link WebhookGuard} 用它做限流主体 → 每次请求换一个伪造值，
 *       限流键就换一个，ZSET 计数永远到不了阈值，<b>限流被完全绕过</b>。
 *       而 webhook 端点免鉴权、直接写库、可触发建单，限流是唯一防线。</li>
 *   <li>{@code OperationAuditInterceptor} 用它写审计的来源 IP →
 *       <b>审计证据可被伪造</b>，事后追溯会指向无辜的内网地址。</li>
 * </ul>
 *
 * <p>本类逐条验证「伪造 XFF 不生效」与「可信代理下取值正确」。</p>
 */
@DisplayName("ClientIpResolver")
class ClientIpResolverTest {

    /** 未配置任何受信任代理（默认部署形态） */
    private final ClientIpResolver noProxy = new ClientIpResolver("");

    /** 配置 10.x 网段为受信任代理（典型 K8s / Nginx 部署） */
    private final ClientIpResolver withProxy = new ClientIpResolver("10.,127.0.0.1");

    private HttpServletRequest request(String remoteAddr, String xff) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr(remoteAddr);
        if (xff != null) {
            req.addHeader("X-Forwarded-For", xff);
        }
        return req;
    }

    @Nested
    @DisplayName("直连部署（无受信任代理）")
    class DirectConnection {

        @Test
        @DisplayName("伪造的 X-Forwarded-For 一律忽略 —— 这是限流绕过的根因")
        void ignoresForgedXffWhenNoTrustedProxy() {
            String ip = noProxy.resolve(request("203.0.113.9", "1.2.3.4"));
            assertEquals("203.0.113.9", ip,
                    "未配置受信任代理时必须以 TCP 层 remoteAddr 为准，XFF 可被任意伪造");
        }

        @Test
        @DisplayName("攻击者每次换不同 XFF，解析结果始终不变 —— 限流键才稳定")
        void forgedXffDoesNotChangeIdentity() {
            String first = noProxy.resolve(request("203.0.113.9", "10.0.0.1"));
            String second = noProxy.resolve(request("203.0.113.9", "10.0.0.2"));
            String third = noProxy.resolve(request("203.0.113.9", "999.999.999.999"));

            assertEquals(first, second);
            assertEquals(second, third);
            assertEquals("203.0.113.9", first);
        }

        @Test
        @DisplayName("没有 XFF 时用 remoteAddr")
        void fallsBackToRemoteAddr() {
            assertEquals("198.51.100.20", noProxy.resolve(request("198.51.100.20", null)));
        }
    }

    @Nested
    @DisplayName("反向代理部署（remoteAddr 在信任列表内）")
    class BehindTrustedProxy {

        @Test
        @DisplayName("单跳：取 XFF 中的客户端地址")
        void singleHop() {
            assertEquals("198.51.100.7",
                    withProxy.resolve(request("10.0.0.5", "198.51.100.7")));
        }

        @Test
        @DisplayName("多跳：取最后一段 —— 左侧是客户端可伪造的部分")
        void takesLastHopNotFirst() {
            // 攻击者在请求里预置 "1.2.3.4"，代理把真实来源追加在右侧
            String ip = withProxy.resolve(request("10.0.0.5", "1.2.3.4, 198.51.100.7"));

            assertEquals("198.51.100.7", ip,
                    "取第一段会拿到攻击者伪造的值，必须取代理追加的最后一段");
        }

        @Test
        @DisplayName("XFF 含非法字符时回落 remoteAddr —— 该值会落库，不能带注入串")
        void rejectsInjectionAttempt() {
            assertEquals("10.0.0.5",
                    withProxy.resolve(request("10.0.0.5", "'; DROP TABLE sys_operation_audit--")));
        }

        @Test
        @DisplayName("XFF 超长时回落 —— 防止撑爆 varchar(45) 列")
        void rejectsOverlongValue() {
            assertEquals("10.0.0.5",
                    withProxy.resolve(request("10.0.0.5", "a".repeat(100))));
        }

        @Test
        @DisplayName("XFF 为空串时回落 remoteAddr")
        void emptyXffFallsBack() {
            assertEquals("10.0.0.5", withProxy.resolve(request("10.0.0.5", "")));
        }

        @Test
        @DisplayName("IPv6 地址被正常接受")
        void acceptsIpv6() {
            assertEquals("2001:db8::1",
                    withProxy.resolve(request("10.0.0.5", "2001:db8::1")));
        }

        @Test
        @DisplayName("不在信任列表的地址即便配了代理也不认 XFF")
        void untrustedSourceStillIgnoresXff() {
            // 203.0.113.9 不匹配 "10." 或 "127.0.0.1"
            assertEquals("203.0.113.9",
                    withProxy.resolve(request("203.0.113.9", "1.2.3.4")));
        }
    }

    @Nested
    @DisplayName("边界")
    class EdgeCases {

        @Test
        @DisplayName("remoteAddr 为 null 时返回 unknown 而非 null —— 该值会落库与写日志")
        void nullRemoteAddrReturnsUnknown() {
            MockHttpServletRequest req = new MockHttpServletRequest();
            req.setRemoteAddr(null);
            assertEquals("unknown", noProxy.resolve(req));
        }

        @Test
        @DisplayName("超长 remoteAddr 被截断到 45 字符（IPv6 上限）")
        void truncatesOverlongRemoteAddr() {
            String ip = noProxy.resolve(request("x".repeat(100), null));
            assertEquals(45, ip.length());
        }
    }
}
