package com.devops.agent.controller;

import com.devops.agent.application.DashboardService;
import com.devops.agent.common.exception.GlobalExceptionHandler;
import com.devops.agent.controller.dto.DashboardOverviewDTO;
import com.devops.agent.domain.biz.service.TicketService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link DashboardController} HTTP 契约测试。
 *
 * <h3>这个 Controller 的风险点不在「能不能取到数」，而在「口径」</h3>
 * {@code /trends} 把两个来源的数据拼进同一张图：工单趋势来自
 * {@code sys_devops_ticket}（<b>支持按服务下钻</b>），成本与缓存命中率来自
 * {@code sys_agent_call_log}（<b>没有服务维度，永远是全局值</b>）。
 *
 * <p>这里最容易出的事故是<b>静默的口径错配</b>：用户在页面上选了「MySQL」，
 * 看到成本曲线跟着变了（其实没变，只是他以为变了），
 * 于是把全局 AI 成本当成 MySQL 这个服务的成本上报。
 * 后端为此在响应里显式回传 {@code module} 与 {@code callTrendScope=GLOBAL}，
 * 让前端能标注口径——<b>这两个字段是契约的一部分，不是调试信息</b>，
 * 少了它们页面就无从区分，所以必须有用例钉住。</p>
 *
 * <h3>另外两件只在运行期才暴露的事</h3>
 * <ul>
 *   <li><b>days 的钳制</b>——{@code days=0} 会让 SQL 区间为空（返回空图，
 *       用户以为「这几天没工单」），{@code days=100000} 会一次拉爆。
 *       后端夹到 [1, 90] 并<b>回传 windowDays</b>，让用户看得出窗口被改过；</li>
 *   <li><b>module 的空白处理</b>——{@code module=""}（前端清空筛选时常见的写法）
 *       必须等价于「不下钻」。若原样传进 SQL，就会去匹配一个名为空串的服务，
 *       结果是一张全空的图，而用户以为自己只是清了筛选。</li>
 * </ul>
 *
 * <p>切片装配沿用已验证的做法，见 {@code TicketControllerWebTest} 的说明。</p>
 */
