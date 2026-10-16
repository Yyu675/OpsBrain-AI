package com.devops.agent.infrastructure.ai;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link FallbackStreamingChatModel} 流式主备降级契约测试（方案 A）。
 *
 * <h3>锁住的核心保证：首包边界</h3>
 * <ol>
 *   <li>主模型<b>首包前</b>失败（onError，尚未吐 token）→ 备用接管，对原始 handler 重发；</li>
 *   <li>主模型<b>已吐 token 后</b>失败 → 不降级（避免输出错乱），error 原样透传；</li>
 *   <li>主模型正常流式完成 → 不碰备用，成功记入熔断器；</li>
 *   <li>主备都失败 → error 透传（不吞）。</li>
 * </ol>
 *
 * <p>桩模型同步触发回调（真实实现是异步，但降级判定逻辑与线程模型无关）。</p>
 */
@DisplayName("FallbackStreamingChatModel 流式主备降级")
class FallbackStreamingChatModelTest {

    private static ChatRequest ping() {
        return ChatRequest.builder().messages(List.of(UserMessage.from("ping"))).build();
    }

    /** 立即 onError 的故障流模型（首包前失败）。 */
    private static StreamingChatModel errorBeforeFirstToken(AtomicInteger calls) {
        StreamingChatModel m = org.mockito.Mockito.mock(StreamingChatModel.class);
        org.mockito.Mockito.doAnswer(inv -> {
            calls.incrementAndGet();
            inv.getArgument(1, StreamingChatResponseHandler.class).onError(new RuntimeException("连接超时"));
            return null;
        }).when(m).chat(org.mockito.ArgumentMatchers.any(ChatRequest.class),
                org.mockito.ArgumentMatchers.any(StreamingChatResponseHandler.class));
        return m;
    }

    /** 先吐一个 token 再 onError（已输出部分，不可降级）。 */
    private static StreamingChatModel errorAfterFirstToken(AtomicInteger calls) {
        StreamingChatModel m = org.mockito.Mockito.mock(StreamingChatModel.class);
        org.mockito.Mockito.doAnswer(inv -> {
            calls.incrementAndGet();
            StreamingChatResponseHandler h = inv.getArgument(1, StreamingChatResponseHandler.class);
            h.onPartialResponse("半截");
            h.onError(new RuntimeException("流中断"));
            return null;
        }).when(m).chat(org.mockito.ArgumentMatchers.any(ChatRequest.class),
                org.mockito.ArgumentMatchers.any(StreamingChatResponseHandler.class));
        return m;
    }

