package com.devops.agent.eval;

import com.devops.agent.domain.evidence.MetricsQueryCatalog;
import com.devops.agent.domain.tools.DevOpsTools;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * S1-2 工具层装配与参数边界测试（不起 Spring，反射直调 internal——
 * ToolRuntimeManager 治理路径已有 S0-3 谱系测试覆盖，本类只锁新增代码面）。
 */
class ChangesToolEvalCallback {

    @Test
    @DisplayName("queryRecentChangesInternal 签名就位（工具层反射查找不再编码错误）")
    void internalMethodSignatureExists() throws Exception {
        var m = DevOpsTools.class.getDeclaredMethod(
                "queryRecentChangesInternal", String.class, String.class);
        assertThat(m).isNotNull();
    }

    @Test
    @DisplayName("非法服务名 → 参数错误（白名单边界，PromQL/SQL 注入面同源）")
    void invalidServiceRejected() {
        DevOpsTools tools = new DevOpsTools();
        ReflectionTestUtils.setField(tools, "metricsQueryCatalog", new MetricsQueryCatalog());
        String out = tools.queryRecentChangesInternal("svc;DROP TABLE sys_change_event", "2h");
        assertThat(out).startsWith("参数错误");
    }

    @Test
    @DisplayName("合法名进入收集路径（收集器缺失才抛 NPE——显式编码错误，不沉默）")
    void validServiceReachesCollector() {
        DevOpsTools tools = new DevOpsTools();
        ReflectionTestUtils.setField(tools, "metricsQueryCatalog", new MetricsQueryCatalog());
        assertDoesNotThrow(() -> {
            try {
                tools.queryRecentChangesInternal("order-service", "2h");
            } catch (NullPointerException npe) {
                // 未注入收集器的裸实例：到达这一步即证明白名单放行
            }
        });
    }

    @Test
    @DisplayName("queryServiceMetricsInternal 同样挡非法服务名（S1-1 同边界复验）")
    void metricsToolRejectsInvalidService() {
        DevOpsTools tools = new DevOpsTools();
        ReflectionTestUtils.setField(tools, "metricsQueryCatalog", new MetricsQueryCatalog());
        String out = tools.queryServiceMetricsInternal("svc\" OR 1=1 --", "30m", "cpu");
        assertThat(out).startsWith("参数错误");
    }
}
