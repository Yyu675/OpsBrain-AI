package com.devops.agent.infrastructure.ai;

import com.devops.agent.domain.ai.AiChannel;
import com.devops.agent.infrastructure.AiModelConfig;
import com.devops.agent.infrastructure.cache.SemanticCacheService;
import com.devops.agent.infrastructure.llm.LlmEndpointSpec;
import com.devops.agent.infrastructure.llm.OpenAiCompatibleModelFactory;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ChannelRefreshService} 热更新引擎测试（P3-1）。
 *
 * <h3>覆盖重点</h3>
 * <ul>
 *   <li><b>chat 渠道</b>：热更新同时替换 turbo/reasoner 两个同步模型 + 两个流式模型，
 *       四个 Refreshable 包装器都被 refreshTo 到 AiModelConfig 新构建的实例；</li>
 *   <li><b>embedding 渠道</b>：替换 embedding 模型且<b>清空语义缓存</b>——
 *       换 embedding 模型后旧向量的语义缓存命中会造成串库，必须同步失效；</li>
 *   <li><b>reranker 渠道</b>：明确返回「暂不支持」（不静默假装成功）；</li>
 *   <li><b>构建失败</b>：AiModelConfig.build* 抛异常 → 包装成 IllegalStateException，
 *       由 Controller 层降级为 restartRequired=true（配置已落 DB，重启生效）；</li>
 *   <li><b>连通性测试</b>：chat 走 ping 对话；embedding 走真实向量化并校验维度
 *       （维度不符时返回 success=false 且带实测维度，不是吞掉错误）。</li>
 * </ul>
 */
class ChannelRefreshServiceTest {

    // ==================== 夹具 ====================

    private static class Fixture {
        RefreshableChatModel turboChat = mock(RefreshableChatModel.class);
        RefreshableChatModel reasonerChat = mock(RefreshableChatModel.class);
        RefreshableStreamingChatModel turboStream = mock(RefreshableStreamingChatModel.class);
        RefreshableStreamingChatModel reasonerStream = mock(RefreshableStreamingChatModel.class);
        RefreshableEmbeddingModel embedding = mock(RefreshableEmbeddingModel.class);
        AiModelConfig config = mock(AiModelConfig.class);
        SemanticCacheService semanticCache = mock(SemanticCacheService.class);

        /** 探针桩表：spec → 该 spec 应返回的探针模型。测试用子类覆写 newProbe 注入。 */
        final java.util.Map<LlmEndpointSpec, ChatModel> probes = new java.util.HashMap<>();

        ChannelRefreshService service = new ChannelRefreshService(
                turboChat, reasonerChat, turboStream, reasonerStream, embedding,
                config, semanticCache) {
            @Override
            ChatModel newProbe(LlmEndpointSpec spec) {
                ChatModel probe = probes.get(spec);
                if (probe == null) throw new IllegalStateException("测试未为该 spec 登记探针");
                return probe;
            }
        };
    }

    // ==================================================================

    @Nested
    @DisplayName("chat 渠道热更新")
    class ChatRefresh {

        @Test
        @DisplayName("四个模型包装器全部原子替换为新构建实例（turbo+reasoner+双流式）")
        void chatRefreshReplacesAllFourModels() {
            Fixture f = new Fixture();
            ChatModel newTurbo = mock(ChatModel.class);
            ChatModel newReasoner = mock(ChatModel.class);
            StreamingChatModel newTurboStream = mock(StreamingChatModel.class);
            StreamingChatModel newReasonerStream = mock(StreamingChatModel.class);
            when(f.config.buildTurboChat()).thenReturn(newTurbo);
            when(f.config.buildReasonerChat()).thenReturn(newReasoner);
            when(f.config.buildTurboStreaming()).thenReturn(newTurboStream);
            when(f.config.buildReasonerStreaming()).thenReturn(newReasonerStream);

            String result = f.service.refresh(AiChannel.KEY_CHAT);

            assertThat(result).contains("chat");
            verify(f.turboChat).refreshTo(newTurbo);
            verify(f.reasonerChat).refreshTo(newReasoner);
            verify(f.turboStream).refreshTo(newTurboStream);
            verify(f.reasonerStream).refreshTo(newReasonerStream);
            // chat 热更新不动 embedding，也不清缓存
            verify(f.embedding, never()).refreshTo(any());
        }

