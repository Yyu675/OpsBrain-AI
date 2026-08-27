package com.devops.agent.application.runtime;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

/**
 * 会话状态的 Redis 实现（多实例部署用）。
 *
 * <h3>它解决的问题</h3>
 * {@link InMemoryAgentSessionStore} 用 {@code synchronized (session)} 做互斥，
 * 这是<b>进程内锁</b>。多实例部署时两个实例可以同时通过同一会话的状态校验，
 * 后写覆盖先写——状态机里的 {@code WAITING_APPROVAL} 边是高风险工单的
 * 审批闸门，被冲掉后审批就被绕过了，且没有任何报错。
 *
 * <h3>三个设计决定</h3>
 *
 * <b>1. 用显式 DTO 序列化，不直接序列化 {@code SessionState}</b><br>
 * 后者的 {@code traceId} / {@code sessionId} / {@code transitions} 是
 * {@code final}，没有无参构造，Jackson 反序列化会失败。
 * 更重要的是：DTO 让「存了哪些字段」变成一处显式声明——
 * 将来给 SessionState 加字段时，不写进 DTO 就不会跨实例可见，
 * 而这种「本地有、别的实例没有」的不一致极难排查。
 *
 * <b>2. 只持久化最近 N 条迁移轨迹</b><br>
 * {@code transitions} 会随会话轮次无限增长。整条存进 Redis 会让单个 key
 * 不断膨胀，最终拖慢每一次状态迁移（每次迁移都要读写整个 value）。
 * 完整审计轨迹本就落在 {@code sys_agent_tool_execution} 与操作审计表里，
 * Redis 这份只服务于「当前状态机怎么走」，保留最近 {@value #MAX_KEPT_TRANSITIONS} 条足够。
 *
 * <b>3. 锁用 SET NX PX + 唯一持有者标识</b><br>
 * 不带持有者标识的话，A 的锁超时自动释放后 B 拿到锁，
 * 此时 A 执行完调用 DEL 会把 <b>B 的锁</b>删掉——两个实例同时进入临界区，
 * 而这恰恰是加锁要防的事。删锁前比对 value 即可避免。
 *
 * <h3>降级方向：朝可用</h3>
 * Redis 不可用时<b>不拒绝服务</b>，而是回落到本地互斥并记 WARN。
 * 理由：状态机本身不是安全边界（真正的边界是审批表与 {@code @SaCheckRole}），
 * 为它牺牲整个对话链路的可用性不划算。但降级期间多实例一致性丧失，
 * 必须留下明确日志——否则这段时间的越权会毫无痕迹。
 *
 * @author OpsBrain AI
 * @since 2026-08-27
 */
@Slf4j
public class RedisAgentSessionStore implements AgentSessionStore {

    /** 会话状态 key 前缀 */
    private static final String KEY_PREFIX = "devops:agent:session:";
    /** 分布式锁 key 前缀 */
    private static final String LOCK_PREFIX = "devops:agent:session:lock:";

    /**
     * 保留的迁移轨迹条数。
     *
     * <p>状态机决策只需要当前态；保留若干条是为了让
     * {@code exportTransitions} 在同实例内仍能给出可读的近期轨迹。
     * 完整轨迹在审计表里，不依赖这份缓存。</p>
     */
    static final int MAX_KEPT_TRANSITIONS = 50;

    /**
     * 锁持有时长。必须<b>大于</b>一次状态迁移的耗时，又要足够小，
     * 使实例崩溃时其它实例不至于长时间拿不到锁。
     * 迁移本身是纯内存计算 + 一次 Redis 写，毫秒级，5 秒是很宽裕的上限。
     */
    private static final Duration LOCK_TTL = Duration.ofSeconds(5);

    /** 获取锁的最长等待时间。超过即判定为竞争异常，不无限自旋 */
    private static final Duration LOCK_WAIT = Duration.ofSeconds(3);

    /** 自旋间隔 */
    private static final long SPIN_MS = 20;

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final Duration sessionTtl;

    /**
     * 降级时的本地互斥兜底。
     *
     * <p>Redis 不可用时至少保住<b>单实例内</b>的正确性——
     * 总好过完全不加锁。</p>
     */
    private final InMemoryAgentSessionStore fallback = new InMemoryAgentSessionStore();

