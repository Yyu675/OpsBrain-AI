package com.devops.agent.controller;

import com.devops.agent.application.runtime.ApprovalOrchestrator;
import com.devops.agent.common.exception.GlobalExceptionHandler;
import com.devops.agent.domain.approval.ApprovalRequest;
import com.devops.agent.domain.approval.ApprovalService;
import com.devops.agent.domain.auth.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link ApprovalController} HTTP 契约测试。
 *
 * <h3>这组端点是「AI 能不能动生产系统」的最后一道闸门</h3>
 * 蓝图 §二规定 P0/P1 高危动作「必须由人工专家审查确认点击后，AI 才可执行」。
 * 本控制器就是那个「点击」的落点——批准之后编排层会<b>立即重放执行</b>被拦下的动作。
 *
 * <p>因此这里的契约错误不是「页面显示不对」，而是<b>授权语义被改变却无人察觉</b>。</p>
 *
 * <h3>覆盖重点</h3>
 * <ol>
 *   <li><b>审批人身份不接受前端传入</b>——取自 Sa-Token 登录态。
 *       若哪天为了「方便测试」加个 {@code approver} 请求参数，
 *       审批记录就可以被伪造，而审批记录正是事后追责的唯一依据；</li>
 *   <li><b>驳回理由必填</b>——没有理由的驳回等于黑箱，
 *       提交人不知道为什么被拒，只能反复重提；</li>
 *   <li><b>「已被他人处理」必须与「不存在」区分</b>（40102 vs 40004）——
 *       两个管理员同时点批准时，第二个人需要知道「已经被处理了」，
 *       而不是「这条审批不存在」；</li>
 *   <li><b>批准成功但执行失败要如实说</b>——{@code status != EXECUTED} 时
 *       消息必须带出失败原因。人的决策已经生效（不回退），
 *       但动作没做成，这两件事必须同时告诉用户。</li>
 * </ol>
 *
 * <h3>关于权限：本类<b>不</b>验证 ADMIN 限制</h3>
 * 类级 {@code @SaCheckRole("ADMIN")} 由 Sa-Token 注解拦截器执行，
 * 而拦截器注册在 {@code WebConfig} 里——本切片刻意排除了它
 * （切片中缺少 Sa-Token 运行时上下文，不排除会让所有请求变成 401）。
 * 所以这里的请求都是「已放行」状态。权限必须由专门的鉴权集成测试覆盖。
 *
 * <p>相应地，{@code currentApprover()} 在切片里取不到登录态，
 * 会走它自己的兜底分支返回 {@code "unknown"}——这正好让我们能断言
 * 「审批人来自服务端而非请求体」这条契约。</p>
 */
