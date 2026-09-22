package com.devops.agent.infrastructure.ai;

import com.devops.agent.domain.ai.AiChannel;
import com.devops.agent.domain.ai.AiChannelCapabilityRepository;
import com.devops.agent.domain.ai.AiChannelRepository;
import com.devops.agent.infrastructure.llm.ApiKeyCrypt;
import com.devops.agent.infrastructure.llm.LlmEndpointSpec;
import com.devops.agent.infrastructure.llm.OpenAiCompatibleModelFactory;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 渠道能力探测服务（P4：连通性测试的深化——不只「能不能通」，还「会干什么」）。
 *
 * <h3>为什么连通性测试不够</h3>
 * 连通性 ping 只证明「端点+key+模型名」三元组能调通。但本平台对模型有
 * <b>能力级依赖</b>：Agent 靠 function calling 调工具、SSE 靠流式、
 * 分析卡片靠 JSON 结构化输出。一个「能 ping 通但不支持工具调用」的模型
 * 配进 chat 渠道，Agent 会静默瘫痪——ping 全绿、功能全死。
 *
 * <h3>探测项（按平台真实依赖选取，不探测用不到的能力）</h3>
 * <ul>
 *   <li>{@code chat}——基础对话（连通性本身）；</li>
 *   <li>{@code streaming}——SSE 打字机依赖；</li>
 *   <li>{@code function_calling}——Agent 工具调用命脉，
 *       用 {@code toolChoice=REQUIRED} 强制触发，比「模型自愿调」可靠；</li>
 *   <li>{@code json_mode}——结构化输出（response_format=json_object）；</li>
 *   <li>{@code embed_batch}（仅 embedding）——批量向量化（摄取链路用 embedAll）。</li>
 * </ul>
 *
 * <h3>三态语义（写进每一条结果，防误读）</h3>
 * {@code SUPPORTED} 实测支持；{@code UNSUPPORTED} 上游明确报不支持；
 * {@code UNKNOWN} 探测本身失败（超时/限流/网络）——<b>不代表不支持</b>，
 * 前端必须区分展示，不能把 UNKNOWN 画成红叉。
 *
 * <h3>成本纪律</h3>
 * 每次探测是真实 API 调用（计费 + 秒级延迟）。只由用户手动触发
 * （POST /{channelKey}/capability-probe），结果落库复用；
 * 单项探测超时 10s 兜底，整轮不超 60s。
 */
@Service
public class ChannelCapabilityProbe {

    private static final Logger log = LoggerFactory.getLogger(ChannelCapabilityProbe.class);

    /** 单项探测超时（独立于渠道配置的超时——探测要短平快，不等待慢模型）。 */
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(10);

    private final AiChannelRepository channelRepo;
    private final AiChannelCapabilityRepository capabilityRepo;
    private final String cryptSecret;

    public ChannelCapabilityProbe(AiChannelRepository channelRepo,
                                  AiChannelCapabilityRepository capabilityRepo,
                                  @Value("${MODEL_KEY_CRYPT_SECRET:}") String cryptSecret) {
        this.channelRepo = channelRepo;
        this.capabilityRepo = capabilityRepo;
        this.cryptSecret = cryptSecret;
    }

    /** 单项探测结果。 */
    public record CapabilityItem(String state, String detail) {
        public static CapabilityItem supported(String detail) { return new CapabilityItem("SUPPORTED", detail); }
        public static CapabilityItem unsupported(String detail) { return new CapabilityItem("UNSUPPORTED", detail); }
        public static CapabilityItem unknown(String detail) { return new CapabilityItem("UNKNOWN", detail); }
    }

    /** 整轮探测结果。 */
    public record ProbeResult(String channelKey, Map<String, CapabilityItem> capabilities, String probedAt) {}

