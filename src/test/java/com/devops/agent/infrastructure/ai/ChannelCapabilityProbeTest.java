package com.devops.agent.infrastructure.ai;

import com.devops.agent.domain.ai.AiChannel;
import com.devops.agent.domain.ai.AiChannelCapabilityRepository;
import com.devops.agent.domain.ai.AiChannelRepository;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ChannelCapabilityProbe} 能力探测测试（P4）。
 *
 * <h3>为什么用子类接缝而非 mockStatic</h3>
 * 探测要建真实模型走 HTTP，测试用子类覆写 {@code newChatModel}/{@code newStreamingChatModel}/
 * {@code newEmbeddingModel} 注入桩模型——与 {@code KnowledgeWriteGuardTest} 同一决策
 * （本项目不引入 mockito-inline）。
 *
 * <h3>覆盖重点</h3>
 * <ul>
 *   <li><b>三态判定</b>：支持/明确不支持（4xx unsupported 关键词）/探测失败（超时网络，
 *       UNKNOWN ≠ 不支持）——这是本服务最核心的语义，前端展示全靠它；</li>
 *   <li><b>function calling 用 REQUIRED 强制触发</b>：模型按要求发起工具调用才判支持；</li>
 *   <li><b>基础对话不通时短路</b>：其余项标 UNKNOWN（不是不支持，是没测成），
 *       避免「ping 挂了就全画红叉」的误读；</li>
 *   <li><b>结果落库</b>：探测完成必须写 capability 表（页面刷新后要能读回）。</li>
 * </ul>
 */
class ChannelCapabilityProbeTest {

    private static final String BASE = "https://dashscope.aliyuncs.com/compatible-mode/v1";

    private final AiChannelRepository channelRepo = mock(AiChannelRepository.class);
    private final AiChannelCapabilityRepository capabilityRepo = mock(AiChannelCapabilityRepository.class);

    /** 可控桩模型槽位 */
    private ChatModel chatStub = mock(ChatModel.class);
    private StreamingChatModel streamingStub = mock(StreamingChatModel.class);
    private EmbeddingModel embeddingStub = mock(EmbeddingModel.class);

    private final ChannelCapabilityProbe probe = new ChannelCapabilityProbe(channelRepo, capabilityRepo, "test-secret") {
        @Override ChatModel newChatModel(com.devops.agent.infrastructure.llm.LlmEndpointSpec spec) { return chatStub; }
        @Override StreamingChatModel newStreamingChatModel(com.devops.agent.infrastructure.llm.LlmEndpointSpec spec) { return streamingStub; }
        @Override EmbeddingModel newEmbeddingModel(com.devops.agent.infrastructure.llm.LlmEndpointSpec spec) { return embeddingStub; }
    };

    private AiChannel chatChannel() {
        return new AiChannel("chat", BASE, "enc-key", "sk-****1234",
                "qwen-turbo", "deepseek-v4", null, null, "ACTIVE", LocalDateTime.now());
    }

    private AiChannel embeddingChannel() {
        return new AiChannel("embedding", BASE, "enc-key", "sk-****1234",
                null, null, "qwen3-text-embedding", 1536, "ACTIVE", LocalDateTime.now());
    }

    private static ChatResponse plainText(String text) {
        return ChatResponse.builder().aiMessage(AiMessage.from(text)).build();
    }

    // ==================================================================

    @Nested
    @DisplayName("chat 渠道能力探测")
    class ChatProbe {