@ExtendWith(SpringExtension.class)
@WebMvcTest(
        controllers = ApprovalController.class,
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
class ApprovalControllerWebTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private com.devops.agent.common.web.TraceIdFilter traceIdFilter;

    @Autowired
    private org.springframework.web.context.WebApplicationContext context;

    @MockitoBean
    private ApprovalService approvalService;

    @MockitoBean
    private ApprovalOrchestrator orchestrator;

    @MockitoBean
    private UserRepository userRepository;

    @BeforeEach
    void setUpMockMvc() {
        mockMvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .webAppContextSetup(context)
                .addFilters(traceIdFilter)
                .build();
    }

    private static ApprovalRequest approval(Long id, String status) {
        ApprovalRequest r = new ApprovalRequest();
        r.setId(id);
        r.setActionType("TOOL_CALL");
        r.setToolName("k8s.pod.restart");
        r.setRiskLevel("DESTRUCTIVE_HIGH_RISK");
        r.setSummary("重启 order-svc 的 pod-3");
        r.setRequester("ai-agent");
        r.setStatus(status);
        r.setTraceId("trace-1");
        r.setCreateTime(LocalDateTime.of(2026, 8, 25, 9, 0));
        return r;
    }

    private static Map<String, Object> page(List<ApprovalRequest> items, int total) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("items", items);
        m.put("total", total);
        m.put("page", 1);
        m.put("size", 20);
        return m;
    }

    private String json(Object o) throws Exception {
        return objectMapper.writeValueAsString(o);
    }

    // ==================================================================

    @Nested
    @DisplayName("列表与角标")
    class Listing {

        @Test
        @DisplayName("省略 status 默认查待审队列")
        void defaultsToPending() throws Exception {
            when(approvalService.listPending(1, 20))
                    .thenReturn(page(List.of(approval(1L, "PENDING")), 1));

            mockMvc.perform(get("/api/v1/approvals"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data.items[0].toolName").value("k8s.pod.restart"))
                    .andExpect(jsonPath("$.data.items[0].riskLevel").value("DESTRUCTIVE_HIGH_RISK"))
                    .andExpect(jsonPath("$.traceId").exists());

            verify(approvalService).listPending(1, 20);
            verify(approvalService, never()).listByStatus(anyString(), anyInt(), anyInt());
        }

        @Test
        @DisplayName("status=PENDING（含大小写变体）等价于默认，仍走待审队列的排序")
        void pendingIsCaseInsensitive() throws Exception {
            when(approvalService.listPending(anyInt(), anyInt())).thenReturn(page(List.of(), 0));

            mockMvc.perform(get("/api/v1/approvals").param("status", "pending"))
                    .andExpect(status().isOk());

            // 待审队列是「最早优先」，其他状态是「最新优先」——
            // 走错分支会让排队最久的审批沉到列表底部
            verify(approvalService).listPending(1, 20);
        }

        @Test
        @DisplayName("status=ALL 传 null 给 Service（表示不筛状态），而不是字符串 \"ALL\"")
        void allMeansNoFilter() throws Exception {
            when(approvalService.listByStatus(isNull(), anyInt(), anyInt()))
                    .thenReturn(page(List.of(), 0));

            mockMvc.perform(get("/api/v1/approvals").param("status", "all"))
                    .andExpect(status().isOk());

            // 若原样传 "ALL"，SQL 会去匹配一个名为 ALL 的状态，结果恒为空列表
            verify(approvalService).listByStatus(isNull(), eq(1), eq(20));
        }

        @Test
        @DisplayName("其他状态归一化为大写并去空格后透传")
        void otherStatusIsNormalized() throws Exception {
            when(approvalService.listByStatus(anyString(), anyInt(), anyInt()))
                    .thenReturn(page(List.of(), 0));

            mockMvc.perform(get("/api/v1/approvals").param("status", "  rejected  "))
                    .andExpect(status().isOk());

            verify(approvalService).listByStatus(eq("REJECTED"), eq(1), eq(20));
        }

        @Test
        @DisplayName("待审角标只取 total，不把整页数据拉回来")
        void pendingCountReturnsOnlyTotal() throws Exception {
            when(approvalService.listPending(1, 1)).thenReturn(page(List.of(), 7));

            mockMvc.perform(get("/api/v1/approvals/pending/count"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.pending").value(7));

            // size=1：角标只需要数字，拉整页是浪费（这个端点会被前端轮询）
            verify(approvalService).listPending(1, 1);
        }

        @Test
        @DisplayName("角标在 total 缺失时给 0，不给 null —— 前端角标不能渲染成 \"null\"")
        void pendingCountFallsBackToZero() throws Exception {
            when(approvalService.listPending(1, 1)).thenReturn(new LinkedHashMap<>());

            mockMvc.perform(get("/api/v1/approvals/pending/count"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.pending").value(0));
        }
    }

    @Nested
    @DisplayName("详情")
    class Detail {

        @Test
        @DisplayName("返回完整审批单，含决策依据字段")
        void returnsFullApproval() throws Exception {
            when(approvalService.getById(1L)).thenReturn(approval(1L, "PENDING"));

            mockMvc.perform(get("/api/v1/approvals/1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(1))
                    // 审批人要据此判断该不该批，这几个字段缺一不可
                    .andExpect(jsonPath("$.data.summary").isNotEmpty())
                    .andExpect(jsonPath("$.data.riskLevel").isNotEmpty())
                    .andExpect(jsonPath("$.data.requester").value("ai-agent"))
                    .andExpect(jsonPath("$.data.traceId").value("trace-1"));
        }

        @Test
        @DisplayName("不存在 → 40004")
        void notFoundMapsTo40004() throws Exception {
            when(approvalService.getById(anyLong()))
                    .thenThrow(new ApprovalService.ApprovalException("审批单不存在"));

            mockMvc.perform(get("/api/v1/approvals/999"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(40400));
        }

        @Test
        @DisplayName("ID 非数字 → 400，而不是被当成「审批单不存在」")
        void nonNumericIdIsBadRequest() throws Exception {
            mockMvc.perform(get("/api/v1/approvals/undefined"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(40001));
        }
    }

    @Nested
    @DisplayName("批准")
    class Approve {

        @Test
        @DisplayName("审批人取自服务端登录态，绝不接受请求体传入")
        void approverComesFromServerNotRequestBody() throws Exception {
            when(orchestrator.approveAndExecute(anyLong(), anyString(), any()))
                    .thenReturn(approval(1L, "EXECUTED"));

            // 请求体里塞一个伪造的审批人，它必须被忽略
            mockMvc.perform(post("/api/v1/approvals/1/approve")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("reason", "已确认影响面",
                                    "approver", "伪造的管理员"))))
                    .andExpect(status().isOk());

            // 切片里取不到登录态，走兜底返回 "unknown"——
            // 关键是它绝不会是请求体里那个值。
            // 审批记录是事后追责的唯一依据，可伪造等于整套审批形同虚设
            verify(orchestrator).approveAndExecute(eq(1L), eq("unknown"), eq("已确认影响面"));
        }

        @Test
        @DisplayName("执行成功：消息明确说「已批准并执行成功」")
        void executedSuccessfully() throws Exception {
            when(orchestrator.approveAndExecute(anyLong(), anyString(), any()))
                    .thenReturn(approval(1L, "EXECUTED"));

            mockMvc.perform(post("/api/v1/approvals/1/approve")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("reason", "ok"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.message").value("已批准并执行成功"));
        }

        @Test
        @DisplayName("批准了但执行失败：消息必须带出失败原因，不能只说「已批准」")
        void executeFailedStillReportsReason() throws Exception {
            ApprovalRequest failed = approval(1L, "EXECUTE_FAILED");
            failed.setExecuteResult("kubectl 超时");
            when(orchestrator.approveAndExecute(anyLong(), anyString(), any()))
                    .thenReturn(failed);

            mockMvc.perform(post("/api/v1/approvals/1/approve")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("reason", "ok"))))
                    .andExpect(status().isOk())
                    // 人的决策已生效（不回退），但动作没做成。
                    // 只说「已批准」会让管理员以为事情办完了，而故障还在
                    .andExpect(jsonPath("$.message").value(
                            org.hamcrest.Matchers.containsString("执行失败")))
                    .andExpect(jsonPath("$.message").value(
                            org.hamcrest.Matchers.containsString("kubectl 超时")));
        }

        @Test
        @DisplayName("无请求体也能批准（理由选填）")
        void bodyIsOptional() throws Exception {
            when(orchestrator.approveAndExecute(anyLong(), anyString(), any()))
                    .thenReturn(approval(1L, "EXECUTED"));

            mockMvc.perform(post("/api/v1/approvals/1/approve"))
                    .andExpect(status().isOk());

            verify(orchestrator).approveAndExecute(eq(1L), anyString(), isNull());
        }

        @Test
        @DisplayName("已被他人处理 → 40102，与「不存在」的 40004 区分开")
        void alreadyHandledMapsTo40102() throws Exception {
            when(orchestrator.approveAndExecute(anyLong(), anyString(), any()))
                    .thenThrow(new ApprovalService.ApprovalException("该审批已被他人处理"));

            mockMvc.perform(post("/api/v1/approvals/1/approve")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("reason", "ok"))))
                    .andExpect(status().isOk())
                    // 两个管理员同时点批准时，第二个人需要知道「已经被处理了」，
                    // 而不是「这条审批不存在」——后者会让他以为数据出了问题
                    .andExpect(jsonPath("$.code").value(40102));
        }

        @Test
        @DisplayName("审批单不存在 → 40004")
        void missingApprovalMapsTo40004() throws Exception {
            when(orchestrator.approveAndExecute(anyLong(), anyString(), any()))
                    .thenThrow(new ApprovalService.ApprovalException("审批单不存在"));

            mockMvc.perform(post("/api/v1/approvals/1/approve")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("reason", "ok"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(40400));
        }

        @Test
        @DisplayName("未预期异常 → 50001，且不透传内部细节")
        void unexpectedErrorMapsTo50001() throws Exception {
            when(orchestrator.approveAndExecute(anyLong(), anyString(), any()))
                    .thenThrow(new RuntimeException("java.sql.SQLException: relation sys_approval"));

            mockMvc.perform(post("/api/v1/approvals/1/approve")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("reason", "ok"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(50001))
                    .andExpect(jsonPath("$.message").value(
                            org.hamcrest.Matchers.not(
                                    org.hamcrest.Matchers.containsString("sys_approval"))));
        }
    }

    @Nested
    @DisplayName("驳回")
    class Reject {

        @Test
        @DisplayName("驳回成功并透传理由")
        void rejectPassesReason() throws Exception {
            when(orchestrator.reject(anyLong(), anyString(), anyString()))
                    .thenReturn(approval(1L, "REJECTED"));

            mockMvc.perform(post("/api/v1/approvals/1/reject")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("reason", "影响面过大，改走灰度"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("已驳回"))
                    .andExpect(jsonPath("$.data.status").value("REJECTED"));

            verify(orchestrator).reject(eq(1L), eq("unknown"), eq("影响面过大，改走灰度"));
        }

        @Test
        @DisplayName("缺理由 → 40001（没有理由的驳回是黑箱，提交人只能反复重提）")
        void missingReasonMapsTo40001() throws Exception {
            when(orchestrator.reject(anyLong(), anyString(), any()))
                    .thenThrow(new ApprovalService.ApprovalException("驳回理由不能为空"));

            mockMvc.perform(post("/api/v1/approvals/1/reject")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(new LinkedHashMap<String, Object>())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(40001));
        }

        @Test
        @DisplayName("驳回也走服务端身份，不接受伪造")
        void rejectApproverIsServerSide() throws Exception {
            when(orchestrator.reject(anyLong(), anyString(), anyString()))
                    .thenReturn(approval(1L, "REJECTED"));

            mockMvc.perform(post("/api/v1/approvals/1/reject")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("reason", "不批", "approver", "伪造者"))))
                    .andExpect(status().isOk());

            verify(orchestrator).reject(eq(1L), eq("unknown"), eq("不批"));
        }
    }
}