    /**
     * 对指定渠道执行整轮能力探测并落库。
     * 渠道不存在抛 {@link IllegalStateException}（404）；
     * key 缺失的项直接标 UNKNOWN（无法实测，如实标注）。
     */
    public ProbeResult probe(String channelKey) {
        AiChannel ch = channelRepo.findByKey(channelKey)
                .orElseThrow(() -> new IllegalStateException("渠道不存在: " + channelKey));

        Map<String, CapabilityItem> results = new LinkedHashMap<>();
        try {
            if (AiChannel.KEY_CHAT.equals(channelKey)) {
                probeChat(ch, results);
            } else if (AiChannel.KEY_EMBEDDING.equals(channelKey)) {
                probeEmbedding(ch, results);
            } else {
                results.put("chat", CapabilityItem.unknown("reranker 渠道暂不支持能力探测"));
            }
        } catch (Exception e) {
            log.warn("[CapabilityProbe] {} 探测过程异常 | {}", channelKey, e.toString());
            results.putIfAbsent("probe_error", CapabilityItem.unknown("探测中断: " + abbreviate(e.getMessage())));
        }

        String json = toJson(results);
        capabilityRepo.save(channelKey, json);
        log.info("🔬 [CapabilityProbe] {} 探测完成 | {}", channelKey, summarize(results));
        return new ProbeResult(channelKey, results, java.time.LocalDateTime.now().toString());
    }

    /** 读已落库的最近探测结果（不重新实测）。无记录返回 null。 */
    public ProbeResult readStored(String channelKey) {
        return capabilityRepo.findByChannel(channelKey)
                .map(row -> new ProbeResult(channelKey, fromJson(row.capabilitiesJson()),
                        row.probedAt() == null ? null : row.probedAt().toString()))
                .orElse(null);
    }

    // ==================== chat 渠道探测 ====================

    private void probeChat(AiChannel ch, Map<String, CapabilityItem> results) {
        String apiKey = decrypt(ch.apiKeyEnc());
        if (apiKey == null || apiKey.isBlank()) {
            results.put("chat", CapabilityItem.unknown("未配置 API Key，无法实测"));
            return;
        }
        LlmEndpointSpec spec = LlmEndpointSpec.chat(ch.baseUrl(), apiKey, ch.turboModel(), PROBE_TIMEOUT, 0);

        // 1. 基础对话
        CapabilityItem chatResult = probeBasicChat(spec);
        results.put("chat", chatResult);
        if (!"SUPPORTED".equals(chatResult.state())) {
            // 基础对话都不通，其余项没有实测意义——如实标 UNKNOWN（不是不支持，是没测成）
            results.put("streaming", CapabilityItem.unknown("基础对话不通，未探测"));
            results.put("function_calling", CapabilityItem.unknown("基础对话不通，未探测"));
            results.put("json_mode", CapabilityItem.unknown("基础对话不通，未探测"));
            return;
        }

        // 2. 流式
        results.put("streaming", probeStreaming(spec));
        // 3. function calling（Agent 命脉）
        results.put("function_calling", probeFunctionCalling(spec));
        // 4. JSON 模式
        results.put("json_mode", probeJsonMode(spec));
    }

    private CapabilityItem probeBasicChat(LlmEndpointSpec spec) {
        try {
            ChatModel model = newChatModel(spec);
            ChatResponse resp = model.chat(ChatRequest.builder()
                    .messages(List.of(UserMessage.from("ping"))).build());
            String text = resp.aiMessage() == null ? null : resp.aiMessage().text();
            return CapabilityItem.supported("基础对话正常（响应 " + (text == null ? 0 : text.length()) + " 字符）");
        } catch (Exception e) {
            return classifyError(e, "基础对话");
        }
    }

