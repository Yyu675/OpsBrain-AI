package com.devops.agent.infrastructure.guard;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ScrapeTokenGuard} 的放行判定。
 *
 * <h3>为什么这段判定值得一个专门测试</h3>
 * 它守的是"指标抓取面"这扇门，而它的两种错法**都不会让任何东西变红**：
 * <ul>
 *   <li><b>该放不放</b>（令牌配了但判定有 bug）→ Prometheus 401 → 面板全空、
 *       {@code up=0} 让宕机规则常亮误报——看着像"监控坏了"，其实是判定写错了；</li>
 *   <li><b>不该放却放</b>（路径判定太宽 / 失败时静默回落到别的凭据）→
 *       内部拓扑、依赖可达性匿名可读。这是安全缺口，且不会有任何异常日志。</li>
 * </ul>
 * 因此这里钉的不是"功能"，而是四条边界：<b>只对抓取面生效、默认关、配错不回落、
 * 令牌不落 URL</b>。
 */
class ScrapeTokenGuardTest {

    private static final String TOKEN = "t0ken-0123456789abcdef";

    /** 构造一个只配了内联令牌的守卫（不碰文件）。 */
    private ScrapeTokenGuard inlineTokenGuard(String token) {
        ScrapeTokenGuard guard = new ScrapeTokenGuard();
        ReflectionTestUtils.setField(guard, "scrapeToken", token == null ? "" : token);
        ReflectionTestUtils.setField(guard, "scrapeTokenFile", "");
        return guard;
    }

    private MockHttpServletRequest request(String path) {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", path);
        req.setServletPath(path);
        return req;
    }

    @Nested
    @DisplayName("最小权限：令牌只对抓取面生效")
    class PathScope {

        @Test
        @DisplayName("令牌正确 → 放行 /actuator/prometheus")
        void allowsScrapePath() {
            MockHttpServletRequest req = request(ScrapeTokenGuard.SCRAPE_PATH);
            req.addHeader("Authorization", "Bearer " + TOKEN);
            assertTrue(inlineTokenGuard(TOKEN).allows(req));
        }

        @Test
        @DisplayName("同一个令牌对别的 actuator 端点**不放行**（拿抓取钥匙读不到 health 详情/bean）")
        void otherActuatorPathsStillProtected() {
            ScrapeTokenGuard guard = inlineTokenGuard(TOKEN);
            for (String path : new String[]{"/actuator/info", "/actuator/health", "/actuator/env",
                    "/actuator/beans", "/actuator/prometheus/extra"}) {
                MockHttpServletRequest req = request(path);
                req.addHeader("Authorization", "Bearer " + TOKEN);
                assertFalse(guard.allows(req), path + " 不应被抓取令牌放行");
            }
        }

        @Test
        @DisplayName("业务端点不受影响（令牌与 /api/** 无关）")
        void apiPathsUnaffected() {
            MockHttpServletRequest req = request("/api/v1/alerts");
            req.addHeader("Authorization", "Bearer " + TOKEN);
            assertFalse(inlineTokenGuard(TOKEN).allows(req));
        }
    }

    @Nested
    @DisplayName("默认关 + 配错不回落（fail-closed）")
    class FailClosed {

        @Test
        @DisplayName("未配置令牌 → 一律不放行（行为与加本功能之前一致：仍需登录）")
        void unconfiguredDeniesEverything() {
            ScrapeTokenGuard guard = inlineTokenGuard("");
            MockHttpServletRequest req = request(ScrapeTokenGuard.SCRAPE_PATH);
            req.addHeader("Authorization", "Bearer " + TOKEN);
            assertFalse(guard.allows(req));
            assertFalse(inlineTokenGuard(null).allows(request(ScrapeTokenGuard.SCRAPE_PATH)));
        }

        @Test
        @DisplayName("令牌不对 → 不放行（交回登录校验 → 401）")
        void wrongTokenDenied() {
            MockHttpServletRequest req = request(ScrapeTokenGuard.SCRAPE_PATH);
            req.addHeader("Authorization", "Bearer wrong-token");
            assertFalse(inlineTokenGuard(TOKEN).allows(req));
        }

