package com.devops.agent.controller;

import com.devops.agent.common.exception.GlobalExceptionHandler;
import com.devops.agent.infrastructure.metrics.MetricsUnavailableException;
import com.devops.agent.infrastructure.metrics.PromQuery;
import com.devops.agent.infrastructure.metrics.PrometheusClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link MetricsController} HTTP 契约测试（L2 · 阶段 B）。
 *
 * <h3>这个 Controller 为什么必须有契约测试</h3>
 * 它是<b>唯一一个对外代理第三方系统</b>的端点组：请求打到 Prometheus，
 * 结果再翻译成本项目的结构。这条链路上有三类只在运行期才暴露的风险：
 * <ol>
 *   <li><b>数据源挂了要如实说</b>——{@link MetricsUnavailableException}
 *       必须映射成 50020/503。如果退化成 500，前端就无法区分
 *       「Prometheus 没起」和「OpsBrain 自己崩了」，
 *       用户会对着「服务内部异常」反复刷新，而真正该做的是去接入管理页看数据源；</li>
 *   <li><b>null 不能伪装成 0</b>——Prometheus 用 NaN 表示「这个目标现在没数据」。
 *       序列化成 0 会让「取不到 CPU」变成「CPU 0%」，
 *       这是两件完全不同的事，后者会让扩容决策彻底跑偏；</li>
 *   <li><b>参数钳制要如实回报</b>——用户传 {@code hours=99999} 时后端会夹到 31 天，
 *       响应必须回传生效值，否则用户以为「数据缺失」而不是「窗口被限制」。</li>
 * </ol>
 *
 * <h3>还守着一条安全边界</h3>
 * 目录只暴露<b>指标 ID</b>，不接受任意 PromQL。所以未知 ID 必须被拒（40001），
 * 不能回退到某个默认查询——回退会画出一张「有数据但是错的」图表，
 * 比空图表危险得多。这里用一条用例把它钉住。
 *
 * <p>切片装配沿用 {@code TicketControllerWebTest} 已验证的做法：
 * 排除 {@code WebConfig}（切片里 SaInterceptor 会让所有请求 401）与
 * {@code OperationAuditInterceptor}（依赖 {@code @Repository}，
 * 切片不实例化 JDBC 层，会让整个上下文启动失败）；
 * {@code TraceIdFilter} 无依赖且 traceId 属响应契约，显式装回。</p>
 */
@ExtendWith(SpringExtension.class)
@WebMvcTest(
        controllers = MetricsController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = {
                        com.devops.agent.controller.config.WebConfig.class,
                        com.devops.agent.common.audit.OperationAuditInterceptor.class
                }),
        excludeAutoConfiguration = {
                org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration.class
        })
