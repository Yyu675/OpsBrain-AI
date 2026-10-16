package com.devops.agent.controller;

import com.devops.agent.application.memory.AgentMemoryManager;
import com.devops.agent.common.exception.GlobalExceptionHandler;
import com.devops.agent.domain.memory.KeyFacts;
import com.devops.agent.domain.memory.SessionSummary;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link SessionMemoryController} HTTP 契约测试。
 *
 * <h3>这组端点的特殊之处：分页参数直接参与 OFFSET 计算</h3>
 * 与其他控制器把分页交给 Repository 不同，本控制器<b>自己算 offset</b>：
 * {@code offset = (safePage - 1) * safeSize}。
 * 这行代码有两个只在边界上暴露的问题，且都不会报错：
 * <ul>
 *   <li>{@code page} 未钳制时 {@code page=0} 会算出 offset=-20，
 *       历史会话列表直接查询失败或返回错乱的一页；</li>
 *   <li>{@code size} 未钳制时 {@code size=100000} 会把整张会话表连同
 *       每条的摘要文本一次装进内存——摘要是 AI 生成的长文本，
 *       这不是「慢一点」而是直接把堆吃穿。</li>
 * </ul>
 * 钳制逻辑存在但无测试守着，删掉编译照样通过，所以这里逐条钉死。
 *
 * <h3>另一条容易被误改的语义：清热记忆 ≠ 清会话</h3>
 * {@code DELETE /{id}/hot-memory} 只清热记忆（最近对话原文），
 * <b>温记忆（关键事实）必须保留</b>——这是「重新开始但保留结论」场景。
 * 若有人顺手把它改成连温记忆一起清，用户点一下「清空对话」
 * 就会丢掉前面几十轮排障积累出的结论，且不可恢复。
 * 提示语里明确写着「温记忆（关键事实）保留」，这条用例守着它。
 *
 * <p>切片装配沿用 {@code TicketControllerWebTest} 的说明。</p>
 */
