package com.devops.agent.controller;

import com.devops.agent.common.exception.GlobalExceptionHandler;
import com.devops.agent.domain.alert.entity.Alert;
import com.devops.agent.domain.alert.service.AlertQueryService;
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

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link AlertController} HTTP 契约测试。
 *
 * <h3>这组端点为什么经不起契约漂移</h3>
 * 告警列表是值班工程师的第一屏。这里的两类错误都不会报错，
 * 只会<b>静默地让人看不见该看见的告警</b>：
 * <ul>
 *   <li><b>分页参数越界</b>——{@code page=0} 会让 SQL 的 OFFSET 变成负数
 *       （MySQL 直接报错，PostgreSQL 语义又不同），
 *       {@code size=100000} 则会一次把整张告警表拉进内存。
 *       Controller 已经做了钳制（page≥1、size∈[1,200]），
 *       但这段逻辑没有任何测试守着，删掉它编译照样通过；</li>
 *   <li><b>处置动作的竞态</b>——「确认」与 Alertmanager 的 resolved 推送
 *       可能同时到达。Service 用「更新影响行数为 0」识别这种竞态并抛
 *       {@code IllegalStateException}，必须映射成 40004/409 而不是 500。
 *       给成 5xx 会让前端按「服务故障」处理并自动重试，
 *       而这是一个<b>重试永远不会成功</b>的状态冲突。</li>
 * </ul>
 *
 * <p>另外钉住一条与工单详情一致的三态语义（6.18 契约）：
 * 空 ID → 40001，不存在 → 40004，存在 → 200。
 * 三者混同会让告警详情页无法区分「链接拼错了」和「这条告警已过保留期」。</p>
 *
 * <p>切片装配沿用 {@code TicketControllerWebTest} 的说明。</p>
 */
