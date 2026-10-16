package com.devops.agent.infrastructure;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.SpringProperties;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.expression.spel.SpelCompilerMode;
import org.springframework.expression.spel.SpelEvaluationException;
import org.springframework.expression.spel.SpelParserConfiguration;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.resource.ContentVersionStrategy;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.servlet.resource.PathResourceResolver;
import org.springframework.web.servlet.resource.ResourceHttpRequestHandler;
import org.springframework.web.servlet.resource.ResourceResolverChain;
import org.springframework.web.servlet.resource.VersionResourceResolver;
import org.springframework.web.servlet.resource.VersionStrategy;
import org.springframework.web.util.JavaScriptUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 实际Framework安全/兼容行为；全用6.2.17已有API，使旧版判红来自断言而非编译错误。
 * 不提交大规模DoS载荷，不以计时/循环比较证明修复，不声称线上可达。
 */
@Timeout(15)
class SpringFrameworkSecurityCompatibilityTest {
    @TempDir Path assets;
    private static final String BUDGET = "spring.expression.maxOperations";
    // 上游76a36df文档的十次运算健康表达式，无高代价操作；旧版也会正常结束。
    private static final String EXPRESSION = "('foo' + 'bar').length >= 6 && {1, 1 + 1, 3}.contains(3)";

    @Test
    void templateLiteralDelimitersAreEscaped() {
        assertEquals("\\u0060\\u0024{alert(1)}\\u0060", JavaScriptUtils.javaScriptEscape("`${alert(1)}`"),
                "template literal delimiters must be escaped");
    }

    @Test
    void ordinaryScriptTextRetainsEscapingContract() {
        assertEquals("hello\\n\\\"Ops\\\"\\/\\u003C\\u003E\\u2028",
                JavaScriptUtils.javaScriptEscape("hello\n\"Ops\"/<>\u2028"));
    }

    @Test
    void contentVersionRemovalPreservesParentDirectory() {
        var strategy = new ContentVersionStrategy();
        assertEquals("font-sha/font.css", strategy.removeVersion("font-sha/font-sha.css", "sha"),
                "only the last version suffix may be removed");
        assertEquals("font/font.css", strategy.removeVersion("font/font.css", "sha"));
    }

    @Test
    void unsafeVersionedPathNeverReachesResourceLookup() {
        // SPI是输入边界，不mock被测resolver或复制其安全检查；无实际敏感文件访问。
        var strategy = mock(VersionStrategy.class);
        when(strategy.extractVersion("app-v1.css")).thenReturn("v1");
        when(strategy.removeVersion("app-v1.css", "v1")).thenReturn("../secret.css");
        when(strategy.getResourceVersion(any(Resource.class))).thenReturn("v1");
        var chain = mock(ResourceResolverChain.class);
        when(chain.resolveResource(isNull(), eq("../secret.css"), anyList()))
                .thenReturn(new ByteArrayResource(new byte[] {1}));
        var resolver = new VersionResourceResolver().addVersionStrategy(strategy, "/**");
        assertNull(resolver.resolveResource(null, "app-v1.css", List.of(), chain),
                "unsafe derived path must be rejected");
        verify(chain).resolveResource(null, "app-v1.css", List.of());
        verifyNoMoreInteractions(chain);
    }

    @Test
    void spelOperationBudgetRejectsAtExactBoundary() {
        withBudget(10, () -> {
            var expression = parser().parseExpression(EXPRESSION);
            var error = assertThrows(SpelEvaluationException.class, () -> expression.getValue(Boolean.class),
                    "ten operations must hit the configured budget");
            assertEquals("MAX_OPERATIONS_EXCEEDED", error.getMessageCode().name());
            assertArrayEquals(new Object[] {10}, error.getInserts());
        });
    }

    @Test
    void healthySpelEvaluationStillWorksAboveBoundary() {
        withBudget(11, () -> {
            var expression = parser().parseExpression(EXPRESSION);
            assertEquals(Boolean.TRUE, expression.getValue(Boolean.class));
            assertEquals(Boolean.TRUE, expression.getValue(Boolean.class), "each evaluation has a fresh budget");
        });
    }

    @Test
    void actualVersionedStaticResourceHandlerServesBytesAndRejectsWrongDigest() throws Exception {
        Files.createDirectories(assets.resolve("css"));
        Files.writeString(assets.resolve("css/app.css"), "body{}");
        var strategy = new ContentVersionStrategy();
        String version = strategy.getResourceVersion(new FileSystemResource(assets.resolve("css/app.css")));
        assertEquals("aa676972bbd2b68e94ef8e91e81d20be", version);
        var handler = new ResourceHttpRequestHandler();
        handler.setLocations(List.of(new FileSystemResource(assets + "/")));
        handler.setResourceResolvers(List.of(new VersionResourceResolver().addContentVersionStrategy("/**"),
                new PathResourceResolver()));
        handler.afterPropertiesSet();
        var response = new MockHttpServletResponse();
        handler.handleRequest(request("css/app-" + version + ".css"), response);
        assertEquals(200, response.getStatus());
        assertEquals("body{}", response.getContentAsString());
        assertTrue(response.getContentType().startsWith("text/css"));
        var error = assertThrows(NoResourceFoundException.class,
                () -> handler.handleRequest(request("css/app-wrong.css"), new MockHttpServletResponse()));
        assertEquals(404, error.getStatusCode().value());
    }

    private static MockHttpServletRequest request(String path) {
        var request = new MockHttpServletRequest("GET", "/static/" + path);
        request.setAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE, path);
        return request;
    }

    private static SpelExpressionParser parser() {
        return new SpelExpressionParser(new SpelParserConfiguration(SpelCompilerMode.OFF, null, false, false, 0, 100));
    }

    private static void withBudget(int maximum, Runnable assertion) {
        String previous = SpringProperties.getProperty(BUDGET);
        try {
            SpringProperties.setProperty(BUDGET, String.valueOf(maximum));
            assertion.run();
        } finally {
            SpringProperties.setProperty(BUDGET, previous);
        }
    }
}
