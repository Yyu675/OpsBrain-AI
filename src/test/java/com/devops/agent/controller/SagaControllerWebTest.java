package com.devops.agent.controller;

import com.devops.agent.application.runtime.SagaCompensationManager;
import com.devops.agent.common.exception.GlobalExceptionHandler;
import com.devops.agent.domain.tools.ToolExecutionRecord;
import com.devops.agent.domain.tools.ToolExecutionState;
import com.devops.agent.domain.tools.ToolFailureType;
import com.devops.agent.domain.tools.ToolRiskLevel;
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
import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link SagaController} HTTP 契约测试。
 *
 * <h3>这组端点是「自动化收不了尾时」的最后兜底</h3>
 * Saga 补偿失败意味着系统处于<b>半残状态</b>：某些副作用已经产生但没能回滚。
 * 按 Agent Methodology §9.4，这时会标记 {@code MANUAL_INTERVENTION_REQUIRED}
 * 并通知人——<b>但如果没有配套的人工处理入口，脏数据就永久残留</b>。
 * 这三个端点就是那个入口。
 *
 * <h3>覆盖重点：状态的可读性，而不只是状态本身</h3>
 * 本控制器的 {@code toDetail} 把枚举同时输出为<b>机器码 + 中文标签 + 处置提示</b>：
 * <ul>
 *   <li>{@code state} / {@code stateLabel} —— 「COMPENSATION_FAILED」与「补偿失败」；</li>
 *   <li>{@code failureType} / {@code failureHint} —— 「TIMEOUT」与
 *       「幂等工具可重试，非幂等需人工确认」。</li>
 * </ul>
 * <p>这不是冗余。看这个页面的人正在处理一堆脏数据，
 * {@code failureHint} 直接告诉他<b>这一条该不该重试</b>——
 * 少了它，运维得自己去翻代码里的枚举定义才知道 TIMEOUT 能不能重试。
 * 所以这几个字段是契约的一部分，有用例逐个钉住。</p>
 *
 * <h3>测试边界声明：这里<b>不</b>验证 ADMIN 权限</h3>
 * 本控制器标了 {@code @SaCheckRole("ADMIN")}，但该注解由 Sa-Token 的注解拦截器执行，
 * 而拦截器注册在 {@code WebConfig} 里——本切片刻意排除了它
 * （切片中缺少 Sa-Token 运行时上下文，不排除会让所有请求变成 401，
 * 把「契约是否正确」的断言变成「鉴权是否配好」的噪音）。
 *
 * <p><b>因此本类里的请求都是「已放行」状态，不构成对权限的任何保证。</b>
 * 与 {@code AuditLogControllerWebTest} / {@code AgentTraceControllerWebTest} 同样的处理。
 * Saga 端点会执行逆向补偿（回滚已落库的写操作），其权限须由专门的鉴权集成测试覆盖。</p>
 *
 * <h3>另一条：needsAttention 必须来自枚举而不是前端硬编码</h3>
 * 哪些状态算「需要关注」是领域知识（PARTIAL_SUCCESS / COMPENSATION_FAILED /
 * MANUAL_INTERVENTION_REQUIRED 三个）。前端若自己维护一份清单，
 * 后端新增一个需关注的状态时前端不会同步，那条记录就<b>静默地不出现在待处理列表里</b>。
 */
