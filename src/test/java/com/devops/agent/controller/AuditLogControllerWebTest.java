package com.devops.agent.controller;

import com.devops.agent.common.exception.GlobalExceptionHandler;
import com.devops.agent.infrastructure.persistence.repo.AuditLogQueryRepository;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link AuditLogController} HTTP 契约测试。
 *
 * <h3>测试边界的一条重要声明：这里<b>不</b>验证 ADMIN 权限</h3>
 * 本控制器上标了 {@code @SaCheckRole("ADMIN")}，但该注解由 Sa-Token 的
 * 注解拦截器执行，而拦截器注册在 {@code WebConfig} 里——本切片刻意排除了它
 * （切片中缺少 Sa-Token 运行时上下文，不排除会让所有请求变成 401，
 * 把「契约是否正确」的断言变成「鉴权是否配好」的噪音）。
 *
 * <p><b>因此本类里的请求都是「已放行」状态，不构成对权限的任何保证。</b>
 * 写下这段是为了避免将来有人看到这组绿灯就以为权限有回归保护——
 * 审计日志含操作者、IP、请求摘要与 AI 问答原文，是全项目最敏感的数据，
 * 它的权限必须由专门的鉴权集成测试覆盖。</p>
 *
 * <h3>那么这里守什么</h3>
 * <ol>
 *   <li><b>时间参数的解析</b>——{@code from}/{@code to} 用
 *       {@code @DateTimeFormat(ISO.DATE_TIME)} 绑定。格式写错时必须是 400，
 *       而不是被当成 null 静默忽略：静默忽略会让用户以为自己筛了「最近一小时」，
 *       实际拿到的是全量数据的第一页，据此得出「这个时间段操作很少」的错误结论；</li>
 *   <li><b>统计与列表同源</b>——{@code /ai-calls} 的 {@code stats} 必须用
 *       与列表<b>完全相同</b>的筛选条件计算。两者条件不一致会出现
 *       「列表 20 条、统计说 1 万次」这种自相矛盾，用户不知道该信哪个；</li>
 *   <li><b>traceId 下钻的空结果语义</b>——查不到时返回 40004 而非空对象。
 *       空对象会让页面渲染出一个「什么都没有的链路详情」，
 *       用户以为这次请求真的没产生任何记录，而实际原因是已过保留期。</li>
 * </ol>
 */
