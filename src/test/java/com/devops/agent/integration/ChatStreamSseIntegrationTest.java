package com.devops.agent.integration;

import com.devops.agent.domain.auth.User;
import com.devops.agent.domain.auth.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import org.slf4j.LoggerFactory;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

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
 * 虚拟线程执行器，请求返回时流还没跑完。本类改用真实 HTTP 后，
 * 读到 EOF 即等于流结束，不再需要手写等待——见下一节。</p>
 *
 * <h3>不依赖具体文案</h3>
 * 断言只针对<b>事件名、顺序、字段存在性与类型</b>，不断言 MOCK 回复的具体文字——
 * 那属于桩的实现细节，钉住它只会让改桩变成改测试。
 *
 * <h3>本轮改用真实 HTTP 消费流（此前 @Disabled 的根因）</h3>
 * 之前用 {@code @SpringBootTest + @AutoConfigureMockMvc}，9 个用例全部拿到
 * <b>空响应体</b>，而受限网络下取不到 surefire 详情，一度只能挂起。
 *
 * <p>本轮改成 <b>{@code RANDOM_PORT} + JDK 21 内置 {@code HttpClient}</b>，
 * 真正起一个 Servlet 容器、真正走一次网络。这样做同时消除了两类问题：</p>
 * <ol>
 *   <li><b>{@code MockMvc} 不真正执行异步分发。</b>{@code SseEmitter} 的写入
 *       发生在容器的异步线程里，{@code MockMvc} 只是模拟 Servlet 环境，
 *       {@code getAsyncResult()} 拿到的是 emitter 对象本身、
 *       {@code isAsyncStarted()} 也随即为 false——两种等待方式都等不到流写完，
 *       响应体自然是空的。真实容器不存在这个模拟差异；</li>
 *   <li><b>{@code context-path=/ai} 被 MockMvc 忽略。</b>真实请求必须带上它，
 *       否则 404。这一条在 MockMvc 下根本不会暴露，
 *       却会在部署后变成「本地测试全绿、线上接口 404」。</li>
 * </ol>
 *
 * <p>不用 {@code WebTestClient} 是因为它来自 {@code spring-webflux}，
 * 而本项目只依赖 {@code spring-boot-starter-web}。为一个测试引入整个响应式栈
 * 不划算，且当前沙箱 Maven 镜像不可达、无法验证新依赖能否解析。
 * JDK 内置 {@code HttpClient} 零新增依赖，读到服务端关闭连接（即 emitter
 * complete）为止，天然就是我们要的等待语义。</p>
 *
 * <p><b>但不能用 {@code BodyHandlers.ofString()}</b>：它对 chunked 编码的收尾
 * 要求严格，而 SSE 常常写完即断、不发那个长度为 0 的结束块，
 * 于是抛 {@code IOException: chunked transfer encoding, state: READING_LENGTH}
 * 并把已收到的事件全部丢弃。改用 {@link #sendTolerantOfAbruptClose} 读，
 * 详见该方法注释。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
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

    @LocalServerPort
    private int port;

    /** 与生产一致的 context-path。MockMvc 会忽略它，真实请求必须带上，否则 404 */
    @Value("${server.servlet.context-path:}")
    private String contextPath;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private com.devops.agent.domain.auth.AuthService authService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final String username = "sse_" + UUID.randomUUID().toString().substring(0, 8);
    private static final String RAW_PASSWORD = "SseStream#2026";

    /** 登录后拿到的鉴权头（tokenName -> tokenValue） */
    private String tokenName;
    private String tokenValue;

    /**
     * 读取整条流的超时。
     *
     * MOCK 模式下通常几十毫秒完成；给到 30 秒是为了容忍 CI 冷启动，
     * 同时保证真出问题时不会把构建挂死。
     */
    private static final Duration STREAM_TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** 一条已解析的 SSE 事件 */
    private record SseEvent(String name, Map<String, Object> data) {
    }

    private String url(String path) {
        return "http://localhost:" + port + contextPath + path;
    }

    /**
     * SSE 端点<b>需要登录</b>（WebConfig 的白名单只放行 auth/health/webhook）。
     *
     * <p>此前的切片测试用 {@code excludeFilters} 把 {@code WebConfig} 整个排掉，
     * 于是无需鉴权。改走真实 HTTP 后拦截器是真的会执行的——
     * 不带 token 的话每个用例都会拿到 401，
     * 而症状（响应体里没有 SSE 事件）与「流没跑起来」一模一样，极易误判。
     * 所以这里先建号、真登录、把 token 带在后续每个请求上。</p>
     */
    @BeforeEach
    void loginForStream() throws Exception {
        User u = new User();
        u.setUsername(username);
        u.setPassword(authService.encodePassword(RAW_PASSWORD));
        u.setDisplayName("SSE 集成测试用户");
        u.setRole("ADMIN");
        u.setStatus("ACTIVE");
        userRepository.insert(u);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", username);
        body.put("password", RAW_PASSWORD);

        HttpRequest req = HttpRequest.newBuilder(URI.create(url("/api/v1/auth/login")))
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .timeout(Duration.ofSeconds(20))
                .POST(HttpRequest.BodyPublishers.ofString(
                        objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> res =
                http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(res.statusCode()).as("登录应成功，否则后续 SSE 全部 401").isEqualTo(200);

        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = objectMapper.readValue(res.body(), Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) parsed.get("data");
        tokenName = String.valueOf(data.get("tokenName"));
        tokenValue = String.valueOf(data.get("token"));
        assertThat(tokenValue).as("必须签发真实 token").isNotBlank();
    }

    /**
     * 清理账号。
     *
     * 不用 {@code @Transactional} 回滚：Sa-Token 的会话写在 Redis 里，
     * 事务管不到它，回滚反而会造成「库里没这个用户但 Redis 还有他的会话」的错位。
     * （与 AuthLoginChainIntegrationTest 保持同一做法。）
     */
    @AfterEach
    void cleanupUser() {
        jdbcTemplate.update("DELETE FROM sys_user WHERE username = ?", username);
    }

    /** 给请求带上鉴权头 */
    private HttpRequest.Builder authed(HttpRequest.Builder b) {
        return b.header(tokenName, tokenValue);
    }

    /**
     * 发起一次流式对话，读完整条流，返回解析后的事件序列。
     *
     * <p><b>为什么不需要手写等待</b>：SSE 是「服务端写完就关连接」的模型，
     * {@link #sendTolerantOfAbruptClose} 会一直读到 EOF，
     * 即 emitter {@code complete()} 之后才返回。这正是我们要的语义，
     * 比此前在 MockMvc 下轮询 {@code isAsyncStarted} 可靠得多——
     * 那个标志在模拟环境里根本不反映真实的异步分发状态。</p>
     */
    private List<SseEvent> streamAndParse(String query) throws Exception {
        HttpResponse<String> res = getStream(query);
        assertThat(res.statusCode()).as("SSE 请求应返回 200").isEqualTo(200);
        return parseOrExplain(res);
    }

    private HttpResponse<String> getStream(String query) throws Exception {
        String uri = url("/api/v1/chat/stream")
                + "?query=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "&sessionId=" + URLEncoder.encode("it-" + UUID.randomUUID(), StandardCharsets.UTF_8);
        HttpRequest req = authed(HttpRequest.newBuilder(URI.create(uri))
                .header("Accept", MediaType.TEXT_EVENT_STREAM_VALUE)
                .timeout(STREAM_TIMEOUT)
                .GET())
                .build();
        return sendTolerantOfAbruptClose(req);
    }

    /**
     * 读取 SSE 响应，容忍服务端「写完即断」造成的分块结尾缺失。
     *
     * <h3>为什么必须这样读（本类此前 9 例全 ERROR 的真正原因）</h3>
     * CI 拿到的真实异常是：
     * <pre>java.io.IOException: chunked transfer encoding, state: READING_LENGTH</pre>
     *
     * <p>SSE 走 {@code Transfer-Encoding: chunked}。规范要求以一个长度为 0 的
     * 结束块收尾，但 {@code SseEmitter.complete()} 之后容器直接关闭连接，
     * 现实中经常<b>收不到那个结束块</b>。
     * {@code BodyHandlers.ofString()} 是<b>严格</b>实现——它在解析分块长度的
     * 状态下遇到 EOF 会直接抛 IOException，<b>连同已经收到的全部事件一起丢弃</b>。
     * 于是表现为「9 例全部 ERROR、且都在几百毫秒内」，
     * 看起来像初始化失败，实际流已经正常跑完了。</p>
     *
     * <p>改用 {@code BodyHandlers.ofInputStream()} 手工读：
     * 逐块读入直到 EOF 或异常，<b>把异常之前已读到的字节当作有效内容返回</b>。
     * 对 SSE 而言这是正确取舍——事件是以 {@code \n\n} 分隔的自描述记录，
     * 少一个协议层的结束块不影响任何一条已完整到达的事件；
     * 而为了一个形式上的收尾块丢掉整条流，才是真正的信息损失。</p>
     *
     * <p>仍然保留超时与状态码校验，异常不会被无声吞掉：
     * 若连一个字节都没读到，返回空串，由 {@code parseOrExplain} 给出带上下文的断言失败。</p>
     */
    private HttpResponse<String> sendTolerantOfAbruptClose(HttpRequest req) throws Exception {
        HttpResponse<InputStream> raw =
                http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (InputStream in = raw.body()) {
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) != -1) {
                buf.write(chunk, 0, n);
            }
        } catch (IOException e) {
            // 分块结尾缺失属预期；已读到的内容仍然有效，继续用它做断言。
            // 真正的问题（如一个字节都没有）会在 parseOrExplain 里暴露。
            LoggerFactory.getLogger(ChatStreamSseIntegrationTest.class)
                    .debug("SSE 流以非正常分块结尾结束（已读 {} 字节）：{}",
                            buf.size(), e.getMessage());
        }
        String body = buf.toString(StandardCharsets.UTF_8);
        return new SimpleStringResponse(raw, body);
    }

    /**
     * 把 {@code HttpResponse<InputStream>} 包装成 {@code HttpResponse<String>}。
     *
     * <p>只为让下游断言代码保持不变——它们只用到 statusCode / headers / body 三项。</p>
     */
    private record SimpleStringResponse(HttpResponse<InputStream> delegate, String bodyText)
            implements HttpResponse<String> {
        @Override public int statusCode() { return delegate.statusCode(); }
        @Override public HttpRequest request() { return delegate.request(); }
        @Override public java.util.Optional<HttpResponse<String>> previousResponse() {
            return java.util.Optional.empty();
        }
        @Override public java.net.http.HttpHeaders headers() { return delegate.headers(); }
        @Override public String body() { return bodyText; }
        @Override public java.util.Optional<javax.net.ssl.SSLSession> sslSession() {
            return delegate.sslSession();
        }
        @Override public URI uri() { return delegate.uri(); }
        @Override public java.net.http.HttpClient.Version version() { return delegate.version(); }
    }

    /**
     * 解析事件；为空时把诊断信息塞进断言消息。
     *
     * <p>本仓库 CI 的原始日志走 Azure blob、artifact 在受限网络下返回 0 字节，
     * annotations API 又只回摘要行——<b>断言消息是唯一能带出上下文的通道</b>。
     * 上一轮就是因为只有一句「事件列表为空」而无法定位，才不得不挂起整个类。</p>
     */
    private List<SseEvent> parseOrExplain(HttpResponse<String> res) throws Exception {
        String body = res.body();
        List<SseEvent> events = parse(body);
        if (events.isEmpty()) {
            throw new AssertionError(String.format(
                    "SSE 未产生任何事件。status=%d, contentType=%s, bodyLen=%d, body=[%s]",
                    res.statusCode(),
                    res.headers().firstValue("Content-Type").orElse("(none)"),
                    body.length(),
                    body.length() > 500 ? body.substring(0, 500) : body));
        }
        return events;
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
        HttpRequest req = authed(HttpRequest.newBuilder(URI.create(url("/api/v1/chat/stream")))
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .header("Accept", MediaType.TEXT_EVENT_STREAM_VALUE)
                .timeout(STREAM_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(
                        Map.of("query", "磁盘打满了怎么办", "sessionId", "it-post")),
                        StandardCharsets.UTF_8)))
                .build();
        // 与 GET 走同一个容忍式读取：POST 的响应同样是 chunked SSE，
        // 用严格的 ofString() 会在缺少结束块时抛 IOException 并丢掉整条流
        HttpResponse<String> res = sendTolerantOfAbruptClose(req);
        assertThat(res.statusCode()).isEqualTo(200);

        List<String> seq = names(parseOrExplain(res));

        // 前端历史上用 GET，新版改 POST（避免 query 出现在 URL 与日志里）。
        // 两者行为若不一致，切换时会出现「换个方法就没有引用了」这类怪事
        assertThat(seq.get(0)).isEqualTo("start");
        assertThat(seq).contains("token");
        assertThat(seq.get(seq.size() - 1)).isEqualTo("complete");
    }

    @Test
    @DisplayName("响应始终是 text/event-stream，且带防缓冲头")
    void responseIsEventStreamWithAntiBuffering() throws Exception {
        HttpResponse<String> res = getStream("简单问题");

        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.headers().firstValue("Content-Type").orElse(""))
                .contains(MediaType.TEXT_EVENT_STREAM_VALUE);
        // 少了它 Nginx 会把整个流缓冲成一次性响应，
        // 用户看到的不是逐字输出而是转圈几十秒后整段蹦出来
        assertThat(res.headers().firstValue("X-Accel-Buffering").orElse(""))
                .isEqualTo("no");
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

    // ==================== 并发与异常路径 ====================
    //
    // 以下几例只有在**真实 HTTP** 下才有意义：MockMvc 既不真跑异步分发、
    // 也没有真实连接可断，切片测试里它们全是假的。

    @Test
    @DisplayName("并发多路流互不串号 —— 串号会让 A 用户看到 B 用户的回答")
    void concurrentStreamsDoNotInterleave() throws Exception {
        int n = 4;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<List<SseEvent>>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                final int idx = i;
                futures.add(pool.submit(() -> streamAndParse("并发提问 " + idx)));
            }

            List<String> traceIds = new ArrayList<>();
            for (Future<List<SseEvent>> f : futures) {
                List<SseEvent> events = f.get(60, TimeUnit.SECONDS);
                // 每一路都必须自成完整序列，不能被别人的事件截断。
                // 断言消息带上实际事件序列——CI 下这是唯一能看到上下文的通道，
                // 只说「少了 complete」定位不到是哪一步断的。
                assertThat(names(events))
                        .as("每一路都应有完整的 start/complete，实际=%s，error事件=%s",
                                names(events),
                                events.stream()
                                        .filter(e -> "error".equals(e.name()))
                                        .map(e -> String.valueOf(e.data()))
                                        .toList())
                        .contains("start", "complete");

                // 同一路内 traceId 必须自洽——串号最典型的形态就是
                // 一条流里混进了另一条流的 traceId
                List<String> idsInThisStream = events.stream()
                        .map(e -> String.valueOf(e.data().get("traceId")))
                        .filter(v -> !"null".equals(v))
                        .distinct()
                        .toList();
                assertThat(idsInThisStream).as("单条流内 traceId 必须唯一").hasSize(1);
                traceIds.add(idsInThisStream.get(0));
            }

            // 4 路之间两两不同。相同意味着 traceId 生成或传递依赖了共享可变状态
            // （典型是 ThreadLocal 未清理或字段被复用），
            // 那样审计日志会把几个用户的操作记到同一条链路上
            assertThat(traceIds).doesNotHaveDuplicates().hasSize(n);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("客户端中途断开：服务端不应因此崩溃，后续请求照常")
    void clientAbortDoesNotBreakServer() throws Exception {
        // 只读响应头就立刻关闭连接，模拟用户刷新页面 / 关标签页。
        // 服务端此时仍在往一个已死的连接里写，onError 回调应当接住它并取消流。
        String uri = url("/api/v1/chat/stream")
                + "?query=" + URLEncoder.encode("这次我会中途断开", StandardCharsets.UTF_8)
                + "&sessionId=" + URLEncoder.encode("it-abort-" + UUID.randomUUID(), StandardCharsets.UTF_8);
        HttpRequest req = authed(HttpRequest.newBuilder(URI.create(uri))
                .header("Accept", MediaType.TEXT_EVENT_STREAM_VALUE)
                .timeout(STREAM_TIMEOUT)
                .GET())
                .build();

        HttpResponse<InputStream> raw =
                http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        assertThat(raw.statusCode()).isEqualTo(200);
        // 读一点点就撒手，不读完
        try (InputStream in = raw.body()) {
            in.read(new byte[64]);
        } catch (IOException ignored) {
            // 断开过程中的异常不是本例关心的对象
        }

        // 关键断言：服务端还活着。若断连处理有缺陷（未取消流、
        // 心跳任务泄漏、异常冒泡到容器线程），下一次请求会失败或超时
        List<SseEvent> events = streamAndParse("断开之后的正常提问");
        assertThat(names(events)).contains("start", "complete");
    }

    @Test
    @DisplayName("同一 sessionId 连续两轮：各自独立成流，不串事件")
    void sameSessionSequentialStreamsAreIndependent() throws Exception {
        String sessionId = "it-seq-" + UUID.randomUUID();

        List<SseEvent> first = streamAndParseWithSession("第一轮提问", sessionId);
        List<SseEvent> second = streamAndParseWithSession("第二轮提问", sessionId);

        // 多轮对话共享 sessionId（记忆需要），但每轮必须是独立完整的一条流。
        // 若第二轮沿用了上一轮的 emitter 或 traceId，
        // 前端会把两轮回答拼在一起显示
        assertThat(names(first)).contains("start", "complete");
        assertThat(names(second)).contains("start", "complete");
        assertThat(traceIdOf(first)).isNotEqualTo(traceIdOf(second));
    }

    /** 取一条流的 traceId（前面已断言过流内唯一） */
    private String traceIdOf(List<SseEvent> events) {
        return events.stream()
                .map(e -> String.valueOf(e.data().get("traceId")))
                .filter(v -> !"null".equals(v))
                .findFirst()
                .orElseThrow(() -> new AssertionError("流中没有任何带 traceId 的事件"));
    }

    /** 指定 sessionId 发起一次流式对话 */
    private List<SseEvent> streamAndParseWithSession(String query, String sessionId) throws Exception {
        String uri = url("/api/v1/chat/stream")
                + "?query=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "&sessionId=" + URLEncoder.encode(sessionId, StandardCharsets.UTF_8);
        HttpRequest req = authed(HttpRequest.newBuilder(URI.create(uri))
                .header("Accept", MediaType.TEXT_EVENT_STREAM_VALUE)
                .timeout(STREAM_TIMEOUT)
                .GET())
                .build();
        HttpResponse<String> res = sendTolerantOfAbruptClose(req);
        assertThat(res.statusCode()).isEqualTo(200);
        return parseOrExplain(res);
    }
}
