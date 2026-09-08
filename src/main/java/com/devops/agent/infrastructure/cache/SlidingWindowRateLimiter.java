package com.devops.agent.infrastructure.cache;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 基于 Redis 的滑动窗口限流器。
 *
 * <h3>为什么需要</h3>
 * 项目此前<b>没有任何 API 层限流</b>。两个端点风险最高：
 * <ul>
 *   <li>{@code /api/v1/chat/stream}：单请求就是一次真实 LLM 调用，
 *       耗时长、花钱、占 Tomcat 异步线程。脚本循环即可打爆额度；</li>
 *   <li>{@code /api/v1/alerts/webhook}：<b>免鉴权</b>且直接写库。
 *       一次网络分区可让 Alertmanager 产生上万条告警，直接压垮数据库。</li>
 * </ul>
 *
 * <h3>算法</h3>
 * 用 Redis 有序集合（ZSET）实现真·滑动窗口，而非固定窗口计数：
 * <ol>
 *   <li>{@code ZREMRANGEBYSCORE} 清掉窗口外的旧记录；</li>
 *   <li>{@code ZCARD} 取窗口内当前请求数；</li>
 *   <li>未超限则 {@code ZADD} 记录本次，并刷新键 TTL。</li>
 * </ol>
 * 固定窗口在窗口边界会放过两倍流量（前窗末尾 + 后窗开头），
 * 滑动窗口没有这个缺口。
 *
 * <h3>原子性</h3>
 * 上述三步必须在一次 Lua 调用内完成。分开执行时，并发请求会在
 * 「读计数」与「写记录」之间穿插，导致实际放行量超过阈值。
 *
 * <h3>降级</h3>
 * Redis 异常时<b>放行</b>（fail-open）。限流是保护措施，不应成为
 * 新的单点故障——尤其本项目是运维排障平台，故障期必须可用。
 *
 * @author OpsBrain AI
 * @since 2026-08-24
 */
@Slf4j
@Component
public class SlidingWindowRateLimiter {

    private static final String KEY_PREFIX = "devops:rl:";

    /**
     * 滑动窗口限流脚本。
     * <p>返回 1 表示放行，0 表示拒绝。</p>
     * <p>成员用 {@code now-随机数} 保证唯一：同一毫秒内的多个请求若成员相同，
     * ZADD 会视作同一元素覆盖而非新增，导致限流<b>少计</b>。</p>
     */
    private static final RedisScript<Long> SLIDING_WINDOW = new DefaultRedisScript<>(
            """
            local key = KEYS[1]
            local now = tonumber(ARGV[1])
            local window = tonumber(ARGV[2])
            local limit = tonumber(ARGV[3])
            local member = ARGV[4]
            redis.call('ZREMRANGEBYSCORE', key, 0, now - window)
            local count = redis.call('ZCARD', key)
            if count >= limit then
              redis.call('PEXPIRE', key, window)
              return 0
            end
            redis.call('ZADD', key, now, member)
            redis.call('PEXPIRE', key, window)
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;

    public SlidingWindowRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * 尝试获取一个令牌。
     *
     * @param scope      限流域，如 {@code "chat"} / {@code "webhook"}
     * @param identity   限流主体，如 userId 或来源 IP
     * @param limit      窗口内允许的最大请求数；&lt;= 0 表示不限流
     * @param windowMs   窗口长度（毫秒）
     * @return true 放行，false 拒绝
     */
    public boolean tryAcquire(String scope, String identity, int limit, long windowMs) {
        if (limit <= 0) {
            return true;
        }
        String key = KEY_PREFIX + scope + ":" + identity;
        long now = System.currentTimeMillis();
        // 成员唯一化：同毫秒并发不会互相覆盖
        String member = now + "-" + Long.toHexString(java.util.concurrent.ThreadLocalRandom.current().nextLong());
        try {
            Long allowed = redis.execute(SLIDING_WINDOW, List.of(key),
                    String.valueOf(now), String.valueOf(windowMs),
                    String.valueOf(limit), member);
            return allowed == null || allowed == 1L;
        } catch (Exception e) {
            // fail-open：限流组件故障不应让业务不可用
            log.warn("⚠️ [RateLimit] Redis 异常，放行本次请求（fail-open）| scope={} | id={} | err={}",
                    scope, identity, e.getMessage());
            return true;
        }
    }
}