@ExtendWith(SpringExtension.class)
@WebMvcTest(
        controllers = DashboardController.class,
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
class DashboardControllerWebTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private com.devops.agent.common.web.TraceIdFilter traceIdFilter;

    @Autowired
    private org.springframework.web.context.WebApplicationContext context;

    @MockitoBean
    private DashboardService dashboardService;

    @MockitoBean
    private TicketService ticketService;

    @BeforeEach
    void setUpMockMvc() {
        mockMvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .webAppContextSetup(context)
                .addFilters(traceIdFilter)
                .build();
    }

    // ==================== 夹具 ====================

    private static Map<String, Object> ticketTrends(int days) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("days", labels(days));
        m.put("created", zeros(days));
        m.put("resolved", zeros(days));
        return m;
    }

    private static Map<String, Object> callTrends(int days) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("days", labels(days));
        m.put("cost", zeros(days));
        m.put("cacheHitRate", zeros(days));
        return m;
    }

    private static List<String> labels(int days) {
        String[] a = new String[days];
        Arrays.setAll(a, i -> "2026-08-" + String.format("%02d", i + 1));
        return List.of(a);
    }

    private static List<Integer> zeros(int days) {
        Integer[] a = new Integer[days];
        Arrays.fill(a, 0);
        return List.of(a);
    }

    private void stubTrends(int days) {
        when(ticketService.getTicketTrends(anyInt(), any())).thenReturn(ticketTrends(days));
        when(dashboardService.getCallTrends(anyInt())).thenReturn(callTrends(days));
    }

    // ==================================================================

    @Nested
    @DisplayName("看板概览")
    class Overview {

        @Test
        @DisplayName("概览字段齐备，且带 traceId")
        void returnsOverviewFields() throws Exception {
            when(dashboardService.getOverview()).thenReturn(DashboardOverviewDTO.builder()
                    .totalQueries(1200L)
                    .cacheHits(360L)
                    .cacheHitRate(30.0)
                    .avgCostRmb(0.0125)
                    .totalTickets(88L)
                    .modelDistribution(List.of(DashboardOverviewDTO.ModelDistribution.builder().build()))
                    .costSavingsChart(List.of(DashboardOverviewDTO.CostTrend.builder().build()))
                    .build());

            mockMvc.perform(get("/api/v1/dashboard/overview"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data.totalQueries").value(1200))
                    .andExpect(jsonPath("$.data.cacheHits").value(360))
                    .andExpect(jsonPath("$.data.cacheHitRate").value(30.0))
                    .andExpect(jsonPath("$.data.totalTickets").value(88))
                    .andExpect(jsonPath("$.data.modelDistribution").isArray())
                    .andExpect(jsonPath("$.data.costSavingsChart").isArray())
                    .andExpect(jsonPath("$.traceId").exists());
        }

        @Test
        @DisplayName("零数据时字段仍在（0 而非缺字段）——前端不必对每个字段判空")
        void zeroDataStillKeepsFields() throws Exception {
            when(dashboardService.getOverview()).thenReturn(DashboardOverviewDTO.builder()
                    .totalQueries(0L)
                    .cacheHits(0L)
                    .cacheHitRate(0.0)
                    .avgCostRmb(0.0)
                    .totalTickets(0L)
                    .modelDistribution(List.of())
                    .costSavingsChart(List.of())
                    .build());

            mockMvc.perform(get("/api/v1/dashboard/overview"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalQueries").value(0))
                    .andExpect(jsonPath("$.data.modelDistribution").isEmpty());
        }
    }

    @Nested
    @DisplayName("趋势与下钻口径")
    class Trends {

        @Test
        @DisplayName("默认 7 天，三条线共用同一横轴")
        void defaultsToSevenDays() throws Exception {
            stubTrends(7);

            mockMvc.perform(get("/api/v1/dashboard/trends"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.windowDays").value(7))
                    .andExpect(jsonPath("$.data.days.length()").value(7))
                    .andExpect(jsonPath("$.data.created.length()").value(7))
                    .andExpect(jsonPath("$.data.cost.length()").value(7))
                    .andExpect(jsonPath("$.data.cacheHitRate.length()").value(7));

            verify(ticketService).getTicketTrends(eq(7), isNull());
            verify(dashboardService).getCallTrends(7);
        }

        @Test
        @DisplayName("days 上下界钳制到 [1, 90]，并回传生效窗口")
        void clampsDays() throws Exception {
            stubTrends(1);
            mockMvc.perform(get("/api/v1/dashboard/trends").param("days", "0"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.windowDays").value(1));
            verify(ticketService).getTicketTrends(eq(1), isNull());

            stubTrends(90);
            mockMvc.perform(get("/api/v1/dashboard/trends").param("days", "100000"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.windowDays").value(90));
            verify(ticketService).getTicketTrends(eq(90), isNull());
        }

        @Test
        @DisplayName("负数天数同样夹到 1，而不是让 SQL 区间倒过来")
        void clampsNegativeDays() throws Exception {
            stubTrends(1);

            mockMvc.perform(get("/api/v1/dashboard/trends").param("days", "-30"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.windowDays").value(1));
        }

        @Test
        @DisplayName("下钻时明示成本/命中率仍是全局口径 —— 防止把全局成本读成该服务的成本")
        void drilldownDeclaresGlobalScopeForCostAndHitRate() throws Exception {
            stubTrends(7);

            mockMvc.perform(get("/api/v1/dashboard/trends").param("module", "MYSQL"))
                    .andExpect(status().isOk())
                    // 生效的下钻口径回传给前端做标注
                    .andExpect(jsonPath("$.data.module").value("MYSQL"))
                    // 这一条是本类最重要的断言：它是「成本曲线没跟着下钻」的唯一显式声明
                    .andExpect(jsonPath("$.data.callTrendScope").value("GLOBAL"));

            // 工单两条线按服务过滤
            verify(ticketService).getTicketTrends(eq(7), eq("MYSQL"));
            // AI 调用趋势拿不到服务维度，只能全局
            verify(dashboardService).getCallTrends(7);
        }

        @Test
        @DisplayName("module 空白等价于不下钻 —— 否则会去匹配一个叫空串的服务，图表全空")
        void blankModuleMeansGlobal() throws Exception {
            stubTrends(7);

            mockMvc.perform(get("/api/v1/dashboard/trends").param("module", "   "))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.module").doesNotExist());

            verify(ticketService).getTicketTrends(eq(7), isNull());
        }

        @Test
        @DisplayName("module 首尾空格被裁掉再查 —— 复制粘贴带来的空格不该让筛选落空")
        void trimsModule() throws Exception {
            stubTrends(7);

            mockMvc.perform(get("/api/v1/dashboard/trends").param("module", " K8S "))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.module").value("K8S"));

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(ticketService).getTicketTrends(eq(7), captor.capture());
            assertThat(captor.getValue()).isEqualTo("K8S");
        }

        @Test
        @DisplayName("横轴取工单趋势的 days；两侧长度不一致时仍返回 200（记 WARN，不让页面挂掉）")
        void mismatchedAxisDoesNotBreakTheResponse() throws Exception {
            when(ticketService.getTicketTrends(anyInt(), any())).thenReturn(ticketTrends(7));
            when(dashboardService.getCallTrends(anyInt())).thenReturn(callTrends(5));

            mockMvc.perform(get("/api/v1/dashboard/trends"))
                    .andExpect(status().isOk())
                    // 以工单趋势为准
                    .andExpect(jsonPath("$.data.days.length()").value(7))
                    .andExpect(jsonPath("$.data.cost.length()").value(5));
        }
    }
}
