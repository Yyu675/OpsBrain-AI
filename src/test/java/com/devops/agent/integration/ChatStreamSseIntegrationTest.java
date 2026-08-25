package com.devops.agent.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AI 对话 SSE <b>正常路径</b>端到端测试 —— 验证事件序列本身。
 *
 * <h3>这是之前几轮明确留下的唯一缺口</h3>
 * {@code DevOpsChatControllerSseTest} 用 {@code @WebMvcTest} 覆盖了同步拒绝路径
 * （空查询、限流），但它 mock 掉了 {@code DevOpsAgentService}，
 * 因此<b>验证不了事件序列</b>——而 SSE 的价值恰恰在序列：
 * 前端按 {@code start → token* → complete} 的顺序驱动打字机效果、
 * 引用角标与成本展示，顺序或字段错了，页面就是坏的，但后端日志一切正常。
 *
 * <h3>为什么现在能测了：MOCK 模式下链路是完整且确定的</h3>
 * {@code AI_MODE=MOCK} 时 {@code MockStreamingChatModel} 逐字回调
 * {@code onPartialResponse} 再 {@code onCompleteResponse}，
 * 不调真实 API、不消耗额度、输出可预期。
 * 于是整条链路（Controller → Agent 编排 → 流式引擎 → SSE 事件）
 * 可以在 {@code @SpringBootTest} 里真实跑通。
 *
 * <p>剩下的问题只是<b>异步等待</b>：{@code handleStreamChat} 把工作交给
 * 虚拟线程执行器，请求返回时流还没跑完。等待方式见
 * {@link #awaitStreamEnd}——那里记了一个踩过的坑。</p>
 *
 * <h3>不依赖具体文案</h3>
 * 断言只针对<b>事件名、顺序、字段存在性与类型</b>，不断言 MOCK 回复的具体文字——
 * 那属于桩的实现细节，钉住它只会让改桩变成改测试。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "devops.ai.mode=MOCK",
        // 放宽限流：本类会连续发多次请求，用生产默认值会互相干扰
        "devops.ai.chat.rate-limit=1000",
        "devops.ai.chat.rate-window-ms=60000",
        // 心跳调大，避免注释帧混进事件解析
        "devops.ai.sse.heartbeat-interval-ms=600000"
})
@DisplayName("AI 对话 SSE 正常路径（事件序列）")
class ChatStreamSseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    /** 一条已解析的 SSE 事件 */
    private record SseEvent(String name, Map<String, Object> data) {
    }

    /**
     * 发起一次流式对话，等待流真正结束，返回解析后的事件序列。
     *
     * <p><b>为什么不能用 {@code getAsyncResult()}</b>：对 {@code SseEmitter}
     * 而言它返回的是 emitter 对象本身，在 emitter 被创建时就已「就绪」，
     * <b>不会等流写完</b>。第一版这么写，9 个用例全部拿到空响应体而失败——
     * 失败信息只说「事件列表为空」，很容易被误读成「产品没发事件」。</p>
     *
     * <p>SSE 的正确等待方式是看请求的异步分发是否结束
     * （{@code isAsyncStarted} 转为 false，即 emitter 已 complete），
     * 再读响应体。MOCK 模式下这通常在几十毫秒内完成。</p>
     */
    private List<SseEvent> streamAndParse(String query) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/chat/stream")
                        .param("query", query)
                        .param("sessionId", "it-" + UUID.randomUUID()))
                .andExpect(status().isOk())
                .andReturn();

        awaitStreamEnd(result);
        String body = result.getResponse().getContentAsString();
        List<SseEvent> events = parse(body);
        if (events.isEmpty()) {
            // 诊断信息直接进断言消息——本项目 CI 的原始日志与 artifact 在
            // 受限网络下都取不到，只能靠这条消息定位
            throw new AssertionError(String.format(
                    "SSE 未产生任何事件。asyncStarted=%s, status=%d, contentType=%s, bodyLen=%d, body=[%s]",
                    result.getRequest().isAsyncStarted(),
                    result.getResponse().getStatus(),
                    result.getResponse().getContentType(),
                    body.length(),
                    body.length() > 500 ? body.substring(0, 500) : body));
        }
        return events;
    }

    /**
     * 轮询等待异步分发结束。
     *
     * <p>用轮询而非固定 sleep：固定等待要么在慢机器上不够、要么白白拖慢测试，
     * 两种都会让这组用例变得不稳定。</p>
     */
    private void awaitStreamEnd(MvcResult result) throws Exception {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            if (!result.getRequest().isAsyncStarted()) return;
            Thread.sleep(20);
        }
        throw new AssertionError("SSE 流在 30 秒内未结束——MOCK 模式下不该发生");
    }

    private List<SseEvent> parse(String body) throws Exception {
        List<SseEvent> events = new ArrayList<>();
        String pendingName = null;
        for (String raw : body.split("\n")) {
            String line = raw.trim();
            if (line.startsWith("event:")) {
                pendingName = line.substring(6).trim();
            } else if (line.startsWith("data:") && pendingName != null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> data =
                        objectMapper.readValue(line.substring(5).trim(), Map.class);
                events.add(new SseEvent(pendingName, data));
                pendingName = null;
            }
        }
        return events;
    }

    private static List<String> names(List<SseEvent> events) {
        return events.stream().map(SseEvent::name).toList();
    }

    // ==================================================================

    @Test
    @DisplayName("完整序列：start 开头、complete 收尾，中间是 token 流")
    void emitsStartTokensThenComplete() throws Exception {
        List<SseEvent> events = streamAndParse("MySQL 连接池耗尽怎么排查");

        assertThat(events).as("必须真的产生事件，而不是空流").isNotEmpty();

        List<String> seq = names(events);
        // 顺序是契约：前端按它驱动打字机、引用角标与成本展示
        assertThat(seq.get(0)).as("首个事件必须是 start").isEqualTo("start");
        assertThat(seq.get(seq.size() - 1)).as("末个事件必须是 complete").isEqualTo("complete");
        assertThat(seq).contains("token");
        // 正常路径不该混入 error
        assertThat(seq).doesNotContain("error");
    }

    @Test
    @DisplayName("start 事件带 traceId 与路由模型 —— 用户报障时靠 traceId 对上后端日志")
    void startCarriesTraceIdAndRouterModel() throws Exception {
        List<SseEvent> events = streamAndParse("K8s Pod 一直 CrashLoopBackOff");

        Map<String, Object> start = events.stream()
                .filter(e -> e.name().equals("start")).findFirst().orElseThrow().data();

        assertThat((String) start.get("traceId")).isNotBlank();
        // routerModel 让用户/运维看得出这次走的是快模型还是推理模型，
        // 缺了它，同样一句话为什么这次慢十倍就无从解释
        assertThat(start.get("routerModel")).isNotNull();
    }

    @Test
    @DisplayName("token 事件逐个携带文本片段，拼起来就是完整回答")
    void tokensAssembleIntoAnswer() throws Exception {
        List<SseEvent> events = streamAndParse("如何查看容器日志");

        List<SseEvent> tokens = events.stream()
                .filter(e -> e.name().equals("token")).toList();

        assertThat(tokens).as("流式回复应产生多个 token 事件").hasSizeGreaterThan(1);

        StringBuilder sb = new StringBuilder();
        for (SseEvent t : tokens) {
            Object text = t.data().get("text");
            // 每个 token 必须有 text 字段——缺字段前端会拼出 "undefined"
            assertThat(text).as("token 事件必须带 text").isNotNull();
            sb.append(text);
        }
        // 不断言具体文案（那是 MOCK 桩的实现细节），只要求拼出非空内容
        assertThat(sb.toString()).isNotBlank();
    }

    @Test
    @DisplayName("complete 事件带齐成本与引用字段 —— 少一个前端就渲染不出结算区")
    void completeCarriesCostAndCitations() throws Exception {
        List<SseEvent> events = streamAndParse("Redis 内存告警怎么处理");

        Map<String, Object> done = events.stream()
                .filter(e -> e.name().equals("complete")).findFirst().orElseThrow().data();

        assertThat((String) done.get("traceId")).isNotBlank();
        assertThat(done).containsKeys("latencyMs", "isCached", "costRmb", "citations", "toolResults");
        // citations/toolResults 即便为空也必须是数组而非 null，
        // 否则前端每处都要判空，漏一处就是白屏
        assertThat(done.get("citations")).isInstanceOf(List.class);
        assertThat(done.get("toolResults")).isInstanceOf(List.class);
        // isCached 是布尔而非字符串——前端用它决定要不要显示「缓存命中」徽标
        assertThat(done.get("isCached")).isInstanceOf(Boolean.class);
    }

    @Test
    @DisplayName("complete 的 traceId 与 start 一致 —— 同一次对话必须能串起来")
    void traceIdIsConsistentAcrossEvents() throws Exception {
        List<SseEvent> events = streamAndParse("网络分区如何定位");

        String startTrace = (String) events.stream()
                .filter(e -> e.name().equals("start")).findFirst().orElseThrow()
                .data().get("traceId");
        String doneTrace = (String) events.stream()
                .filter(e -> e.name().equals("complete")).findFirst().orElseThrow()
                .data().get("traceId");

        // 不一致的话，用户拿着页面上的 traceId 去查日志会查到另一次请求
        assertThat(doneTrace).isEqualTo(startTrace);
    }

    @Test
    @DisplayName("两次独立请求的 traceId 不同 —— 撞车会让审计与配额记到一起")
    void eachRequestHasUniqueTraceId() throws Exception {
        String t1 = (String) streamAndParse("问题一").stream()
                .filter(e -> e.name().equals("start")).findFirst().orElseThrow()
                .data().get("traceId");
        String t2 = (String) streamAndParse("问题二").stream()
                .filter(e -> e.name().equals("start")).findFirst().orElseThrow()
                .data().get("traceId");

        assertThat(t1).isNotEqualTo(t2);
    }

    @Test
    @DisplayName("POST 与 GET 产生同样的事件序列 —— 两个入口不能有行为差异")
    void postAndGetBehaveIdentically() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("query", "磁盘打满了怎么办", "sessionId", "it-post"))))
                .andExpect(status().isOk())
                .andReturn();
        awaitStreamEnd(result);

        List<String> seq = names(parse(result.getResponse().getContentAsString()));

        // 前端历史上用 GET，新版改 POST（避免 query 出现在 URL 与日志里）。
        // 两者行为若不一致，切换时会出现「换个方法就没有引用了」这类怪事
        assertThat(seq.get(0)).isEqualTo("start");
        assertThat(seq).contains("token");
        assertThat(seq.get(seq.size() - 1)).isEqualTo("complete");
    }

    @Test
    @DisplayName("响应始终是 text/event-stream，且带防缓冲头")
    void responseIsEventStreamWithAntiBuffering() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/chat/stream")
                        .param("query", "简单问题"))
                .andExpect(status().isOk())
                .andReturn();
        awaitStreamEnd(result);

        assertThat(result.getResponse().getContentType())
                .contains(MediaType.TEXT_EVENT_STREAM_VALUE);
        // 少了它 Nginx 会把整个流缓冲成一次性响应，
        // 用户看到的不是逐字输出而是转圈几十秒后整段蹦出来
        assertThat(result.getResponse().getHeader("X-Accel-Buffering")).isEqualTo("no");
    }

    @Test
    @DisplayName("每个事件的 data 都是合法 JSON —— 解析失败会让前端整条流中断")
    void everyEventDataIsValidJson() throws Exception {
        List<SseEvent> events = streamAndParse("包含\"引号\"与\\反斜杠 的提问");

        // parse() 本身就在做 JSON 解析，能走到这里说明全部合法。
        // 这条用例的价值在于**输入带引号与反斜杠**：
        // 若某处手工拼 JSON 而非用 ObjectMapper，这里就会炸
        assertThat(events).isNotEmpty();
        assertThat(names(events)).contains("start", "token", "complete");
    }
}