        @Test
        @DisplayName("全能力支持：对话/流式/工具调用/JSON 全绿，结果落库")
        void allCapabilitiesSupported() {
            when(channelRepo.findByKey("chat")).thenReturn(Optional.of(chatChannel()));
            // 对话 + 工具调用 + JSON 都返回正常；工具调用时返回 toolExecutionRequests
            when(chatStub.chat(any(ChatRequest.class))).thenAnswer(inv -> {
                ChatRequest req = inv.getArgument(0);
                boolean hasTools = req.parameters() != null
                        && req.parameters().toolSpecifications() != null
                        && !req.parameters().toolSpecifications().isEmpty();
                if (hasTools) {
                    AiMessage msg = AiMessage.from(ToolExecutionRequest.builder()
                            .id("call_1").name("get_current_time").arguments("{}").build());
                    return ChatResponse.builder().aiMessage(msg).build();
                }
                return plainText("{\"ok\":true}");
            });
            // 流式：回调 onPartialResponse + onCompleteResponse
            doAnswer(inv -> {
                StreamingChatResponseHandler h = inv.getArgument(1);
                h.onPartialResponse("Hello");
                h.onCompleteResponse(plainText("Hello"));
                return null;
            }).when(streamingStub).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));

            ChannelCapabilityProbe.ProbeResult result = probe.probe("chat");

