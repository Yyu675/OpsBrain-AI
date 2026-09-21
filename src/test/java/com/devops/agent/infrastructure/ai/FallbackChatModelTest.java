package com.devops.agent.infrastructure.ai;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link FallbackChatModel} 主备降级契约测试（方案 A）。
 *
 * <h3>锁住的核心保证</h3>
 * <ol>
 *   <li>主模型正常 → 不碰备用；</li>
 *   <li>主模型失败 → 备用接管，调用方拿到备用结果（降级透明）；</li>
 *   <li>失败计入熔断器，连续失败后 OPEN → 直接走备用，不再调主（省一次超时）；</li>
 *   <li>备用也失败 → 原样抛出（不吞异常，上层 SSE error 路径不变）。</li>
 * </ol>
 */
@DisplayName("FallbackChatModel 主备降级")
class FallbackChatModelTest {

    private static ChatRequest ping() {
        return ChatRequest.builder().messages(List.of(UserMessage.from("ping"))).build();
    }

    private static ChatResponse reply(String text) {
        return ChatResponse.builder().aiMessage(AiMessage.from(text)).build();
    }

    /** 每次调用返回固定文本的桩模型。 */
    private static ChatModel stub(String text, AtomicInteger calls) {
        ChatModel m = org.mockito.Mockito.mock(ChatModel.class);
        org.mockito.Mockito.when(m.chat(org.mockito.ArgumentMatchers.any(ChatRequest.class)))
                .thenAnswer(inv -> { calls.incrementAndGet(); return reply(text); });
        return m;
    }

    /** 每次调用都抛异常的故障模型。 */
    private static ChatModel broken(AtomicInteger calls) {
        ChatModel m = org.mockito.Mockito.mock(ChatModel.class);
        org.mockito.Mockito.when(m.chat(org.mockito.ArgumentMatchers.any(ChatRequest.class)))
                .thenAnswer(inv -> { calls.incrementAndGet(); throw new RuntimeException("上游超时"); });
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
        return CircuitBreakerRegistry.of(cfg).circuitBreaker("test-" + System.nanoTime());
    }

    @Test
    @DisplayName("主模型成功 → 返回主结果，备用零调用")
    void primarySuccessDoesNotTouchFallback() {
        AtomicInteger primaryCalls = new AtomicInteger();
        AtomicInteger fallbackCalls = new AtomicInteger();
        FallbackChatModel model = new FallbackChatModel(
                stub("main-ok", primaryCalls), stub("backup", fallbackCalls),
                freshBreaker(), "main", "backup");

        String text = model.chat(ping()).aiMessage().text();

        assertThat(text).isEqualTo("main-ok");
        assertThat(primaryCalls.get()).isEqualTo(1);
        assertThat(fallbackCalls.get()).isZero();
    }

    @Test
    @DisplayName("主模型失败 → 备用接管，返回备用结果（降级透明）")
    void primaryFailureFallsBack() {
        AtomicInteger primaryCalls = new AtomicInteger();
        AtomicInteger fallbackCalls = new AtomicInteger();
        FallbackChatModel model = new FallbackChatModel(
                broken(primaryCalls), stub("backup-ok", fallbackCalls),
                freshBreaker(), "main", "backup");

        String text = model.chat(ping()).aiMessage().text();

        assertThat(text).isEqualTo("backup-ok");
        assertThat(primaryCalls.get()).isEqualTo(1);
        assertThat(fallbackCalls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("失败计入熔断器：连续失败后 OPEN → 直接走备用，主模型不再被调用")
    void circuitOpensAndSkipsPrimary() {
        AtomicInteger primaryCalls = new AtomicInteger();
        AtomicInteger fallbackCalls = new AtomicInteger();
        CircuitBreaker breaker = freshBreaker();
        FallbackChatModel model = new FallbackChatModel(
                broken(primaryCalls), stub("backup-ok", fallbackCalls),
                breaker, "main", "backup");

        // 触发足够失败把熔断器打到 OPEN
        for (int i = 0; i < 4 && breaker.getState() != CircuitBreaker.State.OPEN; i++) {
            model.chat(ping());
        }
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        int primaryBefore = primaryCalls.get();
        int fallbackBefore = fallbackCalls.get();
        // OPEN 后再调用：直接走备用，主模型一次都不碰
        String text = model.chat(ping()).aiMessage().text();

        assertThat(text).isEqualTo("backup-ok");
        assertThat(primaryCalls.get()).isEqualTo(primaryBefore);   // 主未再被调用
        assertThat(fallbackCalls.get()).isEqualTo(fallbackBefore + 1);
    }

    @Test
    @DisplayName("主备都失败 → 抛出备用异常（不吞，上层 error 路径不变）")
    void bothFailThrows() {
        FallbackChatModel model = new FallbackChatModel(
                broken(new AtomicInteger()), broken(new AtomicInteger()),
                freshBreaker(), "main", "backup");

        assertThatThrownBy(() -> model.chat(ping()))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("主成功记入熔断器成功统计（CLOSED 保持）")
    void successKeepsCircuitClosed() {
        CircuitBreaker breaker = freshBreaker();
        FallbackChatModel model = new FallbackChatModel(
                stub("ok", new AtomicInteger()), stub("backup", new AtomicInteger()),
                breaker, "main", "backup");

        // 调用次数 = 滑动窗口大小（4）：COUNT_BASED 窗口只保留最近 N 次，
        // getNumberOfSuccessfulCalls() 是窗口内计数而非累计，超过窗口会被滚动丢弃
        for (int i = 0; i < 4; i++) model.chat(ping());

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(breaker.getMetrics().getNumberOfSuccessfulCalls()).isEqualTo(4);
    }
}
