package com.devops.agent.controller;

import com.devops.agent.application.DevOpsAgentService;
import com.devops.agent.common.exception.GlobalExceptionHandler;
import com.devops.agent.common.web.ClientIpResolver;
import com.devops.agent.infrastructure.cache.SlidingWindowRateLimiter;
import com.devops.agent.domain.biz.service.AgentLogService;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link DevOpsChatController} SSE 端点测试 —— <b>拒绝路径与响应头</b>。
 *
 * <h3>先说清楚这个类<b>不</b>测什么，以及为什么</h3>
 * 之前几轮一直把这个 Controller 留着没做，理由是
 * 「{@code MockMvc} 对异步流支持有限，硬测只会得到几条断言 HTTP 200 的假测试」。
 * 那个判断依然成立——<b>正常对话路径不在本类覆盖范围内</b>：
 *
 * <p>正常路径的 {@code SseEmitter} 由 {@code DevOpsAgentService} 在
 * <b>另一个线程</b>里持续写入，直到模型流结束才 {@code complete()}。
 * {@code MockMvc} 的 {@code asyncDispatch} 需要请求先完成异步处理，
 * 而这里的「完成」依赖真实模型调用。强行断言只能验证「连接建立了」，
 * 验证不了<b>事件序列</b>（token 流、工具调用、完成事件的顺序与内容）——
 * 而 SSE 的价值恰恰在事件序列。那部分需要真实上下文的集成测试。</p>
 *
 * <h3>但有一类路径完全可以在这里测死：同步拒绝</h3>
 * 空查询与触发限流这两条，Controller <b>不进入 Agent 链路</b>，
 * 直接 {@code sendErrorEvent} + {@code complete()} 后返回 emitter。
 * 它们是同步完成的，因此 {@code MockMvc} 能拿到<b>完整的响应体</b>，
 * 可以逐字断言事件名与 JSON 内容。
 *
 * <p>而这两条恰恰是最该守的：</p>
 * <ul>
 *   <li><b>限流</b>——本端点是全项目最贵的一个：一次请求 = 一次真实 LLM 调用，
 *       耗时数十秒、按 token 计费、占用一个异步线程直到结束。
 *       限流失效时，一段循环脚本就能同时打爆额度、连接池与 Tomcat 异步容量；</li>
 *   <li><b>拒绝也必须走 SSE error 事件，不能用 HTTP 状态码</b>——
 *       响应此刻已是 {@code text/event-stream}，前端走的是 SSE 解析器，
 *       改用 429 状态码它收不到可读提示，只会看到「连接异常」；</li>
 *   <li><b>任何终止路径都要落审计</b>（6.6 铁律）。被限流拒绝的请求
 *       同样要留下记录，否则「有人在刷接口」这件事没有任何痕迹。</li>
 * </ul>
 */