    /** 正常完成：吐 token → onCompleteResponse。 */
    private static StreamingChatModel success(String text, AtomicInteger calls) {
        StreamingChatModel m = org.mockito.Mockito.mock(StreamingChatModel.class);
        org.mockito.Mockito.doAnswer(inv -> {
            calls.incrementAndGet();
            StreamingChatResponseHandler h = inv.getArgument(1, StreamingChatResponseHandler.class);
            h.onPartialResponse(text);
            h.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from(text)).build());
            return null;
        }).when(m).chat(org.mockito.ArgumentMatchers.any(ChatRequest.class),
                org.mockito.ArgumentMatchers.any(StreamingChatResponseHandler.class));
        return m;
    }

    private static CircuitBreaker freshBreaker() {
        CircuitBreakerConfig cfg = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(4)
                .minimumNumberOfCalls(2)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(60))
                .build();
        return CircuitBreakerRegistry.of(cfg).circuitBreaker("test-stream-" + System.nanoTime());
    }

    /** 收集 handler 回调的录制器。 */
    private static final class Recorder implements StreamingChatResponseHandler {
        final List<String> partials = new ArrayList<>();
        final AtomicReference<ChatResponse> complete = new AtomicReference<>();
        final AtomicReference<Throwable> error = new AtomicReference<>();

        @Override public void onPartialResponse(String p) { partials.add(p); }
        @Override public void onCompleteResponse(ChatResponse r) { complete.set(r); }
        @Override public void onError(Throwable t) { error.set(t); }
    }

    @Test
    @DisplayName("首包前失败 → 备用接管，原始 handler 收到备用输出")
    void errorBeforeFirstTokenFallsBack() {
        AtomicInteger primaryCalls = new AtomicInteger();
        AtomicInteger fallbackCalls = new AtomicInteger();
        FallbackStreamingChatModel model = new FallbackStreamingChatModel(
                errorBeforeFirstToken(primaryCalls), success("backup-out", fallbackCalls),
                freshBreaker(), "main", "backup");

        Recorder rec = new Recorder();
        model.chat(ping(), rec);

        assertThat(rec.error.get()).isNull();                       // 未向上层报错
        assertThat(rec.partials).containsExactly("backup-out");     // 收到备用输出
        assertThat(rec.complete.get()).isNotNull();
        assertThat(primaryCalls.get()).isEqualTo(1);
        assertThat(fallbackCalls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("已吐 token 后失败 → 不降级，error 原样透传（避免输出错乱）")
    void errorAfterFirstTokenDoesNotFallBack() {
        AtomicInteger primaryCalls = new AtomicInteger();
        AtomicInteger fallbackCalls = new AtomicInteger();
        FallbackStreamingChatModel model = new FallbackStreamingChatModel(
                errorAfterFirstToken(primaryCalls), success("backup-out", fallbackCalls),
                freshBreaker(), "main", "backup");

        Recorder rec = new Recorder();
        model.chat(ping(), rec);

        assertThat(rec.error.get()).isNotNull();                    // error 透传
        assertThat(rec.partials).containsExactly("半截");            // 主模型已输出的部分保留
        assertThat(fallbackCalls.get()).isZero();                   // 备用绝不被调用
        assertThat(primaryCalls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("主模型正常完成 → 不碰备用，成功记入熔断器")
    void primarySuccessDoesNotTouchFallback() {
        AtomicInteger primaryCalls = new AtomicInteger();
        AtomicInteger fallbackCalls = new AtomicInteger();
        CircuitBreaker breaker = freshBreaker();
        FallbackStreamingChatModel model = new FallbackStreamingChatModel(
                success("main-out", primaryCalls), success("backup-out", fallbackCalls),
                breaker, "main", "backup");

        Recorder rec = new Recorder();
        model.chat(ping(), rec);

        assertThat(rec.partials).containsExactly("main-out");
        assertThat(rec.complete.get()).isNotNull();
        assertThat(fallbackCalls.get()).isZero();
        assertThat(breaker.getMetrics().getNumberOfSuccessfulCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("主备都失败 → error 透传（不吞异常）")
    void bothFailPropagatesError() {
        FallbackStreamingChatModel model = new FallbackStreamingChatModel(
                errorBeforeFirstToken(new AtomicInteger()), errorBeforeFirstToken(new AtomicInteger()),
                freshBreaker(), "main", "backup");

        Recorder rec = new Recorder();
        model.chat(ping(), rec);

        assertThat(rec.error.get()).isNotNull();
        assertThat(rec.partials).isEmpty();
    }

    @Test
    @DisplayName("连续首包前失败 → 熔断 OPEN 后直接走备用，主模型不再被调用")
    void circuitOpensAndSkipsPrimary() {
        AtomicInteger primaryCalls = new AtomicInteger();
        AtomicInteger fallbackCalls = new AtomicInteger();
        CircuitBreaker breaker = freshBreaker();
        FallbackStreamingChatModel model = new FallbackStreamingChatModel(
                errorBeforeFirstToken(primaryCalls), success("backup-out", fallbackCalls),
                breaker, "main", "backup");

        for (int i = 0; i < 4 && breaker.getState() != CircuitBreaker.State.OPEN; i++) {
            model.chat(ping(), new Recorder());
        }
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        int primaryBefore = primaryCalls.get();
        Recorder rec = new Recorder();
        model.chat(ping(), rec);

        assertThat(rec.partials).containsExactly("backup-out");
        assertThat(primaryCalls.get()).isEqualTo(primaryBefore);    // OPEN 后主未再被调用
    }
}
