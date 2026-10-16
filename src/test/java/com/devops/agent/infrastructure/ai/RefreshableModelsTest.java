package com.devops.agent.infrastructure.ai;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
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

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link RefreshableChatModel} / {@link RefreshableEmbeddingModel} /
 * {@link RefreshableStreamingChatModel} 原子替换契约测试（P3-1）。
 *
 * <h3>为什么测它</h3>
 * 热更新的核心保证：Spring Bean 引用不变，但内部 delegate 运行时可原子替换。
 * Agent 引擎全程持有的是包装器，渠道配置更新后新请求必须立刻走新模型。
 * 本测试锁住：
 * <ol>
 *   <li><b>初始化后立即可用</b>——构造时设置的 delegate 立即可调用；</li>
 *   <li><b>原子替换切流</b>——refreshTo 后新调用走新模型，旧 delegate 不再被调用；</li>
 *   <li><b>多次替换均生效</b>——连续 refreshTo 每次都切到最新 delegate；</li>
 *   <li><b>替换不抛异常</b>——refreshTo 对调用方透明；</li>
 *   <li><b>维度一致性</b>——embedding 替换前后向量维度不变（1536 铁律不被热更新破坏）。</li>
 * </ol>
 */
class RefreshableModelsTest {

    // ==================== RefreshableChatModel ====================

    @Nested
    @DisplayName("RefreshableChatModel 原子替换")
    class ChatModelRefresh {

        @Test
        @DisplayName("初始化 delegate 立即可调用")
        void initialDelegateIsCallable() {
            RefreshableChatModel subject = new RefreshableChatModel(stubChat("init-response"));

            ChatResponse resp = subject.chat(req());

            assertThat(resp.aiMessage().text()).isEqualTo("init-response");
        }

