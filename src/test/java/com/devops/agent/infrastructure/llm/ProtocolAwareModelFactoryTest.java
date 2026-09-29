package com.devops.agent.infrastructure.llm;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link ProtocolAwareModelFactory} 协议分发逻辑单测（方案 A 多协议支持）。
 * <p>只验证「按 protocol 返回正确的模型类」，不发起真实调用（无凭证）。</p>
 */
class ProtocolAwareModelFactoryTest {

    private static final LlmEndpointSpec SPEC = LlmEndpointSpec.chat(
            "https://api.example.com/v1", "sk-test", "test-model", Duration.ofSeconds(30), 2);

    @Test
    @DisplayName("OPENAI_COMPATIBLE → OpenAiChatModel（现有行为不变）")
    void openaiCompatibleReturnsOpenAiModel() {
        ChatModel m = ProtocolAwareModelFactory.chat(SPEC, "OPENAI_COMPATIBLE", false);
        assertEquals("dev.langchain4j.model.openai.OpenAiChatModel", m.getClass().getName());
    }

    @Test
    @DisplayName("ANTHROPIC → AnthropicChatModel（原生协议真实生效）")
    void anthropicReturnsAnthropicModel() {
        ChatModel m = ProtocolAwareModelFactory.chat(SPEC, "ANTHROPIC", false);
        assertEquals("dev.langchain4j.model.anthropic.AnthropicChatModel", m.getClass().getName());
    }

    @Test
    @DisplayName("AZURE_OPENAI → AzureOpenAiChatModel（deployment 维度）")
    void azureReturnsAzureModel() {
        ChatModel m = ProtocolAwareModelFactory.chat(SPEC, "AZURE_OPENAI", false);
        assertEquals("dev.langchain4j.model.azure.AzureOpenAiChatModel", m.getClass().getName());
    }

    @Test
    @DisplayName("CUSTOM → 回落 OpenAI 兼容（自定义协议多数仍兼容 OpenAI 格式）")
    void customFallsBackToOpenAi() {
        ChatModel m = ProtocolAwareModelFactory.chat(SPEC, "CUSTOM", false);
        assertEquals("dev.langchain4j.model.openai.OpenAiChatModel", m.getClass().getName());
    }

    @Test
    @DisplayName("protocol 为 null/空 → 回落 OpenAI 兼容（向后兼容存量数据）")
    void nullProtocolFallsBackToOpenAi() {
        ChatModel m = ProtocolAwareModelFactory.chat(SPEC, null, false);
        assertEquals("dev.langchain4j.model.openai.OpenAiChatModel", m.getClass().getName());
    }

    @Test
    @DisplayName("流式：ANTHROPIC → AnthropicStreamingChatModel")
    void anthropicStreamingReturnsAnthropicModel() {
        StreamingChatModel m = ProtocolAwareModelFactory.streamingChat(SPEC.streaming(), "ANTHROPIC", false);
        assertEquals("dev.langchain4j.model.anthropic.AnthropicStreamingChatModel", m.getClass().getName());
    }
}