    private CapabilityItem probeStreaming(LlmEndpointSpec spec) {
        try {
            StreamingChatModel model = newStreamingChatModel(spec.streaming());
            CountDownLatch latch = new CountDownLatch(1);
            AtomicBoolean gotToken = new AtomicBoolean(false);
            AtomicReference<Throwable> error = new AtomicReference<>();

            model.chat(ChatRequest.builder()
                            .messages(List.of(UserMessage.from("ping"))).build(),
                    new StreamingChatResponseHandler() {
                        @Override public void onPartialResponse(String partial) {
                            gotToken.set(true);
                        }
                        @Override public void onCompleteResponse(ChatResponse complete) { latch.countDown(); }
                        @Override public void onError(Throwable t) { error.set(t); latch.countDown(); }
                    });

            boolean finished = latch.await(PROBE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) return CapabilityItem.unknown("流式探测超时（" + PROBE_TIMEOUT.toSeconds() + "s 无响应）");
            if (error.get() != null) return classifyError(error.get(), "流式");
            return gotToken.get()
                    ? CapabilityItem.supported("流式输出正常（收到增量 token）")
                    : CapabilityItem.unsupported("流式调用完成但无增量 token（上游可能不支持流式）");
        } catch (Exception e) {
            return classifyError(e, "流式");
        }
    }

    private CapabilityItem probeFunctionCalling(LlmEndpointSpec spec) {
        try {
            ChatModel model = newChatModel(spec);
            ToolSpecification tool = ToolSpecification.builder()
                    .name("get_current_time")
                    .description("获取当前时间。当用户问时间相关问题时可调用。")
                    .parameters(JsonObjectSchema.builder().build())
                    .build();
            ChatRequestParameters params = ChatRequestParameters.builder()
                    .toolSpecifications(tool)
                    .toolChoice(ToolChoice.REQUIRED)  // 强制调用，比「模型自愿」可靠
                    .build();
            ChatResponse resp = model.chat(ChatRequest.builder()
                    .messages(List.of(UserMessage.from("现在几点了？")))
                    .parameters(params)
                    .build());
            AiMessage msg = resp.aiMessage();
            boolean called = msg != null && msg.toolExecutionRequests() != null
                    && !msg.toolExecutionRequests().isEmpty();
            return called
                    ? CapabilityItem.supported("function calling 正常（模型按要求发起工具调用）")
                    : CapabilityItem.unsupported("强制 toolChoice=REQUIRED 下模型未发起工具调用（不支持 function calling）");
        } catch (Exception e) {
            return classifyError(e, "function calling");
        }
    }

    private CapabilityItem probeJsonMode(LlmEndpointSpec spec) {
        try {
            ChatModel model = newChatModel(spec);
            ChatRequestParameters params = ChatRequestParameters.builder()
                    .responseFormat(ResponseFormat.JSON)
                    .build();
            ChatResponse resp = model.chat(ChatRequest.builder()
                    .messages(List.of(UserMessage.from("只返回 JSON：{\"ok\":true}")))
                    .parameters(params)
                    .build());
            String text = resp.aiMessage() == null ? "" : resp.aiMessage().text();
            return text.contains("{")
                    ? CapabilityItem.supported("JSON 模式正常（返回结构化内容）")
                    : CapabilityItem.unsupported("JSON 模式下未返回 JSON 内容");
        } catch (Exception e) {
            return classifyError(e, "JSON 模式");
        }
    }

    // ==================== embedding 渠道探测 ====================

    private void probeEmbedding(AiChannel ch, Map<String, CapabilityItem> results) {
        String apiKey = decrypt(ch.apiKeyEnc());
        if (apiKey == null || apiKey.isBlank()) {
            results.put("embed", CapabilityItem.unknown("未配置 API Key，无法实测"));
            return;
        }
        LlmEndpointSpec spec = LlmEndpointSpec.embedding(ch.baseUrl(), apiKey, ch.model(),
                PROBE_TIMEOUT, 0, ch.dimension() == null ? 1536 : ch.dimension());

        // 1. 单向量
        try {
            EmbeddingModel model = newEmbeddingModel(spec);
            var emb = model.embed("探测").content();
            int dim = emb.vector().length;
            results.put("embed", dim == (ch.dimension() == null ? 1536 : ch.dimension())
                    ? CapabilityItem.supported("向量化正常，维度 " + dim)
                    : CapabilityItem.unsupported("维度不符：实测 " + dim + "≠配置 " + ch.dimension()));
        } catch (Exception e) {
            results.put("embed", classifyError(e, "向量化"));
            results.put("embed_batch", CapabilityItem.unknown("单向量化不通，未探测"));
            return;
        }

        // 2. 批量（摄取链路用 embedAll）
        try {
            EmbeddingModel model = newEmbeddingModel(spec);
            var embs = model.embedAll(List.of(
                    dev.langchain4j.data.segment.TextSegment.from("a"),
                    dev.langchain4j.data.segment.TextSegment.from("b"))).content();
            results.put("embed_batch", embs.size() == 2
                    ? CapabilityItem.supported("批量向量化正常（" + embs.size() + " 条一次成功）")
                    : CapabilityItem.unsupported("批量返回条数不符：" + embs.size()));
        } catch (Exception e) {
            results.put("embed_batch", classifyError(e, "批量向量化"));
        }
    }