@ExtendWith(SpringExtension.class)
@WebMvcTest(
        controllers = AuditLogController.class,
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
class AuditLogControllerWebTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private com.devops.agent.common.web.TraceIdFilter traceIdFilter;

    @Autowired
    private org.springframework.web.context.WebApplicationContext context;

    /** @Repository 不会被 @WebMvcTest 切片实例化，必须显式 mock */
    @MockitoBean
    private AuditLogQueryRepository repository;

    @BeforeEach
    void setUpMockMvc() {
        mockMvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .webAppContextSetup(context)
                .addFilters(traceIdFilter)
                .build();
    }

    private static Map<String, Object> pageResult(List<Map<String, Object>> rows, int total) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("items", rows);
        m.put("total", total);
        m.put("page", 1);
        m.put("size", 20);
        return m;
    }

    // ==================================================================

    @Nested
    @DisplayName("操作审计查询")
    class Operations {

        @Test
        @DisplayName("无筛选时全部条件传 null，由 Repository 决定默认口径")
        void noFiltersPassesNulls() throws Exception {
            when(repository.queryOperationAudit(any(), any(), any(), any(), any(), any(), anyInt(), anyInt()))
                    .thenReturn(pageResult(List.of(), 0));

            mockMvc.perform(get("/api/v1/audit/operations"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data.total").value(0))
                    .andExpect(jsonPath("$.traceId").exists());

            verify(repository).queryOperationAudit(
                    isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), eq(1), eq(20));
        }

        @Test
        @DisplayName("全部筛选条件逐个透传（含 success=false 这个「有效的假值」）")
        void allFiltersArePassedThrough() throws Exception {
            when(repository.queryOperationAudit(any(), any(), any(), any(), any(), any(), anyInt(), anyInt()))
                    .thenReturn(pageResult(List.of(), 0));

            mockMvc.perform(get("/api/v1/audit/operations")
                            .param("actorId", "1001")
                            .param("action", "ticket.")
                            .param("targetType", "TICKET")
                            // false 最容易在绑定环节被当成 null 丢掉，
                            // 丢掉的后果是「只看失败操作」变成「看全部操作」——
                            // 排障时最需要的那一页反而被稀释了
                            .param("success", "false")
                            .param("from", "2026-08-01T00:00:00")
                            .param("to", "2026-08-25T23:59:59")
                            .param("page", "3")
                            .param("size", "50"))
                    .andExpect(status().isOk());

            verify(repository).queryOperationAudit(
                    eq("1001"), eq("ticket."), eq("TICKET"), eq(Boolean.FALSE),
                    eq(LocalDateTime.of(2026, 8, 1, 0, 0, 0)),
                    eq(LocalDateTime.of(2026, 8, 25, 23, 59, 59)),
                    eq(3), eq(50));
        }

        @Test
        @DisplayName("时间格式写错 → 400，绝不静默当成「未筛选」")
        void badDateFormatIsRejected() throws Exception {
            // 静默忽略会让用户以为筛了某个时间段，实际拿到全量数据的第一页，
            // 据此得出「这段时间操作很少」的错误结论
            mockMvc.perform(get("/api/v1/audit/operations").param("from", "2026/08/01"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(40001))
                    // 消息要指出是哪个参数，用户才知道改哪一个
                    .andExpect(jsonPath("$.message").value(
                            org.hamcrest.Matchers.containsString("from")));
        }

        @Test
        @DisplayName("success 传非布尔值 → 400，而不是悄悄按「不筛」处理")
        void badBooleanIsRejected() throws Exception {
            mockMvc.perform(get("/api/v1/audit/operations").param("success", "maybe"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(40001));
        }
    }

    @Nested
    @DisplayName("AI 调用日志")
    class AiCalls {

        @Test
        @DisplayName("stats 与列表用同一套筛选条件 —— 否则会出现「列表 20 条、统计 1 万次」")
        void statsShareTheSameFilters() throws Exception {
            when(repository.queryAgentCallLog(any(), any(), any(), any(), any(), any(), anyInt(), anyInt()))
                    .thenReturn(new LinkedHashMap<>(pageResult(List.of(), 0)));
            when(repository.queryAgentCallStats(any(), any(), any(), any(), any(), any()))
                    .thenReturn(Map.of("totalCalls", 42, "totalCostRmb", 1.25));

            mockMvc.perform(get("/api/v1/audit/ai-calls")
                            .param("modelName", "deepseek-chat")
                            .param("operationType", "CHAT")
                            .param("cached", "true")
                            .param("minLatencyMs", "2000"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.stats.totalCalls").value(42));

            // 两次调用的前四个筛选参数必须完全一致
            verify(repository).queryAgentCallLog(
                    eq("deepseek-chat"), eq("CHAT"), eq(Boolean.TRUE), eq(2000),
                    isNull(), isNull(), eq(1), eq(20));
            verify(repository).queryAgentCallStats(
                    eq("deepseek-chat"), eq("CHAT"), eq(Boolean.TRUE), eq(2000),
                    isNull(), isNull());
        }

        @Test
        @DisplayName("cached=false 是有效筛选（只看未命中缓存的调用），不能被当成 null")
        void cachedFalseIsMeaningful() throws Exception {
            when(repository.queryAgentCallLog(any(), any(), any(), any(), any(), any(), anyInt(), anyInt()))
                    .thenReturn(new LinkedHashMap<>(pageResult(List.of(), 0)));
            when(repository.queryAgentCallStats(any(), any(), any(), any(), any(), any()))
                    .thenReturn(Map.of());

            mockMvc.perform(get("/api/v1/audit/ai-calls").param("cached", "false"))
                    .andExpect(status().isOk());

            // 「只看没命中缓存的」正是排查成本异常时最常用的筛选，
            // 被当成 null 会让它退化成「看全部」
            verify(repository).queryAgentCallLog(
                    isNull(), isNull(), eq(Boolean.FALSE), isNull(), isNull(), isNull(), eq(1), eq(20));
        }

        @Test
        @DisplayName("stats 字段始终存在（哪怕为空 Map），前端不必判空")
        void statsAlwaysPresent() throws Exception {
            when(repository.queryAgentCallLog(any(), any(), any(), any(), any(), any(), anyInt(), anyInt()))
                    .thenReturn(new LinkedHashMap<>(pageResult(List.of(), 0)));
            when(repository.queryAgentCallStats(any(), any(), any(), any(), any(), any()))
                    .thenReturn(Map.of());

            mockMvc.perform(get("/api/v1/audit/ai-calls"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.stats").exists());
        }

        @Test
        @DisplayName("minLatencyMs 传非数字 → 400")
        void badLatencyIsRejected() throws Exception {
            mockMvc.perform(get("/api/v1/audit/ai-calls").param("minLatencyMs", "slow"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(40001));
        }
    }

    @Nested
    @DisplayName("traceId 链路下钻")
    class TraceDetail {

        @Test
        @DisplayName("AI 调用与操作审计一并返回 —— 这是本模块相对通用日志查看器的核心价值")
        void returnsBothSides() throws Exception {
            when(repository.findAgentCallByTraceId("t-1"))
                    .thenReturn(Map.of("id", 1, "modelName", "deepseek-chat"));
            when(repository.findAuditByTraceId("t-1"))
                    .thenReturn(List.of(Map.of("action", "ticket.create")));

            mockMvc.perform(get("/api/v1/audit/trace/t-1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.traceId").value("t-1"))
                    .andExpect(jsonPath("$.data.aiCall.modelName").value("deepseek-chat"))
                    .andExpect(jsonPath("$.data.operations[0].action").value("ticket.create"));
        }

        @Test
        @DisplayName("只有一侧有记录时仍返回 200，缺的那侧为 null/空数组")
        void oneSidedTraceIsStillValid() throws Exception {
            // 纯人工操作（未走 AI）只会留下审计记录。
            // 这不是异常，若判成 40004 会让所有人工操作都查不到链路
            when(repository.findAgentCallByTraceId("t-2")).thenReturn(null);
            when(repository.findAuditByTraceId("t-2"))
                    .thenReturn(List.of(Map.of("action", "ticket.close")));

            mockMvc.perform(get("/api/v1/audit/trace/t-2"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.aiCall").doesNotExist())
                    .andExpect(jsonPath("$.data.operations[0].action").value("ticket.close"));
        }

        @Test
        @DisplayName("两侧都没有 → 40004「可能已过保留期」，而不是空壳详情页")
        void emptyTraceMapsTo40004() throws Exception {
            when(repository.findAgentCallByTraceId(anyString())).thenReturn(null);
            when(repository.findAuditByTraceId(anyString())).thenReturn(List.of());

            mockMvc.perform(get("/api/v1/audit/trace/t-gone"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(40004))
                    // 「已过保留期」是用户能理解并据此行动的解释；
                    // 一个空壳页面只会让人以为这次请求什么都没做
                    .andExpect(jsonPath("$.message").value(
                            org.hamcrest.Matchers.containsString("保留期")));
        }
    }

    @Test
    @DisplayName("筛选候选值从实际数据聚合，不硬编码——避免列出库里根本没有的选项")
    void filterOptionsComeFromData() throws Exception {
        when(repository.queryFilterOptions()).thenReturn(Map.of(
                "actions", List.of("ticket.create", "ticket.close"),
                "models", List.of("deepseek-chat")));

        mockMvc.perform(get("/api/v1/audit/filter-options"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.actions").isArray())
                .andExpect(jsonPath("$.data.models[0]").value("deepseek-chat"));

        verify(repository).queryFilterOptions();
    }
}