@ExtendWith(SpringExtension.class)
@WebMvcTest(
        controllers = AlertController.class,
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
class AlertControllerWebTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private com.devops.agent.common.web.TraceIdFilter traceIdFilter;

    @Autowired
    private org.springframework.web.context.WebApplicationContext context;

    @MockitoBean
    private AlertQueryService alertQueryService;

    @BeforeEach
    void setUpMockMvc() {
        mockMvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .webAppContextSetup(context)
                .addFilters(traceIdFilter)
                .build();
    }

    private static Alert alert(Long id, String level, String status) {
        Alert a = new Alert();
        a.setId(id);
        a.setSource("prometheus");
        a.setAlertName("HighCpuUsage");
        a.setLevel(level);
        a.setTitle("CPU 使用率持续超过 90%");
        a.setDescription("node-a 连续 10 分钟 CPU > 90%");
        a.setStatus(status);
        a.setDedupKey("HighCpuUsage:node-a");
        a.setService("K8S");
        a.setModule("node");
        a.setOccurrenceCount(7);
        a.setFirstOccurredAt(LocalDateTime.of(2026, 8, 25, 9, 0));
        a.setLastOccurredAt(LocalDateTime.of(2026, 8, 25, 9, 30));
        return a;
    }

    private static Map<String, Object> page(List<Alert> alerts, int total, int page, int size) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("alerts", alerts);
        m.put("total", total);
        m.put("page", page);
        m.put("size", size);
        m.put("totalPages", (int) Math.ceil((double) total / size));
        return m;
    }

    // ==================================================================

    @Nested
    @DisplayName("列表查询")
    class ListAlerts {

        @Test
        @DisplayName("默认第 1 页 10 条，不筛状态与级别")
        void defaultPaging() throws Exception {
            when(alertQueryService.listAlerts(isNull(), isNull(), eq(1), eq(10)))
                    .thenReturn(page(List.of(alert(1L, "P1", "FIRING")), 1, 1, 10));

            mockMvc.perform(get("/api/v1/alerts"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data.total").value(1))
                    .andExpect(jsonPath("$.data.page").value(1))
                    .andExpect(jsonPath("$.data.size").value(10))
                    .andExpect(jsonPath("$.data.totalPages").value(1))
                    .andExpect(jsonPath("$.data.alerts[0].alertName").value("HighCpuUsage"))
                    .andExpect(jsonPath("$.traceId").exists());

            verify(alertQueryService).listAlerts(isNull(), isNull(), eq(1), eq(10));
        }

        @Test
        @DisplayName("状态与级别筛选原样透传给 Service")
        void passesFilters() throws Exception {
            when(alertQueryService.listAlerts(eq("FIRING"), eq("P0"), anyInt(), anyInt()))
                    .thenReturn(page(List.of(), 0, 1, 10));

            mockMvc.perform(get("/api/v1/alerts")
                            .param("status", "FIRING")
                            .param("level", "P0"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.alerts").isEmpty());

            verify(alertQueryService).listAlerts(eq("FIRING"), eq("P0"), eq(1), eq(10));
        }

        @Test
        @DisplayName("page<1 被夹到 1 —— 否则 SQL 的 OFFSET 会变成负数")
        void clampsPageLowerBound() throws Exception {
            when(alertQueryService.listAlerts(isNull(), isNull(), eq(1), eq(10)))
                    .thenReturn(page(List.of(), 0, 1, 10));

            mockMvc.perform(get("/api/v1/alerts").param("page", "0"))
                    .andExpect(status().isOk());
            mockMvc.perform(get("/api/v1/alerts").param("page", "-5"))
                    .andExpect(status().isOk());

            verify(alertQueryService, org.mockito.Mockito.times(2))
                    .listAlerts(isNull(), isNull(), eq(1), eq(10));
        }

        @Test
        @DisplayName("size 夹到 [1, 200] —— 上限防的是一次把整张告警表拉进内存")
        void clampsSize() throws Exception {
            when(alertQueryService.listAlerts(isNull(), isNull(), eq(1), eq(200)))
                    .thenReturn(page(List.of(), 0, 1, 200));
            mockMvc.perform(get("/api/v1/alerts").param("size", "100000"))
                    .andExpect(status().isOk());
            verify(alertQueryService).listAlerts(isNull(), isNull(), eq(1), eq(200));

            when(alertQueryService.listAlerts(isNull(), isNull(), eq(1), eq(1)))
                    .thenReturn(page(List.of(), 0, 1, 1));
            mockMvc.perform(get("/api/v1/alerts").param("size", "0"))
                    .andExpect(status().isOk());
            verify(alertQueryService).listAlerts(isNull(), isNull(), eq(1), eq(1));
        }

        @Test
        @DisplayName("page 传非数字返回 400，而不是 500")
        void nonNumericPageIsBadRequest() throws Exception {
            // 兜底 @ExceptionHandler(Exception.class) 优先级高于 Spring 内建解析器，
            // 此前这类请求会返回 500「服务内部异常」，让调用方误判为服务端故障并重试
            mockMvc.perform(get("/api/v1/alerts").param("page", "abc"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(40001));
        }
    }

    @Nested
    @DisplayName("详情三态")
    class GetAlert {

        @Test
        @DisplayName("存在：完整字段下发，含处置时间线")
        void returnsFullEntity() throws Exception {
            Alert a = alert(1L, "P1", "ACKNOWLEDGED");
            a.setAcknowledgedAt(LocalDateTime.of(2026, 8, 25, 9, 35));
            a.setTicketId("TK-2026-0001");
            when(alertQueryService.getAlert(1L)).thenReturn(a);

            mockMvc.perform(get("/api/v1/alerts/1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(1))
                    .andExpect(jsonPath("$.data.status").value("ACKNOWLEDGED"))
                    .andExpect(jsonPath("$.data.occurrenceCount").value(7))
                    // 详情页靠这两个字段渲染处置时间线与「已建单」入口
                    .andExpect(jsonPath("$.data.acknowledgedAt").exists())
                    .andExpect(jsonPath("$.data.ticketId").value("TK-2026-0001"));
        }

        @Test
        @DisplayName("尚未处置时 acknowledgedAt/resolvedAt 为 null，不伪造时间")
        void unhandledAlertHasNullTimestamps() throws Exception {
            when(alertQueryService.getAlert(1L)).thenReturn(alert(1L, "P0", "FIRING"));

            mockMvc.perform(get("/api/v1/alerts/1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.acknowledgedAt").doesNotExist())
                    .andExpect(jsonPath("$.data.resolvedAt").doesNotExist());
        }

        @Test
        @DisplayName("不存在 → 40004 / HTTP 409（与工单详情三态语义一致）")
        void notFoundMapsTo40004() throws Exception {
            when(alertQueryService.getAlert(anyLong()))
                    .thenThrow(new IllegalStateException("告警不存在"));

            mockMvc.perform(get("/api/v1/alerts/999"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value(40004))
                    .andExpect(jsonPath("$.message").value("告警不存在"));
        }

        @Test
        @DisplayName("ID 非数字 → 400/40001，而不是被当成「告警不存在」")
        void nonNumericIdIsBadRequest() throws Exception {
            // 前端拼路径时变量未取到值会得到 /alerts/undefined。
            // 若这里返回 40004，用户看到的是「这条告警不存在」，
            // 排查方向被引向数据，而真正的 bug 在前端路由
            mockMvc.perform(get("/api/v1/alerts/undefined"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(40001));
        }
    }

    @Nested
    @DisplayName("处置动作")
    class Handling {

        @Test
        @DisplayName("确认成功返回更新后的状态与提示语")
        void acknowledgeReturnsUpdatedAlert() throws Exception {
            when(alertQueryService.acknowledge(1L)).thenReturn(alert(1L, "P1", "ACKNOWLEDGED"));

            mockMvc.perform(post("/api/v1/alerts/1/acknowledge"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.message").value("已确认"))
                    // 返回更新后的实体，前端就地更新行而不必重新拉整页
                    .andExpect(jsonPath("$.data.status").value("ACKNOWLEDGED"));
        }

        @Test
        @DisplayName("确认时告警已被恢复（竞态）→ 40004/409，不是 500")
        void acknowledgeRaceMapsTo409() throws Exception {
            // 典型竞态：查询时还在 FIRING，更新瞬间 Alertmanager 的 resolved 推送到达。
            // 给成 5xx 会让前端按「服务故障」自动重试，
            // 而这是一个重试永远不会成功的状态冲突
            when(alertQueryService.acknowledge(anyLong()))
                    .thenThrow(new IllegalStateException("告警已恢复，无法确认"));

            mockMvc.perform(post("/api/v1/alerts/1/acknowledge"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value(40004))
                    .andExpect(jsonPath("$.message").value("告警已恢复，无法确认"));
        }

        @Test
        @DisplayName("标记恢复成功")
        void resolveReturnsUpdatedAlert() throws Exception {
            Alert a = alert(1L, "P1", "RESOLVED");
            a.setResolvedAt(LocalDateTime.of(2026, 8, 25, 10, 0));
            when(alertQueryService.resolve(1L)).thenReturn(a);

            mockMvc.perform(post("/api/v1/alerts/1/resolve"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("已标记恢复"))
                    .andExpect(jsonPath("$.data.status").value("RESOLVED"))
                    .andExpect(jsonPath("$.data.resolvedAt").exists());
        }

        @Test
        @DisplayName("重复标记恢复 → 40004/409（幂等失败要说清原因）")
        void duplicateResolveMapsTo409() throws Exception {
            when(alertQueryService.resolve(anyLong()))
                    .thenThrow(new IllegalStateException("告警已恢复，无需重复操作"));

            mockMvc.perform(post("/api/v1/alerts/1/resolve"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value(40004));
        }

        @Test
        @DisplayName("处置端点只接受 POST，GET 返回 405 而不是 500")
        void handlingRejectsWrongMethod() throws Exception {
            mockMvc.perform(get("/api/v1/alerts/1/acknowledge"))
                    .andExpect(status().isMethodNotAllowed())
                    .andExpect(jsonPath("$.code").value(40001));
        }
    }
}
