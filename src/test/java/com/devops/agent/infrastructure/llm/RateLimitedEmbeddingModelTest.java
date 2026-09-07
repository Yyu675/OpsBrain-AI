package com.devops.agent.infrastructure.llm;

import com.devops.agent.infrastructure.MockEmbeddingModel;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import dev.langchain4j.data.segment.TextSegment;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * LLM 限流装饰器契约（S0-3，路线图 §4.3 任务 0-3.5）。
 *
 * <h3>被钉死的行为</h3>
 * <ul>
 *   <li>限内放行且<b>结果原样透传</b>（装饰不得改变向量的语义）；</li>
 *   <li>超限<b>快速失败</b>并抛类型化的 {@link LlmRateLimitedException}——
 *       与云侧 429 可区分（见该异常 javadoc）；不排队等待（timeout=0，
 *       防止调度线程挂在许可上）。</li>
 * </ul>
 *
 * @author OpsBrain AI
 * @since 2026-09-07（S0-3）
 */
class RateLimitedEmbeddingModelTest {

    /** 测试用小限额注册表：每秒 2 次、零等待——行为与生产「llm」实例同源不同量。 */
    private static RateLimiterRegistry tinyRegistry() {
        return RateLimiterRegistry.of(RateLimiterConfig.custom()
                .limitForPeriod(2)
                .limitRefreshPeriod(Duration.ofSeconds(1))
                .timeoutDuration(Duration.ZERO)
                .build());
    }

    @Test
    @DisplayName("限额内的调用原样透传，向量与裸 mock 完全一致")
    void callsWithinLimitPassThroughUnchanged() {
        var raw = new MockEmbeddingModel(16);
        var wrapped = new RateLimitedEmbeddingModel(raw, tinyRegistry().rateLimiter("llm"));

        float[] viaWrapper = wrapped.embed("相同的文本").content().vector();
        float[] direct = raw.embed("相同的文本").content().vector();
        assertThat(viaWrapper)
                .as("装饰器只能管「放不放行」，不得触碰向量内容")
                .isEqualTo(direct);
    }

    @Test
    @DisplayName("超过限额的调用快速失败并抛出类型化限流异常（不发请求、不排队）")
    void callsBeyondLimitFailFastWithTypedException() {
        var wrapped = new RateLimitedEmbeddingModel(new MockEmbeddingModel(16),
                tinyRegistry().rateLimiter("llm"));

        wrapped.embed("a");
        wrapped.embed("b"); // 用完 2 次/秒 的配额

        long start = System.nanoTime();
        assertThatThrownBy(() -> wrapped.embed("c"))
                .isInstanceOf(LlmRateLimitedException.class)
                .hasMessageContaining("限流");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertThat(elapsedMs)
                .as("timeout-duration=0 的快速失败语义：超限调用不得排队等待下一个窗口")
                .isLessThan(200);
    }

    @Test
    @DisplayName("embedAll 批量路径同样计流（摄取/重建索引是配额吞吐最大的出口）")
    void embedAllCountsAgainstTheSameLimiter() {
        var wrapped = new RateLimitedEmbeddingModel(new MockEmbeddingModel(16),
                tinyRegistry().rateLimiter("llm"));

        wrapped.embedAll(List.of(TextSegment.from("x"), TextSegment.from("y")));
        wrapped.embed("z"); // 2 次/秒 配额已用完

        assertThatThrownBy(() -> wrapped.embedAll(List.of(TextSegment.from("w"))))
                .as("批量入口若漏流，摄取任务就成了绕过限流的后门")
                .isInstanceOf(LlmRateLimitedException.class);
    }
}