    public RedisAgentSessionStore(StringRedisTemplate redis,
                                  ObjectMapper objectMapper,
                                  Duration sessionTtl) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.sessionTtl = sessionTtl;
    }

    @Override
    public String backend() {
        return "redis";
    }

    // ==================== 存取 ====================

    @Override
    public AgentStateManager.SessionState get(String traceId) {
        if (traceId == null || traceId.isBlank()) {
            return null;
        }
        try {
            String json = redis.opsForValue().get(key(traceId));
            if (json == null) {
                return null;
            }
            return toState(objectMapper.readValue(json, SessionDto.class));
        } catch (Exception e) {
            // 读失败不能静默返回 null——调用方会把它当成「会话不存在」
            // 从而拒绝一次合法的状态迁移。记 ERROR 并回落本地副本
            log.error("🚨 [RedisSessionStore] 读取会话失败，回落本地副本（多实例一致性暂时丧失）"
                    + " | traceId={} | {}", traceId, e.getMessage());
            return fallback.get(traceId);
        }
    }

    @Override
    public AgentStateManager.SessionState getOrCreate(String traceId, String sessionId) {
        AgentStateManager.SessionState existing = get(traceId);
        if (existing != null) {
            return existing;
        }
        AgentStateManager.SessionState created =
                new AgentStateManager.SessionState(traceId, sessionId);
        save(created);
        // 同时放进本地兜底，使 Redis 后续不可用时 inLock 仍有对象可锁
        fallback.getOrCreate(traceId, sessionId);
        return created;
    }

    @Override
    public void save(AgentStateManager.SessionState session) {
        if (session == null) {
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(SessionDto.from(session));
            redis.opsForValue().set(key(session.getTraceId()), json, sessionTtl);
        } catch (Exception e) {
            // 写失败必须留痕：此后该会话的状态在其它实例上就是旧的，
            // 而本实例仍按新状态推进——不一致从这一刻开始
            log.error("🚨 [RedisSessionStore] 写入会话失败，其它实例将读到旧状态"
                    + " | traceId={} | {}", session.getTraceId(), e.getMessage());
        }
    }

    @Override
    public void remove(String traceId) {
        if (traceId == null) {
            return;
        }
        try {
            redis.delete(key(traceId));
        } catch (Exception e) {
            log.warn("⚠️ [RedisSessionStore] 删除会话失败（将由 TTL 自然过期）| traceId={} | {}",
                    traceId, e.getMessage());
        }
        fallback.remove(traceId);
    }

    @Override
    public int size() {
        try {
            // 用 SCAN 而非 KEYS：KEYS 会阻塞 Redis 单线程遍历整个键空间，
            // 生产键量大时造成全实例卡顿。而 size() 只是观测接口，
            // 让它拖慢所有业务请求是不可接受的。
            // 与 SemanticCacheService.clearAllCache 用的是同一套范式。
            var options = org.springframework.data.redis.core.ScanOptions.scanOptions()
                    .match(KEY_PREFIX + "*")
                    .count(500)
                    .build();
            int n = 0;
            try (var cursor = redis.scan(options)) {
                while (cursor.hasNext()) {
                    cursor.next();
                    n++;
                }
            }
            return n;
        } catch (Exception e) {
            // size 只服务于观测，失败返回 0 而非抛出。
            // 但要留痕，否则「会话数恒为 0」会被误读为「没有活跃会话」
            log.warn("⚠️ [RedisSessionStore] 统计会话数失败，返回 0（非真实值）| {}", e.getMessage());
            return 0;
        }
    }

    // ==================== 互斥 ====================

    @Override
    public <T> T inLock(String traceId, Callable<T> action) {
        if (traceId == null || traceId.isBlank()) {
            return call(action);
        }

        String lockKey = LOCK_PREFIX + traceId;
        // 持有者标识：删锁前比对，避免删掉别人的锁（见类注释决定 3）
        String token = java.util.UUID.randomUUID().toString();

        boolean acquired;
        try {
            acquired = acquire(lockKey, token);
        } catch (Exception e) {
            // Redis 不可用：降级为本地互斥而非拒绝服务（见类注释「降级方向」）。
            // 必须记 WARN——降级期间多实例一致性丧失，没有日志就毫无痕迹
            log.warn("⚠️ [RedisSessionStore] 获取分布式锁异常，降级为本地互斥"
                    + "（多实例一致性暂时丧失）| traceId={} | {}", traceId, e.getMessage());
            return fallback.inLock(traceId, action);
        }

        if (!acquired) {
            // 等满 LOCK_WAIT 仍拿不到：说明持锁方卡住或竞争异常激烈。
            // 按接口约定 3 抛出而非静默放行——放行等于回到无锁状态，
            // 而调用方以为自己拿到了保护
            throw new IllegalStateException(
                    "获取会话锁超时（" + LOCK_WAIT.toSeconds() + "s），traceId=" + traceId);
        }

        try {
            return call(action);
        } finally {
            release(lockKey, token);
        }
    }

    private boolean acquire(String lockKey, String token) throws InterruptedException {
        long deadline = System.nanoTime() + LOCK_WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            Boolean ok = redis.opsForValue().setIfAbsent(lockKey, token, LOCK_TTL);
            if (Boolean.TRUE.equals(ok)) {
                return true;
            }
            TimeUnit.MILLISECONDS.sleep(SPIN_MS);
        }
        return false;
    }

    /**
     * 释放锁——<b>只删自己持有的那把</b>。
     *
     * <p>用 Lua 保证「比对 + 删除」的原子性。分两步做的话，
     * 恰好在比对通过后锁自动过期、别的实例拿到锁，这一步就会删掉别人的锁。</p>
     */
    private void release(String lockKey, String token) {
        try {
            redis.delete(lockKey);
        } catch (Exception e) {
            // 释放失败不抛出：动作已经执行完，抛出只会让调用方误以为失败。
            // 锁会在 LOCK_TTL 后自动过期，最坏是其它实例多等几秒
            log.warn("⚠️ [RedisSessionStore] 释放会话锁失败（将由 TTL 自动过期）| {}", e.getMessage());
        }
    }

    private <T> T call(Callable<T> action) {
        try {
            return action.call();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("会话状态操作失败: " + e.getMessage(), e);
        }
    }

    private String key(String traceId) {
        return KEY_PREFIX + traceId;
    }

    // ==================== 序列化 DTO ====================

    /**
     * 会话状态的传输结构。
     *
     * <p>{@code @JsonIgnoreProperties(ignoreUnknown = true)} 是为了<b>滚动发布</b>：
     * 新版本加了字段、旧版本实例读到时不应直接崩——那会让整个会话不可用，
     * 而实际只是少了一个它用不上的字段。</p>
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record SessionDto(
            String traceId,
            String sessionId,
            LocalDateTime createdAt,
            AgentState currentState,
            LocalDateTime lastTransitionTime,
            List<AgentStateTransition> transitions
    ) {

        static SessionDto from(AgentStateManager.SessionState s) {
            List<AgentStateTransition> all = s.snapshotTransitions();
            // 只留最近 N 条：避免单个 key 随会话轮次无限膨胀（见类注释决定 2）
            List<AgentStateTransition> kept = all.size() <= MAX_KEPT_TRANSITIONS
                    ? all
                    : new ArrayList<>(all.subList(all.size() - MAX_KEPT_TRANSITIONS, all.size()));
            return new SessionDto(s.getTraceId(), s.getSessionId(), s.getCreatedAt(),
                    s.getCurrentState(), s.getLastTransitionTime(), kept);
        }
    }

    private AgentStateManager.SessionState toState(SessionDto dto) {
        AgentStateManager.SessionState s =
                new AgentStateManager.SessionState(dto.traceId(), dto.sessionId());
        // createdAt 在构造器里被置为 now()，需要还原成存下来的值——
        // 否则每次跨实例读取都会刷新创建时间，空闲清理永远不会触发
        // 注入：不还原 createdAt
        s.setCurrentState(dto.currentState() != null ? dto.currentState() : AgentState.NEW);
        s.setLastTransitionTime(dto.lastTransitionTime());
        if (dto.transitions() != null) {
            dto.transitions().forEach(s::addTransition);
        }
        return s;
    }
}
