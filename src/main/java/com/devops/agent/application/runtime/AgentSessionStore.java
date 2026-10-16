package com.devops.agent.application.runtime;

import java.util.concurrent.Callable;

/**
 * Agent 会话状态存储（可插拔，2026-08-27）。
 *
 * <h3>为什么要有这层接口</h3>
 * {@link AgentStateManager} 原先把会话状态放在
 * {@code ConcurrentHashMap}，并用 {@code synchronized (session)} 保证
 * 「校验当前态 → 写入新态」的原子性。这在单实例下是对的，
 * <b>但 synchronized 是进程内锁，多实例部署时完全失效</b>。
 *
 * <p>后果不只是「状态丢失」这么轻。状态机里有一条
 * {@code WAITING_APPROVAL} 边，是高风险工单的审批闸门。两个实例
 * 同时对同一 traceId 迁移时，各自读到相同的 fromState、各自校验通过，
 * 后写的覆盖先写的——<b>「需人工审批」可能被冲成「草稿就绪」，
 * 审批闸门被绕过</b>，而这一切没有任何报错。</p>
 *
 * <p>同一套治理体系里，工具幂等已经走 Redis（{@code SET NX EX}，跨实例生效），
 * 唯独会话状态还是单机的。本接口把「状态存哪、怎么互斥」与
 * 「状态机有哪些合法边」分开，让部署形态决定选哪个实现。</p>
 *
 * <h3>接口边界怎么划的</h3>
 * <ul>
 *   <li><b>只暴露存取与互斥</b>——{@code get} / {@code getOrCreate} /
 *       {@code save} / {@code inLock}。状态迁移是否合法、耗时怎么算、
 *       审计怎么记，全都留在 {@link AgentStateManager} 里；
 *       这些是<b>业务规则</b>，不该随存储实现变化；</li>
 *   <li><b>互斥用 {@link #inLock} 而非暴露 lock/unlock</b>——
 *       后者把「必须成对调用」的责任推给调用方，一次早返回就漏解锁。
 *       用回调式 API，释放由实现保证；</li>
 *   <li><b>不暴露 Redis key、TTL、Lua 脚本</b>这类实现细节，
 *       否则换实现时上层照样得改。</li>
 * </ul>
 *
 * <h3>实现约定</h3>
 * <ol>
 *   <li><b>{@link #inLock} 必须是可重入或至少不自死锁</b>——
 *       状态迁移过程中可能间接触发另一次迁移；</li>
 *   <li><b>锁粒度是单个会话</b>，不是全局。不同 traceId 之间本就互不影响，
 *       锁全局会让所有并发会话的迁移排队；</li>
 *   <li><b>获取锁失败时抛异常而非静默放行</b>。放行等于回到无锁状态，
 *       而调用方以为自己拿到了保护——比明确失败更危险。</li>
 * </ol>
 *
 * @author OpsBrain AI
 * @since 2026-08-27
 */
public interface AgentSessionStore {

    /**
     * 存储后端标识，如 memory / redis。
     *
     * <p>用途：启动日志里标明「当前用的是哪套存储」。
     * 多实例部署却用着 memory 实现是一类典型的配置事故，
     * 而它在单实例测试时完全看不出来——日志里写明是最低成本的提示。</p>
     */
    String backend();

    /** 取会话，不存在返回 null */
    AgentStateManager.SessionState get(String traceId);

    /** 取会话，不存在则以 {@code sessionId} 创建 */
    AgentStateManager.SessionState getOrCreate(String traceId, String sessionId);

    /**
     * 回写会话。
     *
     * <p>内存实现里对象是共享引用，本方法可以是空操作；
     * 但 Redis 这类<b>值语义</b>的实现必须显式序列化回去——
     * 少了这一步，改动只存在于本地副本，其它实例读到的还是旧状态。
     * 接口保留这个方法正是为了让该差异显式化。</p>
     */
    void save(AgentStateManager.SessionState session);

    /**
     * 在会话级互斥下执行动作。
     *
     * @param traceId 会话标识，锁粒度
     * @param action  受保护的动作，返回值原样透出
     * @return {@code action} 的返回值
     * @throws IllegalStateException 获取锁失败（约定 3：不得静默放行）
     */
    <T> T inLock(String traceId, Callable<T> action);

    /** 移除会话（清理空闲会话时用） */
    void remove(String traceId);

    /** 当前会话数，供观测 */
    int size();
}