@ExtendWith(SpringExtension.class)
@WebMvcTest(
        controllers = SessionMemoryController.class,
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
class SessionMemoryControllerWebTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private com.devops.agent.common.web.TraceIdFilter traceIdFilter;

    @Autowired
    private org.springframework.web.context.WebApplicationContext context;

    @MockitoBean
    private AgentMemoryManager memoryManager;

    @BeforeEach
    void setUpMockMvc() {
        mockMvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .webAppContextSetup(context)
                .addFilters(traceIdFilter)
                .build();
    }

    private static SessionSummary session(String id, boolean withFacts) {
        SessionSummary s = new SessionSummary();
        s.setId(1L);
        s.setSessionId(id);
        s.setTraceId("trace-" + id);
        s.setSummary("排查 MySQL 连接池耗尽");
        s.setTurnCount(12);
        s.setTotalTokens(8400);
        s.setTotalCostRmb(0.36);
        s.setFinalState("RESOLVED");
        s.setRelatedTickets("TK-2026-0001");
        s.setCreateTime(LocalDateTime.of(2026, 8, 25, 9, 0));
        s.setUpdateTime(LocalDateTime.of(2026, 8, 25, 9, 40));
        if (withFacts) {
            KeyFacts f = new KeyFacts();
            f.setIntent("定位连接池耗尽根因");
            f.setConfirmedFacts(List.of("max_connections=200", "应用侧未复用连接"));
            f.setConclusion("连接未归还，需修复 DAO 层 try-with-resources");
            f.setPendingRisks(List.of("修复前需评估重启影响"));
            s.setKeyFacts(f);
        }
        return s;
    }

    // ==================================================================

    @Nested
    @DisplayName("历史会话列表")
    class ListSessions {

        @Test
        @DisplayName("默认第 1 页 20 条、租户 default，offset 从 0 开始")
        void defaultPaging() throws Exception {
            when(memoryManager.listRecentSessions("default", 20, 0))
                    .thenReturn(List.of(session("s-1", true)));
            when(memoryManager.countSessions("default")).thenReturn(1L);

            mockMvc.perform(get("/api/v1/sessions"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data.page").value(1))
                    .andExpect(jsonPath("$.data.size").value(20))
                    .andExpect(jsonPath("$.data.total").value(1))
                    .andExpect(jsonPath("$.data.totalPages").value(1))
                    .andExpect(jsonPath("$.data.sessions[0].sessionId").value("s-1"))
                    .andExpect(jsonPath("$.traceId").exists());

            verify(memoryManager).listRecentSessions("default", 20, 0);
        }

        @Test
        @DisplayName("offset 由 page/size 正确推导：第 3 页 × 10 条 → offset 20")
        void computesOffset() throws Exception {
            when(memoryManager.listRecentSessions(anyString(), anyInt(), anyInt()))
                    .thenReturn(List.of());
            when(memoryManager.countSessions(anyString())).thenReturn(0L);

            mockMvc.perform(get("/api/v1/sessions")
                            .param("page", "3")
                            .param("size", "10"))
                    .andExpect(status().isOk());

            verify(memoryManager).listRecentSessions("default", 10, 20);
        }

        @Test
        @DisplayName("page<1 夹到 1，offset 不会变成负数")
        void clampsPage() throws Exception {
            when(memoryManager.listRecentSessions(anyString(), anyInt(), anyInt()))
                    .thenReturn(List.of());
            when(memoryManager.countSessions(anyString())).thenReturn(0L);

            mockMvc.perform(get("/api/v1/sessions").param("page", "0"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.page").value(1));

            // 关键：offset 必须是 0 而不是 -20
            verify(memoryManager).listRecentSessions("default", 20, 0);
        }

        @Test
        @DisplayName("size 夹到 [1, 100] —— 上限防的是把整张会话表连同长摘要装进内存")
        void clampsSize() throws Exception {
            when(memoryManager.listRecentSessions(anyString(), anyInt(), anyInt()))
                    .thenReturn(List.of());
            when(memoryManager.countSessions(anyString())).thenReturn(0L);

            mockMvc.perform(get("/api/v1/sessions").param("size", "100000"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.size").value(100));
            verify(memoryManager).listRecentSessions("default", 100, 0);

            mockMvc.perform(get("/api/v1/sessions").param("size", "0"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.size").value(1));
            verify(memoryManager).listRecentSessions("default", 1, 0);
        }

        @Test
        @DisplayName("tenantId 透传（多租户预留），不被默认值覆盖")
        void passesTenantId() throws Exception {
            when(memoryManager.listRecentSessions(eq("acme"), anyInt(), anyInt()))
                    .thenReturn(List.of());
            when(memoryManager.countSessions("acme")).thenReturn(0L);

            mockMvc.perform(get("/api/v1/sessions").param("tenantId", "acme"))
                    .andExpect(status().isOk());

            verify(memoryManager).listRecentSessions("acme", 20, 0);
            verify(memoryManager).countSessions("acme");
        }

        @Test
        @DisplayName("totalPages 用 total 与生效 size 计算：21 条 × 每页 20 → 2 页")
        void totalPagesUsesEffectiveSize() throws Exception {
            when(memoryManager.listRecentSessions(anyString(), anyInt(), anyInt()))
                    .thenReturn(List.of());
            when(memoryManager.countSessions("default")).thenReturn(21L);

            mockMvc.perform(get("/api/v1/sessions"))
                    .andExpect(status().isOk())
                    // 若用请求里的原始 size 而非钳制后的值算，翻页控件会给出根本翻不到的页码
                    .andExpect(jsonPath("$.data.totalPages").value(2));
        }

        @Test
        @DisplayName("列表项带成本与轮次 —— 用户据此判断哪次会话值得续聊")
        void briefCarriesCostAndTurns() throws Exception {
            when(memoryManager.listRecentSessions(anyString(), anyInt(), anyInt()))
                    .thenReturn(List.of(session("s-1", true)));
            when(memoryManager.countSessions(anyString())).thenReturn(1L);

            mockMvc.perform(get("/api/v1/sessions"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.sessions[0].turnCount").value(12))
                    .andExpect(jsonPath("$.data.sessions[0].totalTokens").value(8400))
                    .andExpect(jsonPath("$.data.sessions[0].totalCostRmb").value(0.36))
                    .andExpect(jsonPath("$.data.sessions[0].finalState").value("RESOLVED"))
                    .andExpect(jsonPath("$.data.sessions[0].relatedTickets").value("TK-2026-0001"))
                    // 关键事实用于列表页的标签展示
                    .andExpect(jsonPath("$.data.sessions[0].keyFacts.conclusion").isNotEmpty())
                    .andExpect(jsonPath("$.data.sessions[0].keyFacts.confirmedFacts").isArray());
        }

        @Test
        @DisplayName("keyFacts 为 null 时整个字段缺席，而不是给一个各项为 null 的空壳")
        void nullKeyFactsIsOmitted() throws Exception {
            when(memoryManager.listRecentSessions(anyString(), anyInt(), anyInt()))
                    .thenReturn(List.of(session("s-2", false)));
            when(memoryManager.countSessions(anyString())).thenReturn(1L);

            mockMvc.perform(get("/api/v1/sessions"))
                    .andExpect(status().isOk())
                    // 空壳会让前端渲染出一排空标签，用户以为「这次会话什么结论都没得出」，
                    // 而实际是这次会话尚未生成摘要
                    .andExpect(jsonPath("$.data.sessions[0].keyFacts").doesNotExist())
                    .andExpect(jsonPath("$.data.sessions[0].sessionId").value("s-2"));
        }

        @Test
        @DisplayName("page 传非数字 → 400，而不是 500")
        void nonNumericPageIsBadRequest() throws Exception {
            mockMvc.perform(get("/api/v1/sessions").param("page", "first"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(40001));
        }
    }

    @Nested
    @DisplayName("会话上下文（续聊预览）")
    class Context {

        @Test
        @DisplayName("热记忆与温记忆一并返回，hasHistory 为真")
        void returnsBothMemories() throws Exception {
            when(memoryManager.loadContext("s-1")).thenReturn(
                    new AgentMemoryManager.MemoryContext(
                            List.of("用户：连接池爆了", "助手：先看 max_connections"),
                            "已确认：max_connections=200"));

            mockMvc.perform(get("/api/v1/sessions/s-1/context"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.sessionId").value("s-1"))
                    .andExpect(jsonPath("$.data.recentHistory.length()").value(2))
                    .andExpect(jsonPath("$.data.keyFactsText").isNotEmpty())
                    .andExpect(jsonPath("$.data.hasHistory").value(true));
        }

        @Test
        @DisplayName("只有温记忆时 hasHistory 仍为真 —— 热记忆过期不等于无法续聊")
        void warmMemoryAloneStillHasHistory() throws Exception {
            when(memoryManager.loadContext("s-1")).thenReturn(
                    new AgentMemoryManager.MemoryContext(List.of(), "已确认：max_connections=200"));

            mockMvc.perform(get("/api/v1/sessions/s-1/context"))
                    .andExpect(status().isOk())
                    // 若这里给 false，前端会把一个还能基于结论续聊的会话
                    // 显示成「无历史」，用户只能从头再问一遍
                    .andExpect(jsonPath("$.data.hasHistory").value(true))
                    .andExpect(jsonPath("$.data.recentHistory").isEmpty());
        }

        @Test
        @DisplayName("两种记忆都空时 hasHistory 为假，如实告知")
        void emptyContextReportsNoHistory() throws Exception {
            when(memoryManager.loadContext(anyString())).thenReturn(
                    new AgentMemoryManager.MemoryContext(List.of(), null));

            mockMvc.perform(get("/api/v1/sessions/s-unknown/context"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.hasHistory").value(false))
                    .andExpect(jsonPath("$.data.keyFactsText").doesNotExist());
        }

        @Test
        @DisplayName("空白的 keyFactsText 不算有历史（全是空格的摘要等于没摘要）")
        void blankFactsTextIsNotHistory() throws Exception {
            when(memoryManager.loadContext(anyString())).thenReturn(
                    new AgentMemoryManager.MemoryContext(List.of(), "   "));

            mockMvc.perform(get("/api/v1/sessions/s-blank/context"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.hasHistory").value(false));
        }
    }

    @Nested
    @DisplayName("清除热记忆")
    class ClearHotMemory {

        @Test
        @DisplayName("只清热记忆，且提示语必须写明温记忆保留")
        void clearsHotMemoryOnly() throws Exception {
            mockMvc.perform(delete("/api/v1/sessions/s-1/hot-memory"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0))
                    // 提示语是用户判断「我这一下会丢什么」的唯一依据。
                    // 若有人把这个端点改成连温记忆一起清，
                    // 用户点一下「清空对话」就会丢掉几十轮排障积累的结论
                    .andExpect(jsonPath("$.data").value(
                            org.hamcrest.Matchers.containsString("温记忆")));

            verify(memoryManager).clearHotMemory("s-1");
        }

        @Test
        @DisplayName("清除不存在的会话不报错（幂等）—— 重复点击不该弹错误")
        void clearIsIdempotent() throws Exception {
            mockMvc.perform(delete("/api/v1/sessions/never-existed/hot-memory"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0));

            verify(memoryManager).clearHotMemory("never-existed");
        }
    }
}