        @Test
        @DisplayName("refreshTo 后新调用走新模型，旧模型不再被调用")
        void refreshToAtomicallySwitches() {
            AtomicInteger firstCalls = new AtomicInteger(0);
            AtomicInteger secondCalls = new AtomicInteger(0);
            ChatModel first = countingChat("first", firstCalls);
            ChatModel second = countingChat("second", secondCalls);

            RefreshableChatModel subject = new RefreshableChatModel(first);
            subject.chat(req());
            assertThat(firstCalls.get()).isEqualTo(1);
            assertThat(secondCalls.get()).isZero();

            subject.refreshTo(second);
            subject.chat(req());

            assertThat(firstCalls.get()).isEqualTo(1);
            assertThat(secondCalls.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("连续多次 refreshTo 每次均切到最新 delegate")
        void multipleRefreshesWork() {
            RefreshableChatModel subject = new RefreshableChatModel(stubChat("r1"));
            assertThat(subject.chat(req()).aiMessage().text()).isEqualTo("r1");

            subject.refreshTo(stubChat("r2"));
            assertThat(subject.chat(req()).aiMessage().text()).isEqualTo("r2");

            subject.refreshTo(stubChat("r3"));
            assertThat(subject.chat(req()).aiMessage().text()).isEqualTo("r3");
        }

        @Test
        @DisplayName("refreshTo 不抛异常（对调用方透明）")
        void refreshToDoesNotThrow() {
            RefreshableChatModel subject = new RefreshableChatModel(stubChat("x"));
            assertThatCode(() -> subject.refreshTo(stubChat("y"))).doesNotThrowAnyException();
        }

        private ChatModel stubChat(String responseText) {
            ChatModel m = mock(ChatModel.class);
            when(m.chat(any(ChatRequest.class))).thenAnswer(inv ->
                    ChatResponse.builder().aiMessage(AiMessage.from(responseText)).build());
            return m;
        }

        private ChatModel countingChat(String responseText, AtomicInteger counter) {
            ChatModel m = mock(ChatModel.class);
            when(m.chat(any(ChatRequest.class))).thenAnswer(inv -> {
                counter.incrementAndGet();
                return ChatResponse.builder().aiMessage(AiMessage.from(responseText)).build();
            });
            return m;
        }
    }

    // ==================== RefreshableEmbeddingModel ====================

    @Nested
    @DisplayName("RefreshableEmbeddingModel 原子替换 + 维度一致性")
    class EmbeddingModelRefresh {

        @Test
        @DisplayName("初始化 delegate 立即可调用")
        void initialDelegateIsCallable() {
            RefreshableEmbeddingModel subject = new RefreshableEmbeddingModel(stubEmbedding(1536));

            Response<Embedding> resp = subject.embed("test");
            assertThat(resp.content().vector()).hasSize(1536);
        }

        @Test
        @DisplayName("refreshTo 后新调用走新模型，旧模型不再被调用")
        void refreshToSwitches() {
            AtomicInteger firstCalls = new AtomicInteger(0);
            AtomicInteger secondCalls = new AtomicInteger(0);
            EmbeddingModel first = countingEmbedding(firstCalls);
            EmbeddingModel second = countingEmbedding(secondCalls);

            RefreshableEmbeddingModel subject = new RefreshableEmbeddingModel(first);
            subject.embed("t");
            assertThat(firstCalls.get()).isEqualTo(1);

            subject.refreshTo(second);
            subject.embed("t");

            assertThat(firstCalls.get()).isEqualTo(1);
            assertThat(secondCalls.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("维度一致性：替换前后 embedding 向量维度不变（1536 铁律）")
        void dimensionConsistencyAcrossRefresh() {
            RefreshableEmbeddingModel subject = new RefreshableEmbeddingModel(stubEmbedding(1536));
            Response<Embedding> before = subject.embed("test");

            subject.refreshTo(stubEmbedding(1536));
            Response<Embedding> after = subject.embed("test");

            assertThat(before.content().vector()).hasSize(1536);
            assertThat(after.content().vector()).hasSize(1536);
        }

        private EmbeddingModel stubEmbedding(int dimension) {
            EmbeddingModel m = mock(EmbeddingModel.class);
            when(m.embed(any(String.class))).thenAnswer(inv ->
                    Response.from(Embedding.from(new float[dimension])));
            return m;
        }

        private EmbeddingModel countingEmbedding(AtomicInteger counter) {
            EmbeddingModel m = mock(EmbeddingModel.class);
            when(m.embed(any(String.class))).thenAnswer(inv -> {
                counter.incrementAndGet();
                return Response.from(Embedding.from(new float[1536]));
            });
            return m;
        }
    }

    // ==================== RefreshableStreamingChatModel ====================

    @Nested
    @DisplayName("RefreshableStreamingChatModel 原子替换")
    class StreamingChatModelRefresh {

        @Test
        @DisplayName("refreshTo 后新调用走新模型")
        void refreshToSwitchesHandler() {
            AtomicInteger firstCalls = new AtomicInteger(0);
            AtomicInteger secondCalls = new AtomicInteger(0);
            StreamingChatModel first = countingStreaming(firstCalls);
            StreamingChatModel second = countingStreaming(secondCalls);

            RefreshableStreamingChatModel subject = new RefreshableStreamingChatModel(first);
            subject.chat(req(), handler());
            assertThat(firstCalls.get()).isEqualTo(1);

            subject.refreshTo(second);
            subject.chat(req(), handler());

            assertThat(firstCalls.get()).isEqualTo(1);
            assertThat(secondCalls.get()).isEqualTo(1);
        }

        private StreamingChatModel countingStreaming(AtomicInteger counter) {
            StreamingChatModel m = mock(StreamingChatModel.class);
            doAnswer(inv -> { counter.incrementAndGet(); return null; })
                    .when(m).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
            return m;
        }
    }

    // ==================== 工具 ====================

    private static ChatRequest req() {
        return ChatRequest.builder().messages(List.of(UserMessage.from("ping"))).build();
    }

    private static StreamingChatResponseHandler handler() {
        return mock(StreamingChatResponseHandler.class);
    }
}