    // ==================== 错误分类（三态判定核心） ====================

    /**
     * 把异常归为 UNSUPPORTED 或 UNKNOWN。判据是错误消息特征——
     * 「不支持」类错误（4xx + not support/unsupported/invalid 关键词）标 UNSUPPORTED；
     * 超时/连接/5xx/其他一律 UNKNOWN（探测本身失败，不下「不支持」的结论）。
     */
    private CapabilityItem classifyError(Throwable e, String what) {
        String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        String lower = msg.toLowerCase();
        boolean explicitlyUnsupported = lower.contains("not support") || lower.contains("unsupported")
                || lower.contains("does not support") || lower.contains("not implemented")
                || lower.contains("invalid parameter") || lower.contains("unrecognized")
                || (lower.contains("400") && (lower.contains("tool") || lower.contains("function") || lower.contains("response_format")));
        if (explicitlyUnsupported) {
            return CapabilityItem.unsupported(what + "：上游明确不支持（" + abbreviate(msg) + "）");
        }
        return CapabilityItem.unknown(what + "探测失败：" + abbreviate(msg));
    }

    private String decrypt(String enc) {
        try {
            return ApiKeyCrypt.decrypt(enc, cryptSecret);
        } catch (Exception e) {
            log.warn("[CapabilityProbe] key 解密失败 | {}", e.toString());
            return null;
        }
    }

    private static String abbreviate(String msg) {
        if (msg == null) return "unknown";
        return msg.length() > 120 ? msg.substring(0, 120) + "..." : msg;
    }

    private static String summarize(Map<String, CapabilityItem> results) {
        long ok = results.values().stream().filter(i -> "SUPPORTED".equals(i.state())).count();
        long no = results.values().stream().filter(i -> "UNSUPPORTED".equals(i.state())).count();
        long unknown = results.values().stream().filter(i -> "UNKNOWN".equals(i.state())).count();
        return "支持 " + ok + " / 不支持 " + no + " / 未测成 " + unknown;
    }

    // ==================== 可覆写接缝（测试注入桩模型，不走真实 HTTP） ====================

    ChatModel newChatModel(LlmEndpointSpec spec) {
        return OpenAiCompatibleModelFactory.chat(spec, false);
    }

    StreamingChatModel newStreamingChatModel(LlmEndpointSpec spec) {
        return OpenAiCompatibleModelFactory.streamingChat(spec, false);
    }

    EmbeddingModel newEmbeddingModel(LlmEndpointSpec spec) {
        return OpenAiCompatibleModelFactory.embedding(spec);
    }

    // ==================== JSON 序列化（Jackson，项目已有依赖） ====================

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    private static String toJson(Map<String, CapabilityItem> map) {
        try {
            return JSON.writeValueAsString(map);
        } catch (Exception e) {
            throw new IllegalStateException("能力探测结果序列化失败", e);
        }
    }

    private static Map<String, CapabilityItem> fromJson(String json) {
        if (json == null || json.isBlank()) return new LinkedHashMap<>();
        try {
            return JSON.readValue(json,
                    new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, CapabilityItem>>() {});
        } catch (Exception e) {
            // 读不出就当作没有记录——探测结果可重新实测，不该因脏数据让功能挂掉
            return new LinkedHashMap<>();
        }
    }
}