            Map<String, ChannelCapabilityProbe.CapabilityItem> caps = result.capabilities();
            assertThat(caps.get("chat").state()).isEqualTo("SUPPORTED");
            assertThat(caps.get("streaming").state()).isEqualTo("SUPPORTED");
            assertThat(caps.get("function_calling").state()).isEqualTo("SUPPORTED");
            assertThat(caps.get("json_mode").state()).isEqualTo("SUPPORTED");
            // 落库
            ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
            verify(capabilityRepo).save(org.mockito.ArgumentMatchers.eq("chat"), jsonCaptor.capture());
            assertThat(jsonCaptor.getValue()).contains("SUPPORTED");
        }

        @Test
        @DisplayName("function calling：强制 REQUIRED 下模型未发起工具调用 → UNSUPPORTED")
        void functionCallingUnsupported() {
            when(channelRepo.findByKey("chat")).thenReturn(Optional.of(chatChannel()));
            when(chatStub.chat(any(ChatRequest.class))).thenAnswer(inv -> plainText("现在三点")); // 不调工具
            doAnswer(inv -> {
                StreamingChatResponseHandler h = inv.getArgument(1);
                h.onPartialResponse("x");
                h.onCompleteResponse(plainText("x"));
                return null;
            }).when(streamingStub).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));

            ChannelCapabilityProbe.ProbeResult result = probe.probe("chat");

            assertThat(result.capabilities().get("function_calling").state()).isEqualTo("UNSUPPORTED");
        }

        @Test
        @DisplayName("基础对话不通 → 其余三项短路为 UNKNOWN（不是不支持，是没测成）")
        void basicChatDownShortCircuits() {
            when(channelRepo.findByKey("chat")).thenReturn(Optional.of(chatChannel()));
            when(chatStub.chat(any(ChatRequest.class))).thenThrow(new RuntimeException("Connection refused"));

            ChannelCapabilityProbe.ProbeResult result = probe.probe("chat");

            Map<String, ChannelCapabilityProbe.CapabilityItem> caps = result.capabilities();
            assertThat(caps.get("chat").state()).isEqualTo("UNKNOWN");
            assertThat(caps.get("streaming").state()).isEqualTo("UNKNOWN");
            assertThat(caps.get("function_calling").state()).isEqualTo("UNKNOWN");
            assertThat(caps.get("json_mode").state()).isEqualTo("UNKNOWN");
            // UNKNOWN 不等于不支持——前端不能画红叉
            assertThat(caps.get("function_calling").detail()).doesNotContain("不支持");
        }

        @Test
        @DisplayName("上游明确报不支持（400 + unsupported）→ UNSUPPORTED；超时 → UNKNOWN")
        void errorClassification() {
            when(channelRepo.findByKey("chat")).thenReturn(Optional.of(chatChannel()));
            // 对话正常，工具调用报「不支持」
            when(chatStub.chat(any(ChatRequest.class))).thenAnswer(inv -> {
                ChatRequest req = inv.getArgument(0);
                boolean hasTools = req.parameters() != null
                        && req.parameters().toolSpecifications() != null
                        && !req.parameters().toolSpecifications().isEmpty();
                if (hasTools) throw new RuntimeException("400: this model does not support tools");
                return plainText("{\"ok\":true}");
            });
            doAnswer(inv -> {
                StreamingChatResponseHandler h = inv.getArgument(1);
                h.onCompleteResponse(plainText("x"));
                return null;
            }).when(streamingStub).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));

            ChannelCapabilityProbe.ProbeResult result = probe.probe("chat");

            assertThat(result.capabilities().get("function_calling").state()).isEqualTo("UNSUPPORTED");
            assertThat(result.capabilities().get("function_calling").detail()).contains("不支持");
        }
    }

    // ==================================================================

    @Nested
    @DisplayName("embedding 渠道能力探测")
    class EmbeddingProbe {

        @Test
        @DisplayName("向量化 + 批量均正常，维度匹配 1536")
        void embeddingAndBatchSupported() {
            when(channelRepo.findByKey("embedding")).thenReturn(Optional.of(embeddingChannel()));
            when(embeddingStub.embed(anyString())).thenAnswer(inv ->
                    Response.from(Embedding.from(new float[1536])));
            when(embeddingStub.embedAll(anyList())).thenAnswer(inv ->
                    Response.from(List.of(Embedding.from(new float[1536]), Embedding.from(new float[1536]))));

            ChannelCapabilityProbe.ProbeResult result = probe.probe("embedding");

            assertThat(result.capabilities().get("embed").state()).isEqualTo("SUPPORTED");
            assertThat(result.capabilities().get("embed_batch").state()).isEqualTo("SUPPORTED");
        }

        @Test
        @DisplayName("维度不符（实测3072≠配置1536）→ UNSUPPORTED")
        void dimensionMismatchUnsupported() {
            when(channelRepo.findByKey("embedding")).thenReturn(Optional.of(embeddingChannel()));
            when(embeddingStub.embed(anyString())).thenAnswer(inv ->
                    Response.from(Embedding.from(new float[3072])));

            ChannelCapabilityProbe.ProbeResult result = probe.probe("embedding");

            assertThat(result.capabilities().get("embed").state()).isEqualTo("UNSUPPORTED");
            assertThat(result.capabilities().get("embed").detail()).contains("3072");
        }
    }

    // ==================================================================

    @Nested
    @DisplayName("结果读取与边界")
    class ReadAndBoundary {

        @Test
        @DisplayName("渠道不存在 → 404 语义（IllegalStateException）")
        void channelNotFound() {
            when(channelRepo.findByKey("ghost")).thenReturn(Optional.empty());
            assertThatThrownBy(() -> probe.probe("ghost"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("渠道不存在");
        }

        @Test
        @DisplayName("readStored 读回落库结果（JSON 往返不丢三态）")
        void readStoredRoundTrip() {
            String json = "{\"chat\":{\"state\":\"SUPPORTED\",\"detail\":\"ok\"}," +
                    "\"function_calling\":{\"state\":\"UNKNOWN\",\"detail\":\"超时\"}}";
            when(capabilityRepo.findByChannel("chat")).thenReturn(Optional.of(
                    new AiChannelCapabilityRepository.CapabilityRow(json, LocalDateTime.of(2026, 9, 22, 10, 0))));

            ChannelCapabilityProbe.ProbeResult result = probe.readStored("chat");

            assertThat(result).isNotNull();
            assertThat(result.capabilities().get("chat").state()).isEqualTo("SUPPORTED");
            assertThat(result.capabilities().get("function_calling").state()).isEqualTo("UNKNOWN");
        }

        @Test
        @DisplayName("从未探测的渠道 readStored 返回 null（前端据此显示「未实测」而非红叉）")
        void readStoredEmpty() {
            when(capabilityRepo.findByChannel("chat")).thenReturn(Optional.empty());
            assertThat(probe.readStored("chat")).isNull();
        }
    }
}