        @Test
        @DisplayName("配了令牌文件但文件读不到 → **不回落**到内联令牌（防轮换期旧钥匙还能开门）")
        void missingFileDoesNotFallBackToInline(@TempDir Path dir) {
            ScrapeTokenGuard guard = new ScrapeTokenGuard();
            ReflectionTestUtils.setField(guard, "scrapeToken", TOKEN);          // 内联配了正确的
            ReflectionTestUtils.setField(guard, "scrapeTokenFile",
                    dir.resolve("missing-token-file").toString());                          // 文件却读不到
            MockHttpServletRequest req = request(ScrapeTokenGuard.SCRAPE_PATH);
            req.addHeader("Authorization", "Bearer " + TOKEN);
            assertFalse(guard.allows(req),
                    "文件优先且读不到时必须拒绝——回落到旧令牌会让轮换失效");
        }
    }

    @Nested
    @DisplayName("令牌形态与来源")
    class TokenForms {

        @Test
        @DisplayName("Authorization: Bearer 大小写不敏感；也接受 X-Scrape-Token")
        void acceptsBothHeaderForms() {
            ScrapeTokenGuard guard = inlineTokenGuard(TOKEN);
            MockHttpServletRequest lower = request(ScrapeTokenGuard.SCRAPE_PATH);
            lower.addHeader("Authorization", "bearer " + TOKEN);
            assertTrue(guard.allows(lower));

            MockHttpServletRequest alt = request(ScrapeTokenGuard.SCRAPE_PATH);
            alt.addHeader("X-Scrape-Token", TOKEN);
            assertTrue(guard.allows(alt));
        }

        @Test
        @DisplayName("非 Bearer 的 Authorization（如 Basic）不当作令牌比较")
        void nonBearerAuthorizationIgnored() {
            MockHttpServletRequest req = request(ScrapeTokenGuard.SCRAPE_PATH);
            req.addHeader("Authorization", "Basic " + TOKEN);
            assertFalse(inlineTokenGuard(TOKEN).allows(req));
        }

        @Test
        @DisplayName("令牌放 URL 查询参数**不生效**（URL 会进访问日志/浏览器历史，等于泄漏）")
        void queryParameterTokenRejected() {
            MockHttpServletRequest req = request(ScrapeTokenGuard.SCRAPE_PATH);
            req.setQueryString("token=" + TOKEN);
            req.addParameter("token", TOKEN);
            assertFalse(inlineTokenGuard(TOKEN).allows(req));
        }
    }

    @Nested
    @DisplayName("文件令牌：轮换不需要重启")
    class FileToken {

        @Test
        @DisplayName("令牌文件生效；改写文件后新令牌立即可用、旧令牌立即失效（应用侧无需重启）")
        void rotationTakesEffectImmediately(@TempDir Path dir) throws Exception {
            Path file = dir.resolve("scrape_token");
            Files.writeString(file, "token-v1\n", StandardCharsets.UTF_8);

            ScrapeTokenGuard guard = new ScrapeTokenGuard();
            ReflectionTestUtils.setField(guard, "scrapeToken", "");
            ReflectionTestUtils.setField(guard, "scrapeTokenFile", file.toString());

            MockHttpServletRequest v1 = request(ScrapeTokenGuard.SCRAPE_PATH);
            v1.addHeader("Authorization", "Bearer token-v1");
            assertTrue(guard.allows(v1), "文件里的令牌应生效（末尾换行需被裁掉）");

            Files.writeString(file, "token-v2\n", StandardCharsets.UTF_8);

            MockHttpServletRequest newToken = request(ScrapeTokenGuard.SCRAPE_PATH);
            newToken.addHeader("Authorization", "Bearer token-v2");
            assertTrue(guard.allows(newToken), "轮换后新令牌应立即生效（每次请求重读文件）");

            MockHttpServletRequest oldToken = request(ScrapeTokenGuard.SCRAPE_PATH);
            oldToken.addHeader("Authorization", "Bearer token-v1");
            assertFalse(guard.allows(oldToken), "轮换后旧令牌应立即失效");
        }
    }

    @Nested
    @DisplayName("context-path 形态兼容")
    class ContextPathForms {

        @Test
        @DisplayName("部署在 /ai 下（servletPath 为空）也能识别抓取面")
        void contextPathFallback(@TempDir Path dir) {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", "/ai/actuator/prometheus");
            req.setContextPath("/ai");
            req.setRequestURI("/ai/actuator/prometheus");
            req.setServletPath("");                     // 兜底分支：用 URI 去掉 context-path
            req.addHeader("Authorization", "Bearer " + TOKEN);
            assertTrue(inlineTokenGuard(TOKEN).allows(req));
        }
    }
}
