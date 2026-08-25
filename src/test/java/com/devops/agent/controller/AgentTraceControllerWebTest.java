package com.devops.agent.controller;

import com.devops.agent.application.runtime.AgentState;
import com.devops.agent.application.runtime.AgentStateManager;
import com.devops.agent.application.runtime.AgentStateTransition;
import com.devops.agent.common.exception.GlobalExceptionHandler;
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

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link AgentTraceController} HTTP 契约测试。
 *
 * <h3>这个接口把「只进不出」的审计轨迹接了出来</h3>
 * {@code AgentStateManager} 一直忠实记录每次状态迁移，但在此之前
 * <b>没有任何生产代码读过它们</b>——数据只进不出，会话一被空闲清理就消失。
 * 出问题时运维只能靠 traceId 翻散落的日志手工拼链路。
 *
 * <h3>覆盖重点：「查不到」与「没有轨迹」必须能区分</h3>
 * 这两种情况都会返回空的 transitions 列表，但含义完全相反：
 * <ul>
 *   <li><b>会话不存在</b>（{@code found=false}）——已超 30 分钟被清理，
 *       或 traceId 抄错了。运维该去查别的地方；</li>
 *   <li><b>会话存在但零迁移</b>（{@code found=true}）——流程真的卡在最开始。
 *       这是个<b>真实的故障信号</b>。</li>
 * </ul>
 * <p>若混成同一个响应，运维会在错误的方向上排查很久。所以
 * {@code found} 字段和两句不同的 {@code message} 都是契约的一部分。</p>
 *
 * <h3>另一条：状态同时输出机器码与中文标签</h3>
 * 与 {@code SagaController} 同样的取舍——看这个页面的人正在排障，
 * 「TOOLS_RUNNING」和「工具执行中」都要给，否则得去翻枚举定义。
 */
