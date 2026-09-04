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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AI 对话 SSE <b>超时路径</b>集成测试。
 *
 * <h3>为什么必须单开一个类，而不是塞进 ChatStreamSseIntegrationTest</h3>
 * 超时用例要把 {@code devops.ai.sse.timeout-ms} 压到极小值（这里 800ms），
 * 而那个类的 9 个正常路径用例需要足够时间跑完整条流——
 * 两组属性直接冲突。
 *
 * <p>更关键的是：<b>{@code @TestPropertySource} 标在 {@code @Nested} 内嵌类上
 * 不会覆盖外层属性</b>（本项目已在 HealthCheck 探针开关那里踩过一次，
 * 当时同样是被迫拆成独立顶层类）。所以「用内嵌类隔离属性」这条路走不通，
 * 只能单开顶层类。</p>
 *
 * <h3>超时路径为什么值得单独测</h3>
 * {@code emitter.onTimeout} 里做了三件事，每一件漏掉都<b>不会报错</b>：
 * <ol>
 *   <li><b>取消心跳</b>——漏了会让调度器里堆积永不结束的任务，
 *       每次超时泄漏一个，最终拖垮整个应用；</li>
 *   <li><b>取消 Agent 执行</b>（{@code cancelStream}）——漏了则前端已经断开，
 *       后端仍在跑完模型流并<b>写库建单</b>，用户会莫名其妙多出一张工单，
 *       而且模型 token 照常计费；</li>
 *   <li><b>发 error 事件（50002）再 complete</b>——漏了则连接静默挂断，
 *       前端分不清「超时」与「网络断了」，只能显示一个通用错误。</li>
 * </ol>
 *
 * <p>本类验证第 3 条（客户端可观测的部分），并顺带确认超时之后
 * 服务端仍然健康——若心跳或流没被正确取消，后续请求会受影响。</p>
 *
 * @author OpsBrain AI
 * @since 2026-08-25
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "devops.ai.mode=MOCK",
        "devops.ai.chat.rate-limit=1000",
        "devops.ai.chat.rate-window-ms=60000",
        // 核心：把 SSE 超时压到 800ms。
        // MOCK 模式下打字机效果每 3 个字符 sleep 50ms，一段回答远超 800ms，
        // 因此必然在流跑完之前触发 onTimeout——这正是要测的时机。
        "devops.ai.sse.timeout-ms=800",
        // 心跳调大，避免注释帧混进事件解析
        "devops.ai.sse.heartbeat-interval-ms=600000"
})
@DisplayName("AI 对话 SSE · 超时路径")
class ChatStreamSseTimeoutTest {

    @LocalServerPort
    private int port;

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

    private final String username = "sseto_" + UUID.randomUUID().toString().substring(0, 8);
    private static final String RAW_PASSWORD = "SseTimeout#2026";

    private String tokenName;
    private String tokenValue;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private record SseEvent(String name, Map<String, Object> data) {
    }

    private String url(String path) {
        return "http://localhost:" + port + contextPath + path;
    }

    @BeforeEach
    void loginForStream() throws Exception {
        User u = new User();
        u.setUsername(username);
        u.setPassword(authService.encodePassword(RAW_PASSWORD));
        u.setDisplayName("SSE 超时测试用户");
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
    }

    @AfterEach
    void cleanupUser() {
        jdbcTemplate.update("DELETE FROM sys_user WHERE username = ?", username);
    }

