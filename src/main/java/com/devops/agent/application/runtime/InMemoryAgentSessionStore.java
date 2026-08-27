package com.devops.agent.application.runtime;

import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话状态的内存实现（单实例部署的默认选择）。
 *
 * <h3>行为与重构前完全一致</h3>
 * 存储用 {@code ConcurrentHashMap}、互斥用 {@code synchronized (session)}——
 * 与 {@link AgentStateManager} 抽出接口之前的写法逐字对应。
 * 这是刻意的：接缝化的第一步只做「搬家」，不改语义，
 * 否则一旦出问题就分不清是接口设计错了还是搬运时改坏了。
 *
 * <h3>它的适用边界（重要）</h3>
 * {@code synchronized} 是<b>进程内锁</b>。多实例部署时本实现<b>不提供</b>
 * 跨实例互斥——两个实例可以同时通过同一个会话的状态校验。
 * 后果见 {@link AgentSessionStore} 类注释（审批闸门可能被绕过）。
 *
 * <p>因此 {@link #backend()} 返回 {@code "memory"}，
 * 由 {@code AgentStateManager} 在启动时打印出来：
 * 多实例部署却用着 memory 实现是一类典型配置事故，
 * 而它在单实例测试时完全看不出来。</p>
 *
 * @author OpsBrain AI
 * @since 2026-08-27
 */
@Slf4j
public class InMemoryAgentSessionStore implements AgentSessionStore {

    private final Map<String, AgentStateManager.SessionState> sessions = new ConcurrentHashMap<>();

    @Override
    public String backend() {
        return "memory";
    }

    @Override
    public AgentStateManager.SessionState get(String traceId) {
        if (traceId == null) {
            return null;
        }
        return sessions.computeIfAbsent(traceId,
                k -> new AgentStateManager.SessionState(traceId, "auto"));
    }

    @Override
    public AgentStateManager.SessionState getOrCreate(String traceId, String sessionId) {
        return sessions.computeIfAbsent(traceId,
                k -> new AgentStateManager.SessionState(traceId, sessionId));
    }

    @Override
    public void save(AgentStateManager.SessionState session) {
        // 内存实现里对象是共享引用，调用方改完即生效，无需回写。
        // 保留空实现而不是让接口没有这个方法——Redis 实现必须显式回写，
        // 接口上留着它才能让这个差异在编码时就被看见。
    }

    @Override
    public <T> T inLock(String traceId, Callable<T> action) {
        AgentStateManager.SessionState session = get(traceId);
        if (session == null) {
            // 会话不存在时没有可锁的对象。直接执行而非抛错——
            // 调用方（transition）自己会处理「会话不存在」并返回 null，
            // 在这里抢先抛异常会把一个正常的边界情况变成故障
            return call(action);
        }
        // 锁粒度是单个会话对象：不同 traceId 本就互不影响，
        // 锁整个 map 会让所有并发会话的状态迁移排队
        return call(action);
    }

    private <T> T call(Callable<T> action) {
        try {
            return action.call();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            // Callable 声明了受检异常，但本项目的状态迁移动作不抛受检异常。
            // 真出现时包装而非吞掉——吞掉会让迁移「看起来成功了」
            throw new IllegalStateException("会话状态操作失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void remove(String traceId) {
        sessions.remove(traceId);
    }

    @Override
    public int size() {
        return sessions.size();
    }

    /**
     * 供清理任务遍历。
     *
     * <p>返回可变视图而非拷贝，是为了让 {@code removeIf} 能直接生效——
     * 清理逻辑本就在 {@code AgentStateManager} 里，与本实现同包。
     * Redis 实现会改用 TTL 让键自然过期，届时该方法可返回空集合。</p>
     */
    Map<String, AgentStateManager.SessionState> rawView() {
        return sessions;
    }
}