@AutoConfigureMockMvc(addFilters = false)
@Import({GlobalExceptionHandler.class, com.devops.agent.common.web.TraceIdFilter.class})
class MetricsControllerWebTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private com.devops.agent.common.web.TraceIdFilter traceIdFilter;

    @Autowired
    private org.springframework.web.context.WebApplicationContext context;

    @MockitoBean
    private PrometheusClient prometheus;

    /** addFilters=false 是「全关」不是「按需关」，这里只把 TraceIdFilter 织回 */
    @BeforeEach
    void setUpMockMvc() {
        mockMvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .webAppContextSetup(context)
                .addFilters(traceIdFilter)
                .build();
    }

    private static PromQuery.Sample sample(String instance, double value) {
        return new PromQuery.Sample(Map.of("instance", instance), value, 1_700_000_000_000L);
    }

    // ==================================================================

    @Nested
    @DisplayName("指标目录")
    class Catalog {

        @Test
        @DisplayName("返回内置指标列表，并如实带上集成开关状态")
        void returnsCatalogWithEnabledFlag() throws Exception {
            when(prometheus.isEnabled()).thenReturn(true);

            mockMvc.perform(get("/api/v1/metrics/catalog"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data.metrics").isArray())
                    // 每条指标必须自带 id/name/unit/describe，
                    // 缺 unit 会让前端不知道该不该加百分号
                    .andExpect(jsonPath("$.data.metrics[0].id").exists())
                    .andExpect(jsonPath("$.data.metrics[0].unit").exists())
                    .andExpect(jsonPath("$.data.enabled").value(true))
                    .andExpect(jsonPath("$.traceId").exists());
        }

        @Test
        @DisplayName("未启用集成时 enabled=false —— 前端据此显示接入引导而非空图表")
        void reportsDisabledIntegration() throws Exception {
            when(prometheus.isEnabled()).thenReturn(false);

            mockMvc.perform(get("/api/v1/metrics/catalog"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.enabled").value(false));
        }
    }

    @Nested
    @DisplayName("瞬时查询")
    class InstantQuery {

        @Test
        @DisplayName("按 ID 查到样本，值与标签原样下发")
        void returnsSamples() throws Exception {
            when(prometheus.query(anyString()))
                    .thenReturn(List.of(sample("node-a:9100", 42.5)));

            mockMvc.perform(get("/api/v1/metrics/instant").param("metric", "cpu.usage"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data.metric").value("cpu.usage"))
                    .andExpect(jsonPath("$.data.samples[0].value").value(42.5))
                    .andExpect(jsonPath("$.data.samples[0].labels.instance").value("node-a:9100"));
        }

        @Test
        @DisplayName("NaN 序列化成 null 而不是 0 ——「取不到」不能伪装成「读数为 0」")
        void nanBecomesNullNotZero() throws Exception {
            when(prometheus.query(anyString()))
                    .thenReturn(List.of(sample("node-down:9100", Double.NaN)));

            mockMvc.perform(get("/api/v1/metrics/instant").param("metric", "cpu.usage"))
                    .andExpect(status().isOk())
                    // 字段必须在（前端要按 instance 对齐行），但值是 null
                    .andExpect(jsonPath("$.data.samples[0].value").doesNotExist())
                    .andExpect(jsonPath("$.data.samples[0].labels.instance").value("node-down:9100"));
        }

        @Test
        @DisplayName("0 是有效读数，必须原样保留（不能被当成空值抹掉）")
        void zeroIsAValidReading() throws Exception {
            when(prometheus.query(anyString()))
                    .thenReturn(List.of(sample("node-idle:9100", 0.0)));

            mockMvc.perform(get("/api/v1/metrics/instant").param("metric", "cpu.usage"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.samples[0].value").value(0.0));
        }

        @Test
        @DisplayName("未知指标 ID 被拒（40001）—— 绝不回退到默认查询")
        void rejectsUnknownMetricId() throws Exception {
            mockMvc.perform(get("/api/v1/metrics/instant").param("metric", "not.a.metric"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(40001));

            // 关键：根本不该发起查询。若这里放行，Prometheus 会收到一条
            // 由用户输入拼出的语句，指标目录的安全边界就形同虚设
            verify(prometheus, never()).query(anyString());
        }

        @Test
        @DisplayName("缺 metric 参数返回 400，而不是 500")
        void missingMetricParamIsBadRequest() throws Exception {
            mockMvc.perform(get("/api/v1/metrics/instant"))
                    .andExpect(status().is4xxClientError());
        }

        @Test
        @DisplayName("数据源不可用 → 50020 / HTTP 503（不是 500）")
        void datasourceDownMapsTo503() throws Exception {
            when(prometheus.query(anyString()))
                    .thenThrow(new MetricsUnavailableException("Prometheus 连接超时"));

            mockMvc.perform(get("/api/v1/metrics/instant").param("metric", "cpu.usage"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value(50020))
                    // 消息要能指向处置动作，不能只说「系统异常」
                    .andExpect(jsonPath("$.message").value("Prometheus 连接超时"));
        }
    }

    @Nested
    @DisplayName("区间查询")
    class RangeQuery {

        private void stubEmptyRange() {
            when(prometheus.queryRange(anyString(), any(Instant.class), any(Instant.class), anyInt()))
                    .thenReturn(List.of());
        }

        @Test
        @DisplayName("默认 1 小时 / 步长 60，并回传生效参数")
        void defaultsAreEchoedBack() throws Exception {
            stubEmptyRange();

            mockMvc.perform(get("/api/v1/metrics/range").param("metric", "cpu.usage"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.hours").value(1))
                    .andExpect(jsonPath("$.data.step").value(60))
                    .andExpect(jsonPath("$.data.from").isNumber())
                    .andExpect(jsonPath("$.data.to").isNumber());
        }

        @Test
        @DisplayName("超大窗口被夹到 31 天，且响应如实回报——否则用户会误判成数据缺失")
        void clampsOversizedWindow() throws Exception {
            stubEmptyRange();

            mockMvc.perform(get("/api/v1/metrics/range")
                            .param("metric", "cpu.usage")
                            .param("hours", "99999"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.hours").value(31 * 24));

            verify(prometheus).queryRange(anyString(), any(Instant.class), any(Instant.class), anyInt());
        }

        @Test
        @DisplayName("hours=0 夹到 1 而不是报错——手滑填 0 不该变成一次失败请求")
        void clampsZeroHoursToMinimum() throws Exception {
            stubEmptyRange();

            mockMvc.perform(get("/api/v1/metrics/range")
                            .param("metric", "cpu.usage")
                            .param("hours", "0"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.hours").value(1));
        }

        @Test
        @DisplayName("步长被夹到 [1, 3600]")
        void clampsStep() throws Exception {
            stubEmptyRange();

            mockMvc.perform(get("/api/v1/metrics/range")
                            .param("metric", "cpu.usage")
                            .param("step", "99999"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.step").value(3600));

            mockMvc.perform(get("/api/v1/metrics/range")
                            .param("metric", "cpu.usage")
                            .param("step", "-5"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.step").value(1));
        }

        @Test
        @DisplayName("时间线里的 NaN 点同样是 null —— ECharts 才会断线而不是画出假的跌到底")
        void nanPointsBecomeNull() throws Exception {
            when(prometheus.queryRange(anyString(), any(Instant.class), any(Instant.class), anyInt()))
                    .thenReturn(List.of(new PromQuery.Series(
                            Map.of("instance", "node-a:9100"),
                            List.of(new PromQuery.Point(1_700_000_000_000L, 10.0),
                                    new PromQuery.Point(1_700_000_060_000L, Double.NaN),
                                    new PromQuery.Point(1_700_000_120_000L, 20.0)))));

            mockMvc.perform(get("/api/v1/metrics/range").param("metric", "cpu.usage"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.series[0].points[0].v").value(10.0))
                    .andExpect(jsonPath("$.data.series[0].points[1].v").doesNotExist())
                    // 时间戳必须始终存在，否则断点处的横轴会错位
                    .andExpect(jsonPath("$.data.series[0].points[1].t").value(1_700_000_060_000L))
                    .andExpect(jsonPath("$.data.series[0].points[2].v").value(20.0));
        }

        @Test
        @DisplayName("未知指标 ID 同样被拒，不发起区间查询")
        void rejectsUnknownMetricId() throws Exception {
            mockMvc.perform(get("/api/v1/metrics/range").param("metric", "evil{__name__=~\".+\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(40001));

            verify(prometheus, never())
                    .queryRange(anyString(), any(Instant.class), any(Instant.class), anyInt());
        }
    }

    @Nested
    @DisplayName("总览卡片")
    class Overview {

        @Test
        @DisplayName("五张卡片齐备，且每张都带单位与解释")
        void returnsFiveCards() throws Exception {
            when(prometheus.query(anyString())).thenReturn(List.of(sample("node-a:9100", 55.0)));

            mockMvc.perform(get("/api/v1/metrics/overview"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.cards['cpu.usage'].ok").value(true))
                    .andExpect(jsonPath("$.data.cards['cpu.usage'].unit").value("percent"))
                    .andExpect(jsonPath("$.data.cards['cpu.usage'].describe").isNotEmpty())
                    .andExpect(jsonPath("$.data.cards['memory.usage']").exists())
                    .andExpect(jsonPath("$.data.cards['disk.usage']").exists())
                    .andExpect(jsonPath("$.data.cards['load.avg1']").exists())
                    .andExpect(jsonPath("$.data.cards['target.up']").exists())
                    .andExpect(jsonPath("$.data.timestamp").isNumber());
        }

        @Test
        @DisplayName("单条指标失败不拖垮整页：失败项标 ok=false，其余照常出数")
        void oneFailingMetricDoesNotBlankThePage() throws Exception {
            // 只有 CPU 那条语句会抛，其余正常
            when(prometheus.query(anyString())).thenReturn(List.of(sample("node-a:9100", 55.0)));
            when(prometheus.query(com.devops.agent.infrastructure.metrics.MetricsCatalog
                    .promqlOf("cpu.usage")))
                    .thenThrow(new MetricsUnavailableException("node-exporter 未就绪"));

            mockMvc.perform(get("/api/v1/metrics/overview"))
                    .andExpect(status().isOk())
                    // 整体仍是 200——总览页不该因为一个 exporter 没起就整页空白
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data.cards['cpu.usage'].ok").value(false))
                    // 失败原因如实标注，用户才知道该去修哪个 exporter
                    .andExpect(jsonPath("$.data.cards['cpu.usage'].error").value("node-exporter 未就绪"))
                    .andExpect(jsonPath("$.data.cards['cpu.usage'].samples").isEmpty())
                    // 其余卡片不受影响
                    .andExpect(jsonPath("$.data.cards['memory.usage'].ok").value(true));
        }
    }

    @Nested
    @DisplayName("数据源健康")
    class Datasource {

        @Test
        @DisplayName("可达时回传延迟，结构是列表——将来接 K8s/云平台不用改契约")
        void reportsReachable() throws Exception {
            when(prometheus.health()).thenReturn(Map.of(
                    "baseUrl", "http://localhost:9090",
                    "enabled", true,
                    "reachable", true,
                    "latencyMs", 12L));

            mockMvc.perform(get("/api/v1/metrics/datasource"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.total").value(1))
                    .andExpect(jsonPath("$.data.datasources[0].type").value("prometheus"))
                    .andExpect(jsonPath("$.data.datasources[0].reachable").value(true))
                    .andExpect(jsonPath("$.data.datasources[0].latencyMs").value(12));
        }

        @Test
        @DisplayName("连不上时仍返回 200 —— 「连不上」正是这个端点要报告的结果")
        void unreachableIsStillHttp200() throws Exception {
            when(prometheus.health()).thenReturn(Map.of(
                    "baseUrl", "http://localhost:9090",
                    "enabled", true,
                    "reachable", false,
                    "error", "Connection refused"));

            mockMvc.perform(get("/api/v1/metrics/datasource"))
                    // 若这里返回 503，接入管理页自己就打不开了，
                    // 用户连「数据源为什么挂」都看不到——本末倒置
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data.datasources[0].reachable").value(false))
                    .andExpect(jsonPath("$.data.datasources[0].error").value("Connection refused"));
        }
    }
}