@ExtendWith(SpringExtension.class)
@WebMvcTest(
        controllers = SagaController.class,
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
class SagaControllerWebTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private com.devops.agent.common.web.TraceIdFilter traceIdFilter;

    @Autowired
    private org.springframework.web.context.WebApplicationContext context;

    @MockitoBean
    private SagaCompensationManager sagaManager;

    @BeforeEach
    void setUpMockMvc() {
        mockMvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .webAppContextSetup(context)
                .addFilters(traceIdFilter)
                .build();
    }

    // ==================== 夹具 ====================

    private static ToolExecutionRecord record(Long id, ToolExecutionState state,
                                              ToolFailureType failureType) {
        ToolExecutionRecord r = new ToolExecutionRecord();
        r.setId(id);
        r.setTraceId("trace-1");
        r.setSagaId("saga-1");
        r.setStepSeq(2);
        r.setToolName("createTicket");
        r.setRiskLevel(ToolRiskLevel.CONTROLLED_WRITE);
        r.setState(state);
        r.setFailureType(failureType);
        r.setErrorMessage("下游数据库连接超时");
        r.setCompensable(Boolean.TRUE);
        r.setCompensationAction("voidTicket");
        r.setBusinessKey("TK-2026-0001");
        r.setAttemptCount(2);
        r.setDurationMs(3500);
        r.setCreateTime(LocalDateTime.of(2026, 8, 25, 9, 0));
        return r;
    }

    // ==================================================================

    @Nested
    @DisplayName("待人工介入清单")
    class Attention {

        @Test
        @DisplayName("状态与失败类型同时给出机器码、中文标签与处置提示")
        void detailCarriesCodeLabelAndHint() throws Exception {
            when(sagaManager.listNeedingAttention(anyInt())).thenReturn(
                    List.of(record(1L, ToolExecutionState.COMPENSATION_FAILED,
                            ToolFailureType.TIMEOUT)));

            mockMvc.perform(get("/api/v1/saga/attention"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data.count").value(1))
                    // 机器码给程序判断
                    .andExpect(jsonPath("$.data.records[0].state").value("COMPENSATION_FAILED"))
                    // 中文标签给人看，避免运维对着英文枚举猜
                    .andExpect(jsonPath("$.data.records[0].stateLabel").value("补偿失败"))
                    .andExpect(jsonPath("$.data.records[0].failureType").value("TIMEOUT"))
                    // 处置提示是这个页面最有用的一列：直接回答「这条该不该重试」
                    .andExpect(jsonPath("$.data.records[0].failureHint").value(
                            org.hamcrest.Matchers.containsString("重试")))
                    .andExpect(jsonPath("$.traceId").exists());
        }

        @Test
        @DisplayName("needsAttention 由后端枚举给出 —— 前端硬编码会漏掉后端新增的状态")
        void needsAttentionComesFromBackend() throws Exception {
            when(sagaManager.listNeedingAttention(anyInt())).thenReturn(
                    List.of(record(1L, ToolExecutionState.MANUAL_INTERVENTION_REQUIRED,
                            ToolFailureType.PERMISSION_DENIED)));

            mockMvc.perform(get("/api/v1/saga/attention"))
                    .andExpect(status().isOk())
                    // 哪些状态算「需要关注」是领域知识。前端若自己维护清单，
                    // 后端新增一个需关注状态时前端不会同步，
                    // 那条记录就静默地不出现在待处理列表里
                    .andExpect(jsonPath("$.data.records[0].needsAttention").value(true));
        }

        @Test
        @DisplayName("补偿相关字段齐备 —— 运维据此判断能否重试、影响了哪条业务数据")
        void compensationFieldsArePresent() throws Exception {
            ToolExecutionRecord r = record(1L, ToolExecutionState.COMPENSATION_FAILED,
                    ToolFailureType.SERVICE_UNAVAILABLE);
            r.setCompensationError("voidTicket 调用失败：连接被拒");
            when(sagaManager.listNeedingAttention(anyInt())).thenReturn(List.of(r));

            mockMvc.perform(get("/api/v1/saga/attention"))
                    .andExpect(status().isOk())
                    // 能不能补偿
                    .andExpect(jsonPath("$.data.records[0].compensable").value(true))
                    // 补偿要调什么
                    .andExpect(jsonPath("$.data.records[0].compensationAction").value("voidTicket"))
                    // 影响了哪条业务数据——没有它，运维不知道该去查哪张工单
                    .andExpect(jsonPath("$.data.records[0].businessKey").value("TK-2026-0001"))
                    // 上次补偿为什么失败
                    .andExpect(jsonPath("$.data.records[0].compensationError").isNotEmpty())
                    .andExpect(jsonPath("$.data.records[0].attemptCount").value(2));
        }

        @Test
        @DisplayName("limit 夹到 [1,200]")
        void clampsLimit() throws Exception {
            when(sagaManager.listNeedingAttention(anyInt())).thenReturn(List.of());

            mockMvc.perform(get("/api/v1/saga/attention").param("limit", "9999"))
                    .andExpect(status().isOk());
            verify(sagaManager).listNeedingAttention(200);

            mockMvc.perform(get("/api/v1/saga/attention").param("limit", "0"))
                    .andExpect(status().isOk());
            verify(sagaManager).listNeedingAttention(1);
        }

        @Test
        @DisplayName("默认 limit=50")
        void defaultLimit() throws Exception {
            when(sagaManager.listNeedingAttention(anyInt())).thenReturn(List.of());

            mockMvc.perform(get("/api/v1/saga/attention"))
                    .andExpect(status().isOk());

            verify(sagaManager).listNeedingAttention(50);
        }

        @Test
        @DisplayName("无脏数据时 count=0 且 records 为空数组 —— 这是好消息，不是错误")
        void emptyAttentionListIsHealthy() throws Exception {
            when(sagaManager.listNeedingAttention(anyInt())).thenReturn(List.of());

            mockMvc.perform(get("/api/v1/saga/attention"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data.count").value(0))
                    .andExpect(jsonPath("$.data.records").isArray())
                    .andExpect(jsonPath("$.data.records").isEmpty());
        }

        @Test
        @DisplayName("枚举为 null 时字段为 null 而非崩溃（历史数据可能缺字段）")
        void nullEnumsDoNotBreakSerialization() throws Exception {
            ToolExecutionRecord r = new ToolExecutionRecord();
            r.setId(9L);
            r.setSagaId("saga-old");
            // state / riskLevel / failureType 全为 null——早期写入的历史记录
            when(sagaManager.listNeedingAttention(anyInt())).thenReturn(List.of(r));

            mockMvc.perform(get("/api/v1/saga/attention"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.records[0].id").value(9))
                    .andExpect(jsonPath("$.data.records[0].state").doesNotExist())
                    .andExpect(jsonPath("$.data.records[0].stateLabel").doesNotExist())
                    // needsAttention 在 state 为 null 时必须是 false，不能抛 NPE
                    .andExpect(jsonPath("$.data.records[0].needsAttention").value(false));
        }

        @Test
        @DisplayName("limit 传非数字 → 400，而不是 500")
        void nonNumericLimitIsBadRequest() throws Exception {
            mockMvc.perform(get("/api/v1/saga/attention").param("limit", "all"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(40001));
        }
    }

    @Nested
    @DisplayName("Saga 执行链路回放")
    class Steps {

        @Test
        @DisplayName("返回 sagaId 与步骤序号 —— 补偿按 stepSeq 逆序执行，顺序信息不能丢")
        void stepsCarrySequence() throws Exception {
            when(sagaManager.listSagaSteps("saga-1")).thenReturn(List.of(
                    record(1L, ToolExecutionState.SUCCESS, null),
                    record(2L, ToolExecutionState.PARTIAL_SUCCESS, ToolFailureType.TIMEOUT)));

            mockMvc.perform(get("/api/v1/saga/saga-1/steps"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.sagaId").value("saga-1"))
                    .andExpect(jsonPath("$.data.stepCount").value(2))
                    .andExpect(jsonPath("$.data.steps[0].stepSeq").value(2))
                    .andExpect(jsonPath("$.data.steps[0].toolName").value("createTicket"));
        }

        @Test
        @DisplayName("未知 sagaId 返回空步骤而非 404 —— 链路可能已过保留期")
        void unknownSagaReturnsEmptySteps() throws Exception {
            when(sagaManager.listSagaSteps(anyString())).thenReturn(List.of());

            mockMvc.perform(get("/api/v1/saga/saga-gone/steps"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.sagaId").value("saga-gone"))
                    .andExpect(jsonPath("$.data.stepCount").value(0))
                    .andExpect(jsonPath("$.data.steps").isEmpty());
        }

        @Test
        @DisplayName("成功步骤的 needsAttention 为 false，不混进待处理清单")
        void successStepNeedsNoAttention() throws Exception {
            when(sagaManager.listSagaSteps(anyString())).thenReturn(
                    List.of(record(1L, ToolExecutionState.SUCCESS, null)));

            mockMvc.perform(get("/api/v1/saga/saga-1/steps"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.steps[0].needsAttention").value(false))
                    .andExpect(jsonPath("$.data.steps[0].stateLabel").value("成功"))
                    // 没有失败类型时该字段缺席，不能给个空串冒充
                    .andExpect(jsonPath("$.data.steps[0].failureType").doesNotExist());
        }
    }

    @Nested
    @DisplayName("手动重试补偿")
    class RetryCompensation {

        @Test
        @DisplayName("全部补偿成功：fullySucceeded=true 且 failedCount=0")
        void fullySucceeded() throws Exception {
            when(sagaManager.compensateSaga(eq("saga-1"), anyString()))
                    .thenReturn(new SagaCompensationManager.CompensationResult(
                            2, 0, List.of("voidTicket", "rollbackConfig"), List.of()));

            mockMvc.perform(post("/api/v1/saga/saga-1/compensate"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data.compensatedCount").value(2))
                    .andExpect(jsonPath("$.data.failedCount").value(0))
                    .andExpect(jsonPath("$.data.fullySucceeded").value(true))
                    .andExpect(jsonPath("$.data.compensated[0]").value("voidTicket"));
        }

        @Test
        @DisplayName("部分失败：仍返回 code=0，但 failedCount>0 且列出失败项")
        void partialFailureIsReportedHonestly() throws Exception {
            when(sagaManager.compensateSaga(anyString(), anyString()))
                    .thenReturn(new SagaCompensationManager.CompensationResult(
                            1, 1, List.of("voidTicket"), List.of("rollbackConfig")));

            mockMvc.perform(post("/api/v1/saga/saga-1/compensate"))
                    .andExpect(status().isOk())
                    // 重试这个动作本身执行完了，失败的是其中一步。
                    // 报成整体错误会让运维以为「没跑起来」而反复点，
                    // 实际上每点一次都在重跑那些已经成功的补偿
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data.compensatedCount").value(1))
                    .andExpect(jsonPath("$.data.failedCount").value(1))
                    .andExpect(jsonPath("$.data.fullySucceeded").value(false))
                    // 失败项必须列出来，否则运维不知道还剩哪一步没回滚
                    .andExpect(jsonPath("$.data.failed[0]").value("rollbackConfig"));
        }

        @Test
        @DisplayName("无可补偿步骤：计数为 0 且 fullySucceeded=true（幂等重复点击的正常结果）")
        void noopCompensationIsSuccess() throws Exception {
            when(sagaManager.compensateSaga(anyString(), anyString()))
                    .thenReturn(SagaCompensationManager.CompensationResult.noop("无可补偿步骤"));

            mockMvc.perform(post("/api/v1/saga/saga-1/compensate"))
                    .andExpect(status().isOk())
                    // 补偿动作幂等，重复执行安全。第二次点击时没有可补偿的步骤，
                    // 这是正确结果而不是错误——报错会让运维以为出了新问题
                    .andExpect(jsonPath("$.data.compensatedCount").value(0))
                    .andExpect(jsonPath("$.data.failedCount").value(0))
                    .andExpect(jsonPath("$.data.fullySucceeded").value(true));
        }

        @Test
        @DisplayName("重试携带人工触发的原因，便于审计区分自动补偿与人工补偿")
        void retryPassesHumanTriggeredReason() throws Exception {
            when(sagaManager.compensateSaga(anyString(), anyString()))
                    .thenReturn(SagaCompensationManager.CompensationResult.noop("x"));

            mockMvc.perform(post("/api/v1/saga/saga-1/compensate"))
                    .andExpect(status().isOk());

            // 原因字符串会进审计。事后查「这次回滚是谁发起的」时，
            // 自动补偿与人工补偿必须能区分开
            verify(sagaManager).compensateSaga(eq("saga-1"), eq("人工触发补偿重试"));
        }

        @Test
        @DisplayName("补偿端点只接受 POST，GET 返回 405 且不触发任何补偿")
        void getDoesNotTriggerCompensation() throws Exception {
            mockMvc.perform(get("/api/v1/saga/saga-1/compensate"))
                    .andExpect(status().isMethodNotAllowed());

            // 补偿是写操作。若能被 GET 触发，浏览器预取、爬虫、
            // 甚至一次误粘贴到地址栏都会回滚生产数据
            verify(sagaManager, never()).compensateSaga(anyString(), anyString());
        }
    }
}
