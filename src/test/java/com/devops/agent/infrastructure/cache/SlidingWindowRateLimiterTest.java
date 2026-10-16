package com.devops.agent.infrastructure.cache;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 滑动窗口限流器故障分治测试（批 75 / P1-3，报告 174 审计件）。
 *
 * <p>注入-还原语义：Redis 抛异常即「注入」——fail-closed 分支若被删除
 * （恒 fail-open），{@code failClosedBranchRejectsOnRedisError} 立刻红。</p>
 */
@DisplayName("限流器 Redis 故障分治（fail-open vs fail-closed）")
class SlidingWindowRateLimiterTest {

    private StringRedisTemplate redis;
    private SlidingWindowRateLimiter limiter;

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        limiter = new SlidingWindowRateLimiter(redis);
    }

    private void redisIsBroken() {
        when(redis.execute(any(RedisScript.class), anyList(),
                any(), any(), any(), any()))
                .thenThrow(new RuntimeException("connection refused"));
    }

    @Test
    @DisplayName("防线型限流（failClosed=true）Redis 故障时拒绝——webhook 免鉴权直写库，防线不能静默消失")
    void failClosedBranchRejectsOnRedisError() {
        redisIsBroken();

        boolean allowed = limiter.tryAcquire("webhook", "1.2.3.4", 300, 60_000, true);

        assertThat(allowed)
                .as("fail-closed 分支：防线型限流在 Redis 故障时必须拒绝，"
                        + "放行=免鉴权写入端点失去最后防线")
                .isFalse();
    }

    @Test
    @DisplayName("可用性型限流（failClosed=false）Redis 故障时放行——chat 故障期必须可用，额度另有成本熔断兜底")
    void failOpenBranchAllowsOnRedisError() {
        redisIsBroken();

        boolean allowed = limiter.tryAcquire("chat", "user-1", 20, 60_000, false);

        assertThat(allowed)
                .as("fail-open 分支（既有行为）：可用性端点不因限流组件故障而不可用")
                .isTrue();
    }

    @Test
    @DisplayName("旧签名（四参）保持 fail-open 默认——既有 chat 调用方零改动")
    void legacySignatureDefaultsToFailOpen() {
        redisIsBroken();

        assertThat(limiter.tryAcquire("chat", "user-1", 20, 60_000)).isTrue();
    }

    @Test
    @DisplayName("Redis 正常时放行（allowed=1）且限流生效（allowed=0 拒绝）——分治不影响正常路径")
    void normalPathUnaffected() {
        when(redis.execute(any(RedisScript.class), anyList(),
                any(), any(), any(), any()))
                .thenReturn(1L, 0L);

        assertThat(limiter.tryAcquire("webhook", "ip", 1, 60_000, true)).isTrue();
        assertThat(limiter.tryAcquire("webhook", "ip", 1, 60_000, true)).isFalse();
    }
}