@ExtendWith(SpringExtension.class)
@WebMvcTest(
        controllers = AgentTraceController.class,
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
class AgentTraceControllerWebTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private com.devops.agent.common.web.TraceIdFilter traceIdFilter;

    @Autowired
    private org.springframework.web.context.WebApplicationContext context;

    @MockitoBean
    private AgentStateManager stateManager;

    @BeforeEach
    void setUpMockMvc() {
        mockMvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .webAppContextSetup(context)
                .addFilters(traceIdFilter)
                .build();
    }

    private AgentStateTransition transition(AgentState from, AgentState to,
                                            AgentStateTransition.TriggerType trigger,
                                            String detail, long durationMs) {
        AgentStateTransition t = AgentStateTransition.of(
                "tr-1", "sess-1", from, to, trigger, detail, "SYSTEM", durationMs, null);
        return t;
    }

    // ==================== 轨迹查询 ====================

    @Nested
    @DisplayName("GET /api/v1/agent/traces/{traceId}")
    class GetTrace {

        @Test
        @DisplayName("会话不存在：found=false，并说明可能已被清理")
        void sessionNotFound() throws Exception {
            when(stateManager.getCurrentState("ghost")).thenReturn(null);

            mockMvc.perform(get("/api/v1/agent/traces/ghost"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.found").value(false))
                    .andExpect(jsonPath("$.data.currentState").doesNotExist())
                    .andExpect(jsonPath("$.data.transitions").isEmpty())
                    // 必须告诉运维「为什么查不到」，否则他会以为是接口坏了
                    .andExpect(jsonPath("$.data.message").value(
                            org.hamcrest.Matchers.containsString("会话不存在")));
        }

        @Test
        @DisplayName("会话存在但零迁移：found=true，与「查不到」区分开")
        void sessionExistsWithoutTransitions() throws Exception {
            when(stateManager.getCurrentState("t1")).thenReturn(AgentState.NEW);
            when(stateManager.exportTransitions("t1")).thenReturn(List.of());

            mockMvc.perform(get("/api/v1/agent/traces/t1"))
                    .andExpect(status().isOk())
                    // 这是真实的故障信号（流程卡在最开始），不能和「会话被清理」混为一谈
                    .andExpect(jsonPath("$.data.found").value(true))
                    .andExpect(jsonPath("$.data.currentState").value("NEW"))
                    .andExpect(jsonPath("$.data.transitionCount").value(0))
                    .andExpect(jsonPath("$.data.message").value(
                            org.hamcrest.Matchers.containsString("尚未发生任何状态迁移")));
        }

        @Test
        @DisplayName("正常轨迹：按顺序返回，字段完整")
        void returnsFullTrail() throws Exception {
            when(stateManager.getCurrentState("t2")).thenReturn(AgentState.SUCCESS);
            when(stateManager.exportTransitions("t2")).thenReturn(List.of(
                    transition(AgentState.NEW, AgentState.CONTEXT_PREPARED,
                            AgentStateTransition.TriggerType.SECURITY_PASSED, "安全检查通过", 0),
                    transition(AgentState.CONTEXT_PREPARED, AgentState.SUCCESS,
                            AgentStateTransition.TriggerType.CACHE_HIT, "语义缓存命中", 120)));

            mockMvc.perform(get("/api/v1/agent/traces/t2"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.found").value(true))
                    .andExpect(jsonPath("$.data.transitionCount").value(2))
                    .andExpect(jsonPath("$.data.transitions[0].fromState").value("NEW"))
                    .andExpect(jsonPath("$.data.transitions[0].toState").value("CONTEXT_PREPARED"))
                    .andExpect(jsonPath("$.data.transitions[1].toState").value("SUCCESS"))
                    .andExpect(jsonPath("$.data.transitions[1].triggerType").value("CACHE_HIT"))
                    .andExpect(jsonPath("$.data.transitions[1].triggerDetail").value("语义缓存命中"))
                    .andExpect(jsonPath("$.data.transitions[1].operator").value("SYSTEM"));
        }

        @Test
        @DisplayName("状态同时给机器码与中文标签——排障的人不该去翻枚举定义")
        void exposesBothCodeAndLabel() throws Exception {
            when(stateManager.getCurrentState("t3")).thenReturn(AgentState.TOOLS_RUNNING);
            when(stateManager.exportTransitions("t3")).thenReturn(List.of(
                    transition(AgentState.EVIDENCE_READY, AgentState.TOOLS_RUNNING,
                            AgentStateTransition.TriggerType.TOOL_STARTED, "工具调用", 50)));

            mockMvc.perform(get("/api/v1/agent/traces/t3"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.currentState").value("TOOLS_RUNNING"))
                    .andExpect(jsonPath("$.data.currentStateText").value("工具执行中"))
                    .andExpect(jsonPath("$.data.transitions[0].fromStateText").value("证据就绪"))
                    .andExpect(jsonPath("$.data.transitions[0].toStateText").value("工具执行中"));
        }

        @Test
        @DisplayName("累计耗时为各段之和——用于定位哪一步最慢")
        void sumsDuration() throws Exception {
            when(stateManager.getCurrentState("t4")).thenReturn(AgentState.SUCCESS);
            when(stateManager.exportTransitions("t4")).thenReturn(List.of(
                    transition(AgentState.NEW, AgentState.CONTEXT_PREPARED,
                            AgentStateTransition.TriggerType.SECURITY_PASSED, "x", 10),
                    transition(AgentState.CONTEXT_PREPARED, AgentState.EVIDENCE_READY,
                            AgentStateTransition.TriggerType.RETRIEVAL_COMPLETED, "x", 200),
                    transition(AgentState.EVIDENCE_READY, AgentState.DRAFT_READY,
                            AgentStateTransition.TriggerType.DRAFT_GENERATED, "x", 1500)));

            mockMvc.perform(get("/api/v1/agent/traces/t4"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalDurationMs").value(1710));
        }

        @Test
        @DisplayName("terminal / settled 标志正确——前端据此决定是否继续轮询")
        void exposesTerminalAndSettled() throws Exception {
            // 补偿中：不是终态（还能走到 CLOSED），但已脱离自动流程
            when(stateManager.getCurrentState("t5")).thenReturn(AgentState.COMPENSATING);
            when(stateManager.exportTransitions("t5")).thenReturn(List.of());

            mockMvc.perform(get("/api/v1/agent/traces/t5"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.terminal").value(false))
                    .andExpect(jsonPath("$.data.settled").value(true));
        }

        @Test
        @DisplayName("真终态 SUCCESS 时 terminal 与 settled 均为 true")
        void terminalStateFlags() throws Exception {
            when(stateManager.getCurrentState("t6")).thenReturn(AgentState.SUCCESS);
            when(stateManager.exportTransitions("t6")).thenReturn(List.of());

            mockMvc.perform(get("/api/v1/agent/traces/t6"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.terminal").value(true))
                    .andExpect(jsonPath("$.data.settled").value(true));
        }

        @Test
        @DisplayName("进行中的会话 settled=false")
        void inFlightIsNotSettled() throws Exception {
            when(stateManager.getCurrentState("t7")).thenReturn(AgentState.TOOLS_RUNNING);
            when(stateManager.exportTransitions("t7")).thenReturn(List.of());

            mockMvc.perform(get("/api/v1/agent/traces/t7"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.settled").value(false));
        }

        @Test
        @DisplayName("响应回传 traceId，便于前端对齐请求与结果")
        void echoesTraceId() throws Exception {
            when(stateManager.getCurrentState("abc-123")).thenReturn(null);

            mockMvc.perform(get("/api/v1/agent/traces/abc-123"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.traceId").value("abc-123"));
        }
    }

    // ==================== 会话统计 ====================

    @Nested
    @DisplayName("GET /api/v1/agent/traces/stats")
    class Stats {

        @Test
        @DisplayName("返回当前驻留会话数与空闲超时配置")
        void returnsSessionCount() throws Exception {
            when(stateManager.sessionCount()).thenReturn(42);

            // 这个数字只涨不跌就说明空闲清理失效了——
            // 那是一条随请求量线性增长的内存泄漏，早发现比等 OOM 好
            mockMvc.perform(get("/api/v1/agent/traces/stats"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.activeSessions").value(42))
                    .andExpect(jsonPath("$.data.idleTimeoutMinutes").value(30));
        }

        @Test
        @DisplayName("零会话时返回 0，而不是省略字段")
        void zeroIsARealValue() throws Exception {
            when(stateManager.sessionCount()).thenReturn(0);

            // 0 是有效读数，不能因为「假值」就不输出
            mockMvc.perform(get("/api/v1/agent/traces/stats"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.activeSessions").value(0));
        }
    }
}
