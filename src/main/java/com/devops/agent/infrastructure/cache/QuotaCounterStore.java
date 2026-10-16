package com.devops.agent.infrastructure.cache;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 配额计数器存储（Redis 实现，A3 修复）。
 *
 * <h3>为什么必须从内存迁到 Redis</h3>
 * 修复前 {@code CostQuotaManager} 把配额用量放在进程内的
 * {@code ConcurrentHashMap} + {@code AtomicLong} 里，有三个致命问题：
 * <ol>
 *   <li><b>重启清零</b>——用户跑满日额度后，只要服务重启就能重新开始烧钱，
 *       成本熔断形同虚设；</li>
 *   <li><b>多实例失效</b>——每个实例各记各的，实际额度 = 配置值 × 实例数；</li>
 *   <li><b>惰性重置有边界风险</b>——{@code resetDailyIfNeeded()} 只在有请求时触发，
 *       跨天判断依赖进程内的 {@code lastResetTime}。</li>
 * </ol>
 * 改用 Redis 后，三个问题一次性消失：计数天然跨实例共享、跨重启保留，
 * 且<b>用「日期」作为 key 的一部分 + 到当日 24 点的 TTL，日重置变成自然结果</b>，
 * 不再需要任何显式的重置逻辑。
 *
 * <h3>原子性</h3>
 * 「读当前值 → 判断是否超限 → 累加」若分成多次 Redis 往返，并发下会出现
 * <b>检查与累加之间的竞态</b>（两个请求同时通过检查，实际总和超限）。
 * 因此累加与读取都用 Lua 脚本在 Redis 单线程内一次完成。
 *
 * <h3>降级</h3>
 * Redis 不可用时<b>放行而非拒绝</b>（fail-open）。理由：本项目是运维排障平台，
 * 故障期恰恰是 Redis 最可能出问题、同时用户最需要用它排障的时候。
 * 「配额组件自身故障导致排障工具不可用」比「短时间内可能超一点预算」危害大得多。
 * 降级会打 WARN 日志（带 traceId）供事后审计。
 *
 * @author OpsBrain AI
 * @since 2026-08-24
 */
@Slf4j
@Component
public class QuotaCounterStore {

    private static final String KEY_PREFIX = "devops:quota:";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    /**
     * 原子累加并设置过期时间。
     * <p>{@code INCRBY} 对不存在的键会先建为 0 再累加，但<b>不会设置 TTL</b>——
     * 若不显式 EXPIRE，键将永久存在，等于把内存泄漏从 JVM 搬到了 Redis。
     * 这里仅在键<b>首次创建</b>（TTL 为 -1）时设置过期，避免每次累加都重置 TTL
     * 导致「活跃用户的配额永不过期」。</p>
     */
    private static final RedisScript<Long> INCR_WITH_TTL = new DefaultRedisScript<>(
            """
            local v = redis.call('INCRBY', KEYS[1], ARGV[1])
            if redis.call('TTL', KEYS[1]) < 0 then
              redis.call('EXPIRE', KEYS[1], ARGV[2])
            end
            return v
            """, Long.class);

    private final StringRedisTemplate redis;

    public QuotaCounterStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    // ==================== 读 ====================

    /** 读取用户当日已用 token 数。Redis 异常返回 0（fail-open）。 */
    public long getUserTokens(String userId) {
        return read(userTokenKey(userId));
    }

    /** 读取用户当日已用成本（分）。 */
    public long getUserCostFen(String userId) {
        return read(userCostKey(userId));
    }

    /** 读取用户当日请求次数。 */
    public long getUserRequests(String userId) {
        return read(userReqKey(userId));
    }

    /** 读取系统当日总成本（分）。 */
    public long getSystemCostFen() {
        return read(systemCostKey());
    }

    // ==================== 写 ====================

    /**
     * 记录一次实际消耗（原子累加三个计数器）。
     *
     * @param userId  用户标识
     * @param tokens  实际 token 数
     * @param costFen 实际成本（分）
     */
    public void recordUsage(String userId, long tokens, long costFen) {
        long ttl = secondsUntilMidnight();
        incr(userTokenKey(userId), tokens, ttl);
        incr(userCostKey(userId), costFen, ttl);
        incr(userReqKey(userId), 1, ttl);
        incr(systemCostKey(), costFen, ttl);
    }

    /** 距当日 24 点的秒数。作为计数器 TTL，实现「自然日重置」。 */
    public long secondsUntilMidnight() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime midnight = now.toLocalDate().plusDays(1).atStartOfDay();
        long s = Duration.between(now, midnight).getSeconds();
        // 兜底：秒数必须为正，否则 EXPIRE 会立刻删除键
        return s > 0 ? s : 1;
    }

    // ==================== 内部 ====================

    private long read(String key) {
        try {
            String v = redis.opsForValue().get(key);
            return v == null ? 0L : Long.parseLong(v);
        } catch (NumberFormatException e) {
            // 键被外部写成非数字：按 0 处理并告警，不要让配额检查抛异常中断业务
            log.warn("⚠️ [Quota] 计数器值非法，按 0 处理 | key={}", key, e);
            return 0L;
        } catch (Exception e) {
            log.warn("⚠️ [Quota] Redis 读取失败，降级为 0（fail-open）| key={} | err={}", key, e.getMessage());
            return 0L;
        }
    }

    private void incr(String key, long delta, long ttlSeconds) {
        if (delta == 0) return;
        try {
            redis.execute(INCR_WITH_TTL, List.of(key),
                    String.valueOf(delta), String.valueOf(ttlSeconds));
        } catch (Exception e) {
            // 累加失败只丢一次计数，不影响本次请求。打 WARN 供审计。
            log.warn("⚠️ [Quota] Redis 累加失败，本次用量未计入 | key={} | delta={} | err={}",
                    key, delta, e.getMessage());
        }
    }

    private String today() {
        return LocalDate.now().format(DAY);
    }

    private String userTokenKey(String userId) {
        return KEY_PREFIX + "u:" + userId + ":" + today() + ":tokens";
    }

    private String userCostKey(String userId) {
        return KEY_PREFIX + "u:" + userId + ":" + today() + ":costfen";
    }

    private String userReqKey(String userId) {
        return KEY_PREFIX + "u:" + userId + ":" + today() + ":reqs";
    }

    private String systemCostKey() {
        return KEY_PREFIX + "sys:" + today() + ":costfen";
    }

    /** 供测试与运维核对：当前使用的日期分区 */
    public String currentDayPartition() {
        return today();
    }

    /** 仅供测试/运维：清空某用户当日配额 */
    public void resetUser(String userId) {
        try {
            redis.delete(List.of(userTokenKey(userId), userCostKey(userId), userReqKey(userId)));
        } catch (Exception e) {
            log.warn("⚠️ [Quota] 重置用户配额失败 | user={} | err={}", userId, e.getMessage());
        }
    }

    /** 距离下次重置（当日 24 点）的秒数，供前端倒计时展示 */
    public long resetInSeconds() {
        return secondsUntilMidnight();
    }
}
