package com.devops.agent.infrastructure.llm;

import com.devops.agent.domain.ai.AiChannel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 协议感知的模型工厂（方案 A 多协议支持，2026-09-29）。
 *
 * <h3>为什么需要这一层</h3>
 * 此前 {@link OpenAiCompatibleModelFactory} 是唯一的模型构建入口——无论渠道配的
 * protocol 是什么，实际调用都走 OpenAI 兼容协议。这让 protocol 成了「假配置」：
 * 用户配了 Anthropic/Azure，调用却按 OpenAI 兼容发出而失败。
 * 本工厂按 {@link AiChannel#protocol()} 分发到对应协议的模型构建逻辑，
 * 让 protocol 配置真正生效。
 *
 * <h3>各协议的构建差异</h3>
 * <ul>
 *   <li><b>OPENAI_COMPATIBLE</b>：阿里云/DeepSeek/智谱/assistant/本地 vLLM 等
 *       绝大多数厂商，走 {@link OpenAiCompatibleModelFactory}；</li>
 *   <li><b>AZURE_OPENAI</b>：AzureOpenAiChatModel，端点是 deployment 维度
 *       （baseUrl 填 endpoint，modelName 填 deploymentName）；</li>
 *   <li><b>ANTHROPIC</b>：AnthropicChatModel，原生 /v1/messages 协议；</li>
 *   <li><b>CUSTOM</b>：回落 OpenAI 兼容 + WARN（自定义协议多数仍兼容 OpenAI 格式）。</li>
 * </ul>
 *
 * <h3>Embedding 的边界</h3>
 * Anthropic 没有 embedding 模型，Azure 的 embedding 走 deployment 维度——
 * 当前 embedding 仅支持 OpenAI 兼容协议，非 OpenAI 协议回落 OpenAI 兼容 + WARN。
 */
public final class ProtocolAwareModelFactory {

    private static final Logger log = LoggerFactory.getLogger(ProtocolAwareModelFactory.class);

    private ProtocolAwareModelFactory() {}

    // ==================== ChatModel ====================

    /** 按 protocol 分发构建同步对话模型。 */
    public static ChatModel chat(LlmEndpointSpec spec, String protocol, boolean logRequests) {
        String p = normalize(protocol);
        ChatModel model = switch (p) {
            case "ANTHROPIC" -> buildAnthropicChat(spec, logRequests);
            case "AZURE_OPENAI" -> buildAzureChat(spec, logRequests);
            case "CUSTOM" -> {
                log.warn("⚠️ [ProtocolAware] CUSTOM 协议按 OpenAI 兼容处理（自定义协议多数仍兼容 OpenAI 格式）");
                yield OpenAiCompatibleModelFactory.chat(spec, logRequests);
            }
            default -> OpenAiCompatibleModelFactory.chat(spec, logRequests);
        };
        // 方案 A 实证：INFO 记录非默认协议下真实构建的模型类——Anthropic 必须是
        // AnthropicChatModel，不能静默落成 OpenAiChatModel（否则 protocol 又成了假配置）。
        // 仅非默认协议打 INFO（默认 OpenAI 兼容不打，避免每次构建刷屏）。
        if (!AiChannel.PROTOCOL_OPENAI_COMPATIBLE.equals(p)) {
            log.info("🔀 [ProtocolAware] chat 模型按协议构建 | protocol={} | modelClass={}", p, model.getClass().getSimpleName());
        }
        return model;
    }

    /** 按 protocol 分发构建流式对话模型。 */
    public static StreamingChatModel streamingChat(LlmEndpointSpec spec, String protocol, boolean logRequests) {
        String p = normalize(protocol);
        switch (p) {
            case "ANTHROPIC":
                return buildAnthropicStreaming(spec, logRequests);
            case "AZURE_OPENAI":
                return buildAzureStreaming(spec, logRequests);
            case "CUSTOM":
                log.warn("⚠️ [ProtocolAware] CUSTOM 协议按 OpenAI 兼容处理（流式）");
                return OpenAiCompatibleModelFactory.streamingChat(spec, logRequests);
            default:
                return OpenAiCompatibleModelFactory.streamingChat(spec, logRequests);
        }
    }

    /** Embedding：当前仅 OpenAI 兼容协议真实支持，其余回落 + WARN。 */
    public static EmbeddingModel embedding(LlmEndpointSpec spec, String protocol) {
        String p = normalize(protocol);
        if (!"OPENAI_COMPATIBLE".equals(p)) {
            log.warn("⚠️ [ProtocolAware] Embedding 暂仅支持 OpenAI 兼容协议，当前协议 {} 按 OpenAI 兼容处理", p);
        }
        return OpenAiCompatibleModelFactory.embedding(spec);
    }

    // ==================== Anthropic ====================

    private static ChatModel buildAnthropicChat(LlmEndpointSpec spec, boolean logRequests) {
        return dev.langchain4j.model.anthropic.AnthropicChatModel.builder()
                .baseUrl(spec.baseUrl())
                .apiKey(spec.apiKey())
                .modelName(spec.modelName())
                .timeout(spec.timeout())
                .maxRetries(spec.maxRetries())
                .logRequests(logRequests)
                .build();
    }

    private static StreamingChatModel buildAnthropicStreaming(LlmEndpointSpec spec, boolean logRequests) {
        return dev.langchain4j.model.anthropic.AnthropicStreamingChatModel.builder()
                .baseUrl(spec.baseUrl())
                .apiKey(spec.apiKey())
                .modelName(spec.modelName())
                .timeout(spec.timeout())
                .logRequests(logRequests)
                .build();
    }

    // ==================== Azure OpenAI ====================

    private static ChatModel buildAzureChat(LlmEndpointSpec spec, boolean logRequests) {
        // Azure OpenAI：baseUrl 填 endpoint，modelName 填 deploymentName
        return dev.langchain4j.model.azure.AzureOpenAiChatModel.builder()
                .endpoint(spec.baseUrl())
                .apiKey(spec.apiKey())
                .deploymentName(spec.modelName())
                .timeout(spec.timeout())
                .maxRetries(spec.maxRetries())
                .logRequestsAndResponses(logRequests)
                .build();
    }

    private static StreamingChatModel buildAzureStreaming(LlmEndpointSpec spec, boolean logRequests) {
        return dev.langchain4j.model.azure.AzureOpenAiStreamingChatModel.builder()
                .endpoint(spec.baseUrl())
                .apiKey(spec.apiKey())
                .deploymentName(spec.modelName())
                .timeout(spec.timeout())
                .logRequestsAndResponses(logRequests)
                .build();
    }

    private static String normalize(String protocol) {
        return protocol == null || protocol.isBlank()
                ? AiChannel.PROTOCOL_OPENAI_COMPATIBLE : protocol.trim().toUpperCase();
    }
}
