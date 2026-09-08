package com.devops.agent.domain.tools;

import com.devops.agent.domain.evidence.LogsEvidenceCollector;
import com.devops.agent.domain.evidence.MetricsQueryCatalog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/** S1-3 工具层边界：签名就位 + 白名单拦截（与 S1-2 同谱系，零 Spring）。 */
class LogsToolBoundaryTest {

    @Test
    @DisplayName("queryServiceLogsInternal 签名就位（反射装配不再编码错误）")
    void internalMethodSignatureExists() throws Exception {
        var m = DevOpsTools.class.getDeclaredMethod("queryServiceLogsInternal",
                String.class, String.class, String.class, String.class);
        assertThat(m).isNotNull();
    }

    @Test
    @DisplayName("非法服务名 → 参数错误（注入面同源白名单）")
    void invalidServiceRejected() {
        DevOpsTools tools = new DevOpsTools();
        ReflectionTestUtils.setField(tools, "metricsQueryCatalog", new MetricsQueryCatalog());
        String out = tools.queryServiceLogsInternal("svc'; DROP TABLE logs; --", "30m", "ERROR", null);
        assertThat(out).startsWith("参数错误");
    }

    @Test
    @DisplayName("三个取证工具 @Tool 注解齐备（白名单装配面）")
    void toolAnnotationsPresent() throws Exception {
        var t1 = DevOpsTools.class.getDeclaredMethod("queryServiceMetrics",
                String.class, String.class, String.class).getAnnotation(dev.langchain4j.agent.tool.Tool.class);
        var t2 = DevOpsTools.class.getDeclaredMethod("queryRecentChanges",
                String.class, String.class).getAnnotation(dev.langchain4j.agent.tool.Tool.class);
        var t3 = DevOpsTools.class.getDeclaredMethod("queryServiceLogs",
                String.class, String.class, String.class, String.class).getAnnotation(dev.langchain4j.agent.tool.Tool.class);
        assertThat(t1).isNotNull();
        assertThat(t2).isNotNull();
        assertThat(t3).isNotNull();
        // ToolMeta 同点校验（治理契约在场才生效）
        assertThat(DevOpsTools.class.getDeclaredMethod("queryServiceLogs",
                String.class, String.class, String.class, String.class)
                .getAnnotation(ToolMeta.class).timeoutMs()).isGreaterThan(0);
    }
}