@ExtendWith(SpringExtension.class)
@WebMvcTest(
        controllers = DevOpsChatController.class,
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
        "devops.ai.chat.rate-limit=20",
        "devops.ai.chat.rate-window-ms=60000",
        // 心跳调小无影响：本类只测同步拒绝路径，emitter 立即 complete
        "devops.ai.sse.heartbeat-interval-ms=15000",
        "devops.ai.sse.timeout-ms=150000"
})
@DisplayName("AI 对话 SSE · 拒绝路径与响应头")
class DevOpsChatControllerSseTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private com.devops.agent.common.web.TraceIdFilter traceIdFilter;

    @Autowired
    private org.springframework.web.context.WebApplicationContext context;

    @MockitoBean
    private DevOpsAgentService agentService;

    @MockitoBean
    private AgentLogService agentLogService;

    @MockitoBean
    private SlidingWindowRateLimiter rateLimiter;

    @MockitoBean
    private ClientIpResolver clientIpResolver;

    @BeforeEach
    void setUp() {
        mockMvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .webAppContextSetup(context)
                .addFilters(traceIdFilter)
                .build();
        when(clientIpResolver.resolve(any())).thenReturn("10.0.0.1");
        // 默认放行，需要测限流的用例单独覆盖
        when(rateLimiter.tryAcquire(anyString(), anyString(), anyInt(), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(true);
    }

    /** 从 SSE 响应体里解析出某个事件的 data JSON */
    private Map<String, Object> eventData(String body, String eventName) throws Exception {
        String[] lines = body.split("\n");
        for (int i = 0; i < lines.length; i++) {
            if (!lines[i].trim().equals("event:" + eventName)) continue;
            for (int j = i + 1; j < Math.min(i + 4, lines.length); j++) {
                String l = lines[j].trim();
                if (l.startsWith("data:")) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> m = objectMapper.readValue(l.substring(5).trim(), Map.class);
                    return m;
                }
            }
        }
        return null;
    }

    // ==================================================================

    @Nested
    @DisplayName("空查询：不进 Agent 链路，但必须留审计")
    class BlankQuery {

        @Test
        @DisplayName("空查询返回 error 事件（40001），且绝不调用 Agent")
        void blankQueryEmitsErrorEvent() throws Exception {
            MvcResult res = mockMvc.perform(get("/api/v1/chat/stream").param("query", "   "))
                    .andExpect(status().isOk())
                    .andReturn();

            String body = res.getResponse().getContentAsString();
            Map<String, Object> data = eventData(body, "error");

            assertThat(data).as("必须发出 error 事件而不是空响应").isNotNull();
            assertThat(data.get("code")).isEqualTo(40001);
            assertThat((String) data.get("message")).contains("输入不能为空");
            // traceId 让用户报障时能对上后端日志
            assertThat((String) data.get("traceId")).isNotBlank();

            // 空查询不该消耗一次 LLM 调用
            verify(agentService, never()).handleStreamChat(anyString(), anyString(), any());
        }

        @Test
        @DisplayName("空查询也要落审计（6.6：任何终止路径都要有记录）")
        void blankQueryIsAudited() throws Exception {
            mockMvc.perform(get("/api/v1/chat/stream").param("query", ""))
                    .andExpect(status().isOk());

            verify(agentLogService).saveLog(anyString(), any(), anyString(), anyString(),
                    anyBoolean(), anyInt(), anyDouble(), anyString(),
                    eq("REJECTED_SECURITY"), anyString(), anyString());
        }

        @Test
        @DisplayName("空查询不占用限流额度 —— 校验发生在限流之前")
        void blankQueryDoesNotConsumeQuota() throws Exception {
            mockMvc.perform(get("/api/v1/chat/stream").param("query", "  "))
                    .andExpect(status().isOk());

            // 顺序反了的话，用户手滑发一串空请求就会把自己的额度耗尽
            verify(rateLimiter, never()).tryAcquire(anyString(), anyString(),
                    anyInt(), org.mockito.ArgumentMatchers.anyLong());
        }
    }

    @Nested
    @DisplayName("限流：全项目最贵的端点")
    class RateLimit {

        @Test
        @DisplayName("触发限流返回 SSE error 事件（42901），而不是 HTTP 429")
        void rateLimitedEmitsSseErrorNotHttpStatus() throws Exception {
            when(rateLimiter.tryAcquire(eq("chat"), anyString(), anyInt(),
                    org.mockito.ArgumentMatchers.anyLong())).thenReturn(false);

            MvcResult res = mockMvc.perform(get("/api/v1/chat/stream").param("query", "帮我查一下"))
                    // 关键：HTTP 层仍是 200。响应此刻已是 text/event-stream，
                    // 前端走 SSE 解析器，改用 429 它收不到可读提示，只会看到「连接异常」
                    .andExpect(status().isOk())
                    .andReturn();

            Map<String, Object> data = eventData(res.getResponse().getContentAsString(), "error");
            assertThat(data).isNotNull();
            assertThat(data.get("code")).isEqualTo(42901);
        }

        @Test
        @DisplayName("限流提示要说清「每 N 秒最多 M 次」，而不是干巴巴一句「太频繁」")
        void rateLimitMessageIsActionable() throws Exception {
            when(rateLimiter.tryAcquire(anyString(), anyString(), anyInt(),
                    org.mockito.ArgumentMatchers.anyLong())).thenReturn(false);

            MvcResult res = mockMvc.perform(get("/api/v1/chat/stream").param("query", "x"))
                    .andReturn();

            String msg = (String) eventData(res.getResponse().getContentAsString(), "error")
                    .get("message");
            // 用户需要知道等多久才能再问，否则只能盲目重试
            assertThat(msg).contains("60").contains("20");
        }

        @Test
        @DisplayName("被限流的请求不进入 Agent 链路 —— 这正是限流的目的")
        void rateLimitedDoesNotCallAgent() throws Exception {
            when(rateLimiter.tryAcquire(anyString(), anyString(), anyInt(),
                    org.mockito.ArgumentMatchers.anyLong())).thenReturn(false);

            mockMvc.perform(get("/api/v1/chat/stream").param("query", "x"))
                    .andExpect(status().isOk());

            verify(agentService, never()).handleStreamChat(anyString(), anyString(), any());
            verify(agentService, never()).handleStreamChat(anyString(), anyString(),
                    anyString(), any());
        }

        @Test
        @DisplayName("限流拒绝同样落审计 —— 否则「有人在刷接口」没有任何痕迹")
        void rateLimitedIsAudited() throws Exception {
            when(rateLimiter.tryAcquire(anyString(), anyString(), anyInt(),
                    org.mockito.ArgumentMatchers.anyLong())).thenReturn(false);

            mockMvc.perform(get("/api/v1/chat/stream").param("query", "x"))
                    .andExpect(status().isOk());

            verify(agentLogService).saveLog(anyString(), anyString(), anyString(), anyString(),
                    anyBoolean(), anyInt(), anyDouble(), anyString(),
                    eq("REJECTED_RATE_LIMIT"), anyString(), anyString());
        }

        @Test
        @DisplayName("限流按 chat 业务维度隔离，不与其他端点共用计数")
        void rateLimitUsesChatBucket() throws Exception {
            when(rateLimiter.tryAcquire(anyString(), anyString(), anyInt(),
                    org.mockito.ArgumentMatchers.anyLong())).thenReturn(false);

            mockMvc.perform(get("/api/v1/chat/stream").param("query", "x"));

            // 与 webhook 等其他限流桶混用会让告警推送把对话额度吃掉
            verify(rateLimiter).tryAcquire(eq("chat"), anyString(), eq(20), eq(60000L));
        }
    }

    @Nested
    @DisplayName("防缓冲响应头")
    class Headers {

        @Test
        @DisplayName("必须带 X-Accel-Buffering:no 与 no-transform —— 否则 Nginx 会把流缓冲成一次性响应")
        void antiBufferingHeadersArePresent() throws Exception {
            MvcResult res = mockMvc.perform(get("/api/v1/chat/stream").param("query", "  "))
                    .andExpect(status().isOk())
                    .andReturn();

            // 少了这两个头，用户看到的不是逐字流式输出，
            // 而是转圈几十秒后整段文字突然出现——体验上等同于「卡住了」
            assertThat(res.getResponse().getHeader("X-Accel-Buffering")).isEqualTo("no");
            assertThat(res.getResponse().getHeader("Cache-Control")).contains("no-transform");
        }

        @Test
        @DisplayName("Content-Type 是 text/event-stream")
        void contentTypeIsEventStream() throws Exception {
            MvcResult res = mockMvc.perform(get("/api/v1/chat/stream").param("query", "  "))
                    .andReturn();

            assertThat(res.getResponse().getContentType())
                    .contains(MediaType.TEXT_EVENT_STREAM_VALUE);
        }
    }

    @Nested
    @DisplayName("POST 入参校验")
    class PostValidation {

        @Test
        @DisplayName("query 超过 1500 字 → 400（与 SecurityInputGuard 上限一致）")
        void oversizedQueryRejected() throws Exception {
            String body = objectMapper.writeValueAsString(
                    Map.of("query", "x".repeat(1501), "sessionId", "s1"));

            mockMvc.perform(post("/api/v1/chat/stream")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());

            // 两处上限不一致会让「DTO 放行但 Guard 拒绝」的请求
            // 白走完鉴权、限流、配额预检，浪费一次配额名额才报错
            verify(agentService, never()).handleStreamChat(anyString(), anyString(),
                    anyString(), any());
        }

        @Test
        @DisplayName("sessionId 超过 64 字 → 400")
        void oversizedSessionIdRejected() throws Exception {
            String body = objectMapper.writeValueAsString(
                    Map.of("query", "正常提问", "sessionId", "s".repeat(65)));

            mockMvc.perform(post("/api/v1/chat/stream")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("请求体畸形 → 400，而不是 500")
        void malformedBodyIsBadRequest() throws Exception {
            mockMvc.perform(post("/api/v1/chat/stream")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{ broken"))
                    .andExpect(status().isBadRequest());
        }
    }
}