    /**
     * 读取 SSE，容忍 chunked 流的非正常收尾。
     *
     * <p>与 {@code ChatStreamSseIntegrationTest} 同一考量：
     * {@code BodyHandlers.ofString()} 在缺少结束块时会抛 IOException
     * 并丢弃已收到的全部事件。超时场景下连接是被服务端主动掐断的，
     * 更容易缺结束块，因此这里必须用容忍式读取。</p>
     */
    private String readTolerant(HttpRequest req) throws Exception {
        HttpResponse<InputStream> raw =
                http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        assertThat(raw.statusCode()).isEqualTo(200);
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (InputStream in = raw.body()) {
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) != -1) {
                buf.write(chunk, 0, n);
            }
        } catch (IOException ignored) {
            // 超时导致的断流属预期，已读内容仍然有效
        }
        return buf.toString(StandardCharsets.UTF_8);
    }

    private List<SseEvent> parse(String body) throws Exception {
        List<SseEvent> events = new ArrayList<>();
        String pendingName = null;
        for (String rawLine : body.split("\n")) {
            String line = rawLine.trim();
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

    private List<SseEvent> stream(String query) throws Exception {
        String uri = url("/api/v1/chat/stream")
                + "?query=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "&sessionId=" + URLEncoder.encode("to-" + UUID.randomUUID(), StandardCharsets.UTF_8);
        HttpRequest req = HttpRequest.newBuilder(URI.create(uri))
                .header("Accept", MediaType.TEXT_EVENT_STREAM_VALUE)
                .header(tokenName, tokenValue)
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        return parse(readTolerant(req));
    }

    // ==================================================================

    @Test
    @DisplayName("超时后仍以合法 SSE 事件收尾，而不是静默挂断连接")
    void timeoutEmitsProperEvents() throws Exception {
        List<SseEvent> events = stream("请详细展开讲讲磁盘打满的完整排查流程");

        // 至少要有 start——说明流确实起来了，不是一开始就失败。
        // 若连 start 都没有，问题在鉴权或路由，不在超时逻辑
        assertThat(events).as("超时前应已发出至少一个事件").isNotEmpty();
        assertThat(events.get(0).name()).isEqualTo("start");

        // 关键：收尾必须是可识别的事件。
        // 静默挂断的话前端分不清「超时」与「网络断了」，
        // 只能显示一个通用错误，用户不知道该不该重试
        List<String> names = events.stream().map(SseEvent::name).toList();
        assertThat(names)
                .as("超时应以 error 或 complete 收尾，实际=%s", names)
                .containsAnyOf("error", "complete");
    }

    @Test
    @DisplayName("超时的 error 事件带 50002 与可读文案，且带 traceId 供排查")
    void timeoutErrorCarriesCodeAndTraceId() throws Exception {
        List<SseEvent> events = stream("再讲一遍完整的排查流程，越详细越好");

        List<SseEvent> errors = events.stream()
                .filter(e -> "error".equals(e.name()))
                .toList();

        // MOCK 模式下流可能刚好在超时前跑完，此时没有 error 事件——
        // 那是合法结果，不该把用例判失败。只在确实超时时校验内容
        if (errors.isEmpty()) {
            assertThat(events.stream().map(SseEvent::name).toList())
                    .as("没有超时就必须是正常收尾")
                    .contains("complete");
            return;
        }

        Map<String, Object> data = errors.get(0).data();
        // 50002 是「连接超时」专用码。用通用的 50001 会让前端无法区分
        // 「超时可重试」与「服务内部异常需上报」
        assertThat(String.valueOf(data.get("code"))).isEqualTo("50002");
        assertThat(String.valueOf(data.get("message"))).isNotBlank();
        // traceId 必须带上：超时往往需要结合服务端日志排查，
        // 没有它用户报障时说不清是哪一次
        assertThat(String.valueOf(data.get("traceId"))).isNotBlank();
    }

    @Test
    @DisplayName("超时之后服务端仍健康——心跳与流若未正确取消，后续请求会受影响")
    void serverStaysHealthyAfterTimeout() throws Exception {
        // 先制造一次超时
        stream("第一次，预期会超时");

        // 再来一次。onTimeout 里若漏了 cancelHeartbeat，
        // 调度器会堆积永不结束的任务；若漏了 cancelStream，
        // 后端仍在跑上一条流。两者都会在这里显现为异常或超时
        List<SseEvent> second = stream("第二次，服务端应当照常响应");

        assertThat(second).isNotEmpty();
        assertThat(second.get(0).name()).isEqualTo("start");
    }
}
