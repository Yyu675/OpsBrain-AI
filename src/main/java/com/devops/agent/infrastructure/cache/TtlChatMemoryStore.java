package com.devops.agent.infrastructure.cache;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import dev.langchain4j.store.memory.chat.InMemoryChatMemoryStore;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 带 TTL 与容量上限的 ChatMemoryStore 装饰器（A1 修复）。
 *
 * <h3>为什么必须有这个类</h3>
 * LangChain4j 的 {@link InMemoryChatMemoryStore} 内部是一个
 * {@code ConcurrentHashMap<Object, List<ChatMessage>>}，<b>只增不减</b>——
 * 它自身没有任何 TTL 或容量上限，只有显式调用 {@code deleteMessages(memoryId)}
 * 才会移除条目。而本项目此前<b>没有任何一处调用它</b>：
 * <ul>
 *   <li>{@code AgentStateManager} 的 30 分钟空闲驱逐只清自己的 {@code sessionStates}</li>
 *   <li>{@code HotMemoryStore} 的 TTL 只作用于 Redis</li>
 *   <li>{@code AgentMemoryManager.evict()} 也只清 Redis 热记忆</li>
 * </ul>
 * 更糟的是 {@code DevOpsChatController.doStream()} 在 sessionId 为空时会
 * <b>退化为 {@code sessionId = traceId}</b>，而 traceId 是每请求一个的 UUID——
 * 意味着<b>每一次匿名单轮对话都会在 map 里永久插入一个新条目</b>，
 * 每条目最多 {@code maxMessages}(20) 条消息。按日均 1000 次对话、
 * 单条消息 500 字符估算，每天泄漏约 20MB 且永不释放，数周内必然 OOM。
 *
 * <h3>为什么不在 AgentStateManager 里顺带清理</h3>
 * {@code AgentStateManager.sessionStates} 以 <b>traceId</b> 为键，而对话记忆以
 * <b>sessionId</b> 为键，二者是<b>多对一</b>关系（同一会话的多轮请求各有 traceId）。
 * 在某个 traceId 过期时删除该 sessionId 的记忆，会<b>误删仍在活跃的多轮会话</b>，
 * 表现为用户对话到一半突然失忆。因此驱逐必须按 memoryId 自身的访问时间独立计算。
 *
 * <h3>策略</h3>
 * <ul>
 *   <li><b>TTL</b>：{@code expireAfterAccessMinutes} 内无任何读写则回收。
 *       读({@code getMessages})与写({@code updateMessages})都算访问并续期，
 *       活跃会话不会被误删。</li>
 *   <li><b>容量兜底</b>：条目数超过 {@code maxEntries} 时，按最后访问时间升序
 *       淘汰最旧的一批。防御 TTL 内瞬时涌入大量会话（如压测/爬虫）导致的内存尖峰。</li>
 *   <li><b>清扫线程</b>：守护线程，固定间隔扫描，不阻塞 JVM 退出。</li>
 * </ul>
 *
 * <p>本类为线程安全实现。委托目标 {@link InMemoryChatMemoryStore} 自身线程安全，
 * {@code lastAccess} 用 {@link ConcurrentHashMap} 维护。清扫与业务读写并发时，
 * 极端情况下可能删掉一个"刚好在清扫瞬间被访问"的会话；代价仅是该会话丢失窗口内
 * 上下文（降级为单轮），不会抛异常或串会话，可接受。</p>
 *
 * <p>分布式部署时应替换为 Redis backed store；本类解决的是<b>单实例也必然发生</b>
 * 的生命周期缺失问题，与是否分布式无关。</p>
 *
 * @author OpsBrain AI
 * @since 2026-08-24
 */
@Slf4j
public class TtlChatMemoryStore implements ChatMemoryStore {

    /** 实际存储（委托） */
    private final ChatMemoryStore delegate = new InMemoryChatMemoryStore();

    /** memoryId -> 最后访问时间戳（毫秒） */
    private final Map<Object, Long> lastAccess = new ConcurrentHashMap<>();

    /** 空闲多久后回收（毫秒） */
    private final long expireAfterAccessMs;

    /** 条目数上限，超出按最旧访问时间淘汰 */
    private final int maxEntries;

    /** 清扫间隔（毫秒） */
    private final long sweepIntervalMs;