        @Test
        @DisplayName("构建失败 → 包装成 IllegalStateException，Controller 据此降级 restartRequired=true")
        void buildFailureWrapsIntoIllegalState() {
            Fixture f = new Fixture();
            when(f.config.buildTurboChat()).thenThrow(new IllegalStateException("DB 连接失败"));

            assertThatThrownBy(() -> f.service.refresh(AiChannel.KEY_CHAT))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("热更新失败");
        }
    }

    // ==================================================================

    @Nested
    @DisplayName("embedding 渠道热更新")
    class EmbeddingRefresh {

        @Test
        @DisplayName("替换 embedding 模型且清空语义缓存（防旧向量串库命中）")
        void embeddingRefreshClearsSemanticCache() {
            Fixture f = new Fixture();
            EmbeddingModel newEmbedding = mock(EmbeddingModel.class);
            when(f.config.buildEmbedding()).thenReturn(newEmbedding);

            String result = f.service.refresh(AiChannel.KEY_EMBEDDING);

            assertThat(result).contains("embedding");
            verify(f.embedding).refreshTo(newEmbedding);
            verify(f.semanticCache).clearAllCache();
            // 不动 chat 模型
            verify(f.turboChat, never()).refreshTo(any());
        }

        @Test
        @DisplayName("缓存清空抛异常不阻断热更新（模型替换已完成）")
        void cacheClearFailureDoesNotBlock() {
            Fixture f = new Fixture();
            EmbeddingModel newEmbedding = mock(EmbeddingModel.class);
            when(f.config.buildEmbedding()).thenReturn(newEmbedding);
            doThrow(new RuntimeException("Redis down")).when(f.semanticCache).clearAllCache();

            String result = f.service.refresh(AiChannel.KEY_EMBEDDING);

            assertThat(result).contains("embedding");
            verify(f.embedding).refreshTo(newEmbedding);
        }
    }

    // ==================================================================

    @Nested
    @DisplayName("reranker 渠道")
    class RerankerRefresh {

        @Test
        @DisplayName("reranker 明确返回暂不支持（不静默假装成功）")
        void rerankerReturnsUnsupported() {
            Fixture f = new Fixture();

            String result = f.service.refresh(AiChannel.KEY_RERANKER);

            assertThat(result).contains("暂不支持");
            verify(f.turboChat, never()).refreshTo(any());
            verify(f.embedding, never()).refreshTo(any());
        }
    }

    // ==================================================================

    @Nested
    @DisplayName("连通性测试（P2-3，独立探针绕过 Fallback 装饰器）")
    class Connectivity {

        /**
         * 探针语义：testConnectivity 不复用装配好的 Bean，而是从 spec 建独立模型——
         * 否则主模型挂了会被备用透明兜住误报「正常」。测试经子类接缝 newProbe
         * 注入桩模型（本项目不引入 mockito-inline，静态工厂不可 mockStatic，
         * 与 KnowledgeWriteGuardTest 同一决策）。
         */
        @Test
        @DisplayName("chat：主模型 ping 成功且无备用 → success=true，消息含「未配置备用」")
        void chatPingSuccess() {
            Fixture f = new Fixture();
            LlmEndpointSpec spec = mock(LlmEndpointSpec.class);
            when(f.config.turboSpec()).thenReturn(spec);
            when(f.config.turboFallbackSpec()).thenReturn(null);
            ChatModel probe = mock(ChatModel.class);
            when(probe.chat(any(ChatRequest.class))).thenAnswer(inv ->
                    ChatResponse.builder().aiMessage(AiMessage.from("pong")).build());
            f.probes.put(spec, probe);

            ChannelRefreshService.ConnectivityResult r = f.service.testConnectivity(AiChannel.KEY_CHAT);

            assertThat(r.success()).isTrue();
            assertThat(r.message()).contains("未配置备用");
        }

