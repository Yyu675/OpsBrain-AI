package com.devops.agent.controller;

import com.devops.agent.common.exception.GlobalExceptionHandler;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link HealthCheckController} HTTP 契约测试。
 *
 * <h3>本类存在的直接原因：写它的时候查出了一个真实缺陷</h3>
 * 原代码在 {@code /ai-model} 方法上标了：
 * <pre>{@code
 * @GetMapping("/ai-model")
 * @ConditionalOnProperty(name = "devops.ai.health.ai-model-enabled", havingValue = "true")
 * }</pre>
 *
 * <p><b>{@code @ConditionalOnProperty} 标在 {@code @RequestMapping} 方法上完全不生效。</b>
 * 它是 <b>Bean 注册阶段</b>的条件注解；Controller 这个 Bean 一旦注册，
 * 它的全部请求映射方法都会被 {@code RequestMappingHandlerMapping} 扫描注册成路由，
 * 没有任何一步会去看方法上的这个注解。</p>
 *
 * <p>后果是 P1-7 的修复完全落空：开关配了（{@code application.yml} 里默认 false）、
 * 文档也写了「默认关闭」，但<b>端点实际一直开放</b>。
 * 再叠加 {@code WebConfig} 把 {@code /api/v1/health/**} 放进鉴权白名单
 * （为 K8s 探针放行），结果是一个 <b>匿名可访问的付费 LLM 端点</b>——
 * 正是 P1-7 声称已经堵上的那个成本失控风险。
 * K8s 默认 probe 间隔 10s，一天 8640 次 LLM + embedding 调用。</p>
 *
 * <p>这类缺陷有个共同特征：<b>配置项存在、文档齐全、代码看着也对，
 * 唯独没有任何东西真正读取它</b>。上一轮查出的「业务码词表零调用方」是同一类。
 * 它们不会有任何报错，只能靠测试断言「关掉时真的没调用」来发现。</p>
 *
 * <h3>分档设计：按是否消耗外部付费资源</h3>
 * <ul>
 *   <li>{@code /ping} —— 零外部调用，始终开放，可被 K8s livenessProbe 高频拉取；</li>
 *   <li>{@code /db} —— 只探本地库，失败要如实报 DOWN 而不是抛异常；</li>
 *   <li>{@code /ai-model} —— 真实调付费 API，默认关闭。</li>
 * </ul>
 */
@ExtendWith(SpringExtension.class)
@WebMvcTest(
        controllers = HealthCheckController.class,
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
@TestPropertySource(properties = {
        "devops.ai.mode=MOCK",
        // 与生产默认值一致：付费探测默认关闭
        "devops.ai.health.ai-model-enabled=false"
})
class HealthCheckControllerWebTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private com.devops.agent.common.web.TraceIdFilter traceIdFilter;

    @Autowired
    private org.springframework.web.context.WebApplicationContext context;

    @MockitoBean
    private DataSource dataSource;

    @MockitoBean(name = "turboModel")
    private ChatModel turboModel;

    @MockitoBean(name = "reasonerModel")
    private ChatModel reasonerModel;

    @MockitoBean
    private EmbeddingModel embeddingModel;

    @BeforeEach
    void setUpMockMvc() {
        mockMvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .webAppContextSetup(context)
                .addFilters(traceIdFilter)
                .build();
    }

    // ==================================================================

    @Nested
    @DisplayName("存活探针（零成本，始终开放）")
    class Ping {

        @Test
        @DisplayName("/health 与 /health/ping 都返回 UP，且不触碰任何外部依赖")
        void pingIsFreeOfExternalCalls() throws Exception {
            mockMvc.perform(get("/api/v1/health"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("UP"))
                    .andExpect(jsonPath("$.mode").value("MOCK"))
                    .andExpect(jsonPath("$.timestamp").isNumber());

            mockMvc.perform(get("/api/v1/health/ping"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("UP"));

            // 存活探针被 K8s 每 10 秒拉一次。一旦有人往这里加个数据库查询
            // 或模型调用，就会变成一天上万次的隐形负载/账单
            verifyNoInteractions(dataSource, turboModel, reasonerModel, embeddingModel);
        }

        @Test
        @DisplayName("/ping 不走 ApiResponse 包装 —— 探针要的是最小响应体")
        void pingReturnsRawMap() throws Exception {
            mockMvc.perform(get("/api/v1/health/ping"))
                    .andExpect(status().isOk())
                    // 这里刻意不是 {code,message,data,traceId} 结构：
                    // 探针只看 HTTP 状态与 status 字段，包装层是多余开销
                    .andExpect(jsonPath("$.code").doesNotExist())
                    .andExpect(jsonPath("$.status").value("UP"));
        }
    }

    @Nested
    @DisplayName("数据库探针（只探本地库）")
    class Db {

        @Test
        @DisplayName("连接有效 → UP")
        void healthyDbReportsUp() throws Exception {
            Connection conn = mock(Connection.class);
            when(conn.isValid(anyInt())).thenReturn(true);
            when(dataSource.getConnection()).thenReturn(conn);

            mockMvc.perform(get("/api/v1/health/db"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("UP"))
                    .andExpect(jsonPath("$.database").value("PostgreSQL/pgvector"));
        }

        @Test
        @DisplayName("连接超时未就绪 → DEGRADED（区别于完全连不上的 DOWN）")
        void invalidConnectionReportsDegraded() throws Exception {
            Connection conn = mock(Connection.class);
            when(conn.isValid(anyInt())).thenReturn(false);
            when(dataSource.getConnection()).thenReturn(conn);

            mockMvc.perform(get("/api/v1/health/db"))
                    .andExpect(status().isOk())
                    // DEGRADED 与 DOWN 的处置不同：前者是慢/半死，
                    // 后者是彻底连不上。混为一谈会误导排障方向
                    .andExpect(jsonPath("$.status").value("DEGRADED"))
                    .andExpect(jsonPath("$.error").isNotEmpty());
        }

        @Test
        @DisplayName("连不上 → DOWN 且带原因，不是抛 500")
        void unreachableDbReportsDownNotThrow() throws Exception {
            when(dataSource.getConnection())
                    .thenThrow(new SQLException("Connection refused"));

            mockMvc.perform(get("/api/v1/health/db"))
                    // 「连不上」正是这个端点要报告的**结果**。
                    // 抛 500 会让探针端拿不到任何诊断信息
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("DOWN"))
                    .andExpect(jsonPath("$.error").value(
                            org.hamcrest.Matchers.containsString("Connection refused")));
        }

        @Test
        @DisplayName("数据库探针不调用任何模型")
        void dbProbeDoesNotTouchModels() throws Exception {
            Connection conn = mock(Connection.class);
            when(conn.isValid(anyInt())).thenReturn(true);
            when(dataSource.getConnection()).thenReturn(conn);

            mockMvc.perform(get("/api/v1/health/db")).andExpect(status().isOk());

            verifyNoInteractions(turboModel, reasonerModel, embeddingModel);
        }
    }

    @Nested
    @DisplayName("付费探针（默认关闭）—— 本类最重要的一组")
    class AiModelProbe {

        @Test
        @DisplayName("开关关闭时返回 DISABLED，且**一次模型都不调**")
        void disabledProbeCallsNoPaidApi() throws Exception {
            mockMvc.perform(get("/api/v1/health/ai-model"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.overallStatus").value("DISABLED"))
                    // 要说清为什么被关掉、以及怎么打开，
                    // 否则运维只会看到一个没有解释的 DISABLED
                    .andExpect(jsonPath("$.reason").value(
                            org.hamcrest.Matchers.containsString("ai-model-enabled")));

            // 这是全类最关键的断言。修复前 @ConditionalOnProperty 标在方法上不生效，
            // 端点一直开放且在鉴权白名单内——任何人 curl 一下就产生一次
            // LLM + embedding 计费，K8s probe 接上去就是一天 8640 次
            verifyNoInteractions(turboModel, reasonerModel, embeddingModel);
        }

        @Test
        @DisplayName("关闭时返回 200 而不是 4xx/5xx —— 探针端不该因此判实例不健康")
        void disabledProbeStillReturns200() throws Exception {
            mockMvc.perform(get("/api/v1/health/ai-model"))
                    .andExpect(status().isOk());
            // 若返回 503，接了 probe 的 K8s 会反复重启这个实例，
            // 而「探测被关掉了」根本不是实例不健康
        }

        @Test
        @DisplayName("关闭时仍回传 mode 与 timestamp，便于确认配置确实生效")
        void disabledProbeKeepsDiagnosticFields() throws Exception {
            mockMvc.perform(get("/api/v1/health/ai-model"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.mode").value("MOCK"))
                    .andExpect(jsonPath("$.timestamp").isNumber());
        }
    }

}