    /** 累计驱逐条目数（供健康检查/指标暴露） */
    private final AtomicLong evictedCount = new AtomicLong();

    private final ScheduledExecutorService sweeper =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "chat-memory-sweeper");
                t.setDaemon(true);
                return t;
            });

    public TtlChatMemoryStore(long expireAfterAccessMinutes, int maxEntries, long sweepIntervalMinutes) {
        this.expireAfterAccessMs = TimeUnit.MINUTES.toMillis(expireAfterAccessMinutes);
        this.maxEntries = maxEntries;
        this.sweepIntervalMs = TimeUnit.MINUTES.toMillis(sweepIntervalMinutes);
    }

    @PostConstruct
    public void init() {
        sweeper.scheduleWithFixedDelay(this::sweep, sweepIntervalMs, sweepIntervalMs, TimeUnit.MILLISECONDS);
        log.info("🧹 [ChatMemoryStore] TTL 清扫已启动 | expireAfterAccess={}ms | maxEntries={} | interval={}ms",
                expireAfterAccessMs, maxEntries, sweepIntervalMs);
    }

    @PreDestroy
    public void destroy() {
        sweeper.shutdownNow();
        log.info("🧹 [ChatMemoryStore] 清扫线程已关闭 | 累计驱逐={} 条", evictedCount.get());
    }

    // ==================== ChatMemoryStore 实现 ====================

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        touch(memoryId);
        return delegate.getMessages(memoryId);
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        touch(memoryId);
        delegate.updateMessages(memoryId, messages);
    }

    @Override
    public void deleteMessages(Object memoryId) {
        lastAccess.remove(memoryId);
        delegate.deleteMessages(memoryId);
    }

    // ==================== 内部 ====================

    private void touch(Object memoryId) {
        if (memoryId == null) return;
        lastAccess.put(memoryId, System.currentTimeMillis());
    }

    /**
     * 清扫：先按 TTL 回收，再按容量上限兜底淘汰最旧条目。
     * <p>异常必须吞掉——{@code scheduleWithFixedDelay} 的任务一旦抛异常
     * 会<b>静默终止后续所有调度</b>，那样泄漏会重新出现且没有任何日志。</p>
     */
    private void sweep() {
        try {
            long now = System.currentTimeMillis();
            int before = lastAccess.size();

            // 1) TTL 回收
            lastAccess.entrySet().removeIf(e -> {
                // 用 >= 而非 >：TTL 配成 0（"立即过期"，测试与某些压测场景会这么配）时，
                // 若清扫恰好发生在写入的同一毫秒，now - lastAccess == 0，
                // 用 > 会判定为未过期而永远回收不掉——TTL=0 反而变成了永不过期。
                // >= 让边界含义明确：已达到存活时长即可回收。
                if (now - e.getValue() >= expireAfterAccessMs) {
                    delegate.deleteMessages(e.getKey());
                    evictedCount.incrementAndGet();
                    return true;
                }
                return false;
            });

            // 2) 容量兜底：仍超限则按最后访问时间升序淘汰
            int overflow = lastAccess.size() - maxEntries;
            if (overflow > 0) {
                lastAccess.entrySet().stream()
                        .sorted(Map.Entry.comparingByValue())
                        .limit(overflow)
                        .map(Map.Entry::getKey)
                        .toList()   // 先物化，避免在流迭代中改动 map
                        .forEach(id -> {
                            lastAccess.remove(id);
                            delegate.deleteMessages(id);
                            evictedCount.incrementAndGet();
                        });
                log.warn("⚠️ [ChatMemoryStore] 条目数超上限，按 LRU 淘汰 | overflow={} | maxEntries={}",
                        overflow, maxEntries);
            }

            int removed = before - lastAccess.size();
            if (removed > 0) {
                log.info("🧹 [ChatMemoryStore] 清扫完成 | removed={} | remaining={} | 累计驱逐={}",
                        removed, lastAccess.size(), evictedCount.get());
            }
        } catch (Exception e) {
            log.error("❌ [ChatMemoryStore] 清扫异常（已吞掉以保证调度不中断）", e);
        }
    }

    /** 当前活跃会话窗口数（供健康检查/指标） */
    public int size() {
        return lastAccess.size();
    }

    /** 累计驱逐条目数（供健康检查/指标） */
    public long evictedCount() {
        return evictedCount.get();
    }
}
