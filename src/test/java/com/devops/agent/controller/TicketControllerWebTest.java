package com.devops.agent.controller;

import com.devops.agent.common.exception.GlobalExceptionHandler;
import com.devops.agent.common.exception.OptimisticLockException;
import com.devops.agent.domain.biz.entity.DevOpsTicket;
import com.devops.agent.domain.biz.service.TicketAiAnalysisService;
import com.devops.agent.domain.biz.service.TicketAttachmentService;
import com.devops.agent.domain.biz.service.TicketService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link TicketController} HTTP 契约测试。
 *
 * <h3>为什么需要这组测试</h3>
 * 项目此前 99 个端点<b>零 Controller 测试</b>，API 契约完全没有回归保护。
 * 而 F2 重构刚刚移除了 58 处样板 try/catch，把错误映射整体搬到
 * {@link GlobalExceptionHandler}——这类跨十几个文件的机械改动，
 * 最容易出的问题恰恰是「异常没被正确接管，状态码或业务码变了」，
 * 而这种变化在编译期完全看不出来。
 *
 * <h3>测试边界</h3>
 * 只加载 Web 层（{@code @WebMvcTest}），Service 全部 mock。
 * 这里验证的是<b>HTTP 契约</b>：路由、状态码、响应结构、异常映射，
 * 不验证业务逻辑（那是 {@code TicketServiceTest} 的职责）。
 *
 * <p>显式 {@code @Import(GlobalExceptionHandler.class)}：{@code @WebMvcTest}
 * 默认会扫描 {@code @ControllerAdvice}，但显式声明能让「这组测试依赖全局异常
 * 处理器」这一意图变得清晰，也避免将来调整扫描范围时被意外排除。</p>
 *
 * <p>{@code addFilters = false} 关闭 Servlet 过滤器链：本项目有
 * Sa-Token 鉴权与 traceId Filter，它们的行为应由各自的测试覆盖，
 * 混进来只会让契约断言失败原因变得含糊。</p>
 */