        @Test
        @DisplayName("chat：主模型不通 → success=false 且消息点名「主模型不通」（不被备用掩盖）")
        void chatPrimaryDownReportedEvenIfFallbackExists() {
            Fixture f = new Fixture();
            LlmEndpointSpec spec = mock(LlmEndpointSpec.class);
            when(f.config.turboSpec()).thenReturn(spec);
            ChatModel brokenProbe = mock(ChatModel.class);
            when(brokenProbe.chat(any(ChatRequest.class)))
                    .thenThrow(new RuntimeException("Connection refused"));
            f.probes.put(spec, brokenProbe);

            ChannelRefreshService.ConnectivityResult r = f.service.testConnectivity(AiChannel.KEY_CHAT);

            assertThat(r.success()).isFalse();
            assertThat(r.message()).contains("主模型不通");
        }

        @Test
        @DisplayName("chat：主备都通 → 消息含「降级就绪」")
        void chatFallbackAlsoProbed() {
            Fixture f = new Fixture();
            LlmEndpointSpec main = mock(LlmEndpointSpec.class);
            LlmEndpointSpec fb = mock(LlmEndpointSpec.class);
            when(f.config.turboSpec()).thenReturn(main);
            when(f.config.turboFallbackSpec()).thenReturn(fb);
            ChatModel okProbe = mock(ChatModel.class);
            when(okProbe.chat(any(ChatRequest.class))).thenAnswer(inv ->
                    ChatResponse.builder().aiMessage(AiMessage.from("pong")).build());
            f.probes.put(main, okProbe);
            f.probes.put(fb, okProbe);

            ChannelRefreshService.ConnectivityResult r = f.service.testConnectivity(AiChannel.KEY_CHAT);

            assertThat(r.success()).isTrue();
            assertThat(r.message()).contains("降级就绪");
        }

        @Test
        @DisplayName("embedding：维度匹配（1536）→ success=true 带维度")
        void embeddingDimensionMatch() {
            Fixture f = new Fixture();
            when(f.embedding.embed(anyString())).thenAnswer(inv ->
                    Response.from(Embedding.from(new float[1536])));
            when(f.config.vectorDimension()).thenReturn(1536);

            ChannelRefreshService.ConnectivityResult r = f.service.testConnectivity(AiChannel.KEY_EMBEDDING);

            assertThat(r.success()).isTrue();
            assertThat(r.dimension()).isEqualTo(1536);
        }

        @Test
        @DisplayName("embedding：维度不符（实测3072≠配置1536）→ success=false 带实测维度，不吞错误")
        void embeddingDimensionMismatch() {
            Fixture f = new Fixture();
            when(f.embedding.embed(anyString())).thenAnswer(inv ->
                    Response.from(Embedding.from(new float[3072])));
            when(f.config.vectorDimension()).thenReturn(1536);

            ChannelRefreshService.ConnectivityResult r = f.service.testConnectivity(AiChannel.KEY_EMBEDDING);

            assertThat(r.success()).isFalse();
            assertThat(r.dimension()).isEqualTo(3072);
            assertThat(r.message()).contains("维度不符");
        }

        @Test
        @DisplayName("探针构建抛异常 → success=false 消息截断（异常详情不无限外泄）")
        void connectivityFailureIsCaptured() {
            Fixture f = new Fixture();
            when(f.config.turboSpec()).thenThrow(new RuntimeException("Connection refused: " + "x".repeat(300)));

            ChannelRefreshService.ConnectivityResult r = f.service.testConnectivity(AiChannel.KEY_CHAT);

            assertThat(r.success()).isFalse();
            assertThat(r.message().length()).isLessThanOrEqualTo(203); // 200 + "..."
        }

        @Test
        @DisplayName("reranker 连通性测试明确返回暂不支持")
        void rerankerConnectivityUnsupported() {
            Fixture f = new Fixture();

            ChannelRefreshService.ConnectivityResult r = f.service.testConnectivity(AiChannel.KEY_RERANKER);

            assertThat(r.success()).isFalse();
            assertThat(r.message()).contains("暂不支持");
        }
    }
}