@ExtendWith(SpringExtension.class)
@WebMvcTest(
        controllers = TicketController.class,
        // 排除 WebConfig：它会注册 SaInterceptor，其 StpUtil.checkLogin()
        // 需要完整的 Sa-Token 运行时上下文，在切片测试里会让所有请求返回 401，
        // 把「契约是否正确」的断言变成「鉴权是否配好」的噪音。
        // 鉴权行为应由独立的鉴权测试覆盖，不该混进契约测试。
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = com.devops.agent.controller.config.WebConfig.class),
        excludeAutoConfiguration = {
                org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration.class
        })
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class TicketControllerWebTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private TicketService ticketService;

    @MockitoBean
    private TicketAttachmentService attachmentService;

    @MockitoBean
    private TicketAiAnalysisService aiAnalysisService;

    private static DevOpsTicket sampleTicket() {
        DevOpsTicket t = new DevOpsTicket();
        t.setId("TKT-20260824-0001");
        t.setTitle("order-service Pod CrashLoopBackOff");
        t.setStatus("PENDING");
        t.setPriority("P0");
        t.setVersion(1);
        return t;
    }

    // ==================== 成功路径：响应结构契约 ====================

    @Test
    @DisplayName("查询工单详情：返回 code=0 且 data 为工单对象")
    void getTicketById_returnsWrappedTicket() throws Exception {
        when(ticketService.getTicketWithTags("TKT-20260824-0001")).thenReturn(sampleTicket());

        mockMvc.perform(get("/api/v1/tickets/TKT-20260824-0001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value("TKT-20260824-0001"))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                // traceId 是排障入口，必须始终存在
                .andExpect(jsonPath("$.traceId").exists());
    }

    @Test
    @DisplayName("创建工单：请求体字段被正确透传给 Service")
    void createTicket_passesFieldsThrough() throws Exception {
        when(ticketService.createTicket(anyString(), anyString(), anyString(), anyString(),
                any(), any(), any(), any(), any())).thenReturn(sampleTicket());

        String body = objectMapper.writeValueAsString(Map.of(
                "title", "order-service Pod CrashLoopBackOff",
                "priority", "P0",
                "module", "order-service",
                "description", "Pod 反复重启，就绪探针失败"));

        mockMvc.perform(post("/api/v1/tickets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value("TKT-20260824-0001"));
    }

    @Test
    @DisplayName("变更状态：成功时返回更新后的工单")
    void updateStatus_returnsUpdated() throws Exception {
        DevOpsTicket updated = sampleTicket();
        updated.setStatus("PROCESSING");
        when(ticketService.updateStatus("TKT-20260824-0001", "PROCESSING")).thenReturn(updated);

        mockMvc.perform(patch("/api/v1/tickets/TKT-20260824-0001/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"PROCESSING\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("PROCESSING"));
    }

    // ==================== 异常映射：F2 重构的核心回归点 ====================

    @Test
    @DisplayName("非法状态流转 → 40004 + HTTP 409（F2 后由全局处理器接管）")
    void illegalStateMapsTo40004() throws Exception {
        when(ticketService.updateStatus(anyString(), anyString()))
                .thenThrow(new IllegalStateException("非法状态流转：已作废 → 待处理"));

        mockMvc.perform(patch("/api/v1/tickets/TKT-1/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"PENDING\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40004))
                // 领域层消息是写给用户看的，应原样透传
                .andExpect(jsonPath("$.message").value("非法状态流转：已作废 → 待处理"));
    }

    @Test
    @DisplayName("参数非法 → 40001 + HTTP 400")
    void illegalArgumentMapsTo40001() throws Exception {
        when(ticketService.updateStatus(anyString(), anyString()))
                .thenThrow(new IllegalArgumentException("状态不能为空"));

        mockMvc.perform(patch("/api/v1/tickets/TKT-1/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
    }

    @Test
    @DisplayName("乐观锁冲突 → 40009 + HTTP 409，而非 500")
    void optimisticLockMapsTo40009() throws Exception {
        // 修复前：未单独 catch 的端点会落到 RuntimeException 分支返回 500，
        // 把「他人已修改，请刷新」这种可恢复冲突误报成服务器故障
        when(ticketService.updateTicket(anyString(), any()))
                .thenThrow(new OptimisticLockException("工单 TKT-1", 1, 2));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/v1/tickets/TKT-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"改标题\",\"version\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40009));
    }

    @Test
    @DisplayName("未预期异常 → 50001，且不把内部异常消息泄漏给用户")
    void unexpectedErrorDoesNotLeakInternals() throws Exception {
        when(ticketService.getTicketWithTags(anyString()))
                .thenThrow(new RuntimeException(
                        "ERROR: relation \"sys_ticket\" does not exist; SQL state 42P01"));

        mockMvc.perform(get("/api/v1/tickets/TKT-1"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(50001))
                // 表名与 SQL state 属内部实现细节，对攻击者是信息泄漏
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("sys_ticket"))))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("42P01"))));
    }

    // ==================== 路由契约 ====================

    @Test
    @DisplayName("列表端点返回分页结构（total + list）")
    void listReturnsPagedShape() throws Exception {
        when(ticketService.findTickets(org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(), any()))
                .thenReturn(new java.util.ArrayList<>(java.util.List.of(sampleTicket())));
        when(ticketService.countTickets(any())).thenReturn(1L);

        mockMvc.perform(get("/api/v1/tickets").param("page", "1").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.tickets").isArray())
                .andExpect(jsonPath("$.data.page").value(1));
    }

    @Test
    @DisplayName("未注册的子路径返回 404，不应被当作工单号吞掉")
    void unknownSubPathIsNotSwallowed() throws Exception {
        mockMvc.perform(post("/api/v1/tickets/TKT-1/definitely-not-an-endpoint"))
                .andExpect(status().is4xxClientError());
    }
}
