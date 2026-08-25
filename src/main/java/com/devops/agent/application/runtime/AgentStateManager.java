package com.devops.agent.application.runtime;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Agent 状态管理器
 * <p>
 * 职责：
 * <ol>
 *   <li>维护会话级状态机，强制合法迁移</li>
 *   <li>记录每次状态迁移，生成审计轨迹</li>
 *   <li>提供状态查询、回放数据导出</li>
 *   <li>集成 TraceContext，自动关联 traceId</li>
 * </ol>
 * <p>
 * 设计原则（Agent Methodology §10）：
 * - 显式状态机：所有流程节点显式建模，禁止隐式 Prompt 串联
 * - 单写原则：状态仅由状态管理器写入，AgentEngine 只返回建议
 * - 可审计：每次迁移留痕，支持 Replay 回放
 * - 可恢复：状态持久化，支持断点续跑
 * </p>
 *
 * @author OpsBrain AI
 * @since 2026-08-08
 */
@Slf4j
// 注意：Bean 由 AgentEngineConfig.agentStateManager() 以 @Bean 方式创建，
// 此处不加 @Component 以避免双重定义（P2-24）。
public class AgentStateManager {

    /**
     * 会话状态存储：traceId -> SessionState
     * 生产环境应持久化到 Redis/PostgreSQL，此处用内存演示
     */
    private final Map<String, SessionState> sessionStates = new ConcurrentHashMap<>();

    /**
     * 会话空闲超时（毫秒），超过此时间未迁移的会话自动清理
     */
    private static final long SESSION_IDLE_TIMEOUT_MS = 30 * 60 * 1000L; // 30 分钟

    /**
     * 清理周期（毫秒）
     */
    private static final long CLEANUP_INTERVAL_MS = 5 * 60 * 1000L; // 5 分钟

    /**
     * 后台清理线程池（守护线程，不阻塞 JVM 退出）
     */
    private final ScheduledExecutorService cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "state-manager-cleanup");
        t.setDaemon(true);
        return t;
    });

    /**
     * 初始化后台清理任务
     */
    @PostConstruct
    public void init() {
        cleanupExecutor.scheduleWithFixedDelay(
                this::evictIdleSessions,
                CLEANUP_INTERVAL_MS,
                CLEANUP_INTERVAL_MS,
                TimeUnit.MILLISECONDS
        );
        log.info("🧹 [StateManager] 会话空闲清理已启动 | idleTimeout={}ms | interval={}ms",
                SESSION_IDLE_TIMEOUT_MS, CLEANUP_INTERVAL_MS);
    }

    /**
     * 关闭清理线程池
     */
    @PreDestroy
    public void destroy() {
        cleanupExecutor.shutdownNow();
        log.info("🧹 [StateManager] 会话清理线程池已关闭");
    }

    /**
     * 清理空闲会话
     * <p>
     * <b>空闲基准取「最后一次迁移时间」，没迁移过则回落到「创建时间」。</b>
     * 早先的写法是 {@code if (lastTransitionTime == null) return false;}——
     * 直接放过从未迁移的会话，而 {@link #getOrCreateSession} 建出来的新会话
     * {@code lastTransitionTime} 恰恰就是 null。
     * </p>
     * <p>
     * 用户可见后果：任何在首次状态迁移之前就中断的会话
     * （请求刚进来就被安全门卫拒绝、预算超限、客户端断连、
     * 或后续迁移全被判非法而未落地）都会在这张 Map 里<b>永久驻留</b>，
     * 清理线程每 5 分钟跑一次却一个都清不掉。这是一条随请求量线性增长、
     * 永不回收的内存泄漏路径，最终表现为服务运行数日后堆占用只涨不跌、
     * Full GC 频繁、响应变慢，直至 OOM——而日志里
     * 「清理空闲会话 removed=0」看上去一切正常。
     * </p>
     */
    private void evictIdleSessions() {
        LocalDateTime now = LocalDateTime.now();
        int before = sessionStates.size();
        sessionStates.values().removeIf(s -> {
            LocalDateTime since = s.getIdleSince();
            if (since == null) return false;
            return java.time.Duration.between(since, now).toMillis() > SESSION_IDLE_TIMEOUT_MS;
        });
        int removed = before - sessionStates.size();
        if (removed > 0) {
            log.info("🧹 [StateManager] 清理空闲会话 | removed={} | remaining={}", removed, sessionStates.size());
        }
    }

    /**
     * 获取或创建会话状态
     */
    public SessionState getOrCreateSession(String traceId, String sessionId) {
        return sessionStates.computeIfAbsent(traceId, k -> new SessionState(traceId, sessionId));
    }

    /**
     * 获取会话状态（不创建）
     */
    public SessionState getSession(String traceId) {
        return sessionStates.get(traceId);
    }

    /**
     * 查询会话当前状态，会话不存在返回 null
     * <p>
     * 刻意<b>不</b>在会话缺失时回落成 {@link AgentState#NEW}：
     * 「这个会话处于新建态」和「根本没有这个会话」是两件事，
     * 后者往往意味着会话已被空闲清理或 traceId 传错，
     * 拿 NEW 冒充会让调用方以为流程刚开始而继续往下推。
     * </p>
     */
    public AgentState getCurrentState(String traceId) {
        SessionState session = sessionStates.get(traceId);
        return session == null ? null : session.getCurrentState();
    }

    /**
     * 导出会话的完整迁移轨迹，供审计与 Replay 回放使用
     * <p>
     * 类注释把「提供状态查询、回放数据导出」列为职责，但此前<b>没有任何导出方法</b>：
     * 迁移记录全都锁在 {@code private static class SessionState} 里，
     * 而 {@link #getSession} 的返回类型当时也是私有的，包外根本无法声明变量接住。
     * 结果是每次迁移辛苦攒下的审计轨迹只进不出，等会话被清理就彻底消失，
     * 出问题时运维<b>拿不到任何可回放的执行链路</b>，只能翻散落的日志文本。
     * </p>
     *
     * @return 不可变的迁移记录副本（按发生顺序）；会话不存在时返回空列表
     */
    public List<AgentStateTransition> exportTransitions(String traceId) {
        SessionState session = sessionStates.get(traceId);
        if (session == null) {
            return List.of();
        }
        return session.snapshotTransitions();
    }

    /**
     * 当前驻留的会话数
     * <p>用于监控内存占用与验证空闲清理确实生效。</p>
     */
    public int sessionCount() {
        return sessionStates.size();
    }

    /**
     * 尝试迁移状态
     * <p>
     * 核心方法：校验合法性、记录迁移、更新当前状态
     * </p>
     *
     * @param traceId      追踪 ID
     * @param toState      目标状态
     * @param trigger      触发器类型
     * @param detail       触发详情
     * @param operator     操作人
     * @param metadata     附加元数据（可选）
     * @return 迁移记录（成功）或 null（非法迁移）
     */
    public AgentStateTransition transition(
            String traceId,
            AgentState toState,
            AgentStateTransition.TriggerType trigger,
            String detail,
            String operator,
            String metadata) {

        SessionState session = sessionStates.get(traceId);
        if (session == null) {
            log.warn("⚠️ [StateManager] 会话不存在，无法迁移 | traceId={} | targetState={}", traceId, toState);
            return null;
        }

        AgentState fromState;
        AgentStateTransition transition;
        long durationMs;

        // 「校验当前态 → 写入新态」必须是一个原子段。
        //
        // 这里的并发不是理论风险：状态迁移的调用方分布在至少两类线程上——
        // 处理请求的 sessionExecutor 线程（安全检查、缓存命中、预算超限等），
        // 以及模型的 SSE 回调线程（onToolExecuted / onCompleteResponse / onError）。
        // 二者同时对同一个 traceId 迁移是常态而非例外。
        //
        // 不加锁时的具体后果：
        //   1) 两个线程同时读到 fromState=TOOLS_COMPLETED，各自校验通过，
        //      随后一个写 DRAFT_READY、一个写 WAITING_APPROVAL——
        //      后写的覆盖先写的，状态机凭空跨过一条不存在的边，
        //      「需人工审批」的会话可能被冲成「草稿就绪」，直接绕过审批闸门；
        //   2) transitions 是普通 ArrayList，两个线程并发 add 会丢记录，
        //      扩容期撞车甚至让审计轨迹里出现 null 空洞；
        //   3) durationMs 基于 lastTransitionTime 计算，交错读写会算出负数或离谱大值，
        //      各阶段耗时统计随之失真。
        //
        // 锁粒度取「单个会话对象」而非整个管理器：不同 traceId 之间本就互不影响，
        // 锁在 sessionStates 上会让所有并发会话的状态迁移互相排队。
        synchronized (session) {
            fromState = session.getCurrentState();

            // 校验合法迁移
            if (!AgentState.canTransition(fromState, toState)) {
                log.warn("⚠️ [StateManager] 非法状态迁移 | traceId={} | from={} | to={} | trigger={}",
                        traceId, fromState, toState, trigger);
                return null;
            }

            // 计算耗时
            durationMs = 0;
            if (session.getLastTransitionTime() != null) {
                durationMs = java.time.Duration.between(session.getLastTransitionTime(), LocalDateTime.now()).toMillis();
            }

            // 创建迁移记录
            transition = AgentStateTransition.of(
                    traceId,
                    session.getSessionId(),
                    fromState,
                    toState,
                    trigger,
                    detail,
                    operator,
                    durationMs,
                    metadata
            );

            // 更新会话状态
            session.setCurrentState(toState);
            session.setLastTransitionTime(LocalDateTime.now());
            session.addTransition(transition);
        }

        log.info("🔄 [StateManager] 状态迁移 | traceId={} | {} → {} | trigger={} | operator={} | duration={}ms",
                traceId, fromState.name(), toState.name(), trigger, operator, durationMs);

        return transition;
    }

    /**
     * 简化版迁移（自动从 TraceContext 获取 traceId，operator=SYSTEM）
     */
    public AgentStateTransition transition(AgentState toState, AgentStateTransition.TriggerType trigger, String detail) {
        String traceId = com.devops.agent.common.context.TraceContext.getTraceId();
        if (traceId == null) {
            log.warn("⚠️ [StateManager] TraceContext 无 traceId，无法记录状态迁移");
            return null;
        }
        return transition(traceId, toState, trigger, detail, "SYSTEM", null);
    }

    // isEmpty 等公共方法由 evictIdleSessions 代替

    // ==================== 内部会话状态类 ====================

    /**
     * 单会话运行时状态
     * <p>
     * <b>改为 public：</b>{@link #getSession(String)} 是 public 方法却返回私有类型，
     * 包外调用方连接住返回值的变量都声明不出来，这个「查询接口」实际只有本包能用。
     * </p>
     * <p>
     * <b>线程安全：</b>本类的字段由 {@link AgentStateManager#transition} 在
     * {@code synchronized (session)} 块内读写。会话状态会被两类线程并发触碰——
     * 处理请求的 {@code sessionExecutor} 线程，以及模型 SSE 回调线程
     * （{@code onToolExecuted} / {@code onCompleteResponse} 里都在迁移状态）。
     * </p>
     */
    public static class SessionState {
        private final String traceId;
        private final String sessionId;
        private LocalDateTime createdAt;
        private AgentState currentState = AgentState.NEW;
        private LocalDateTime lastTransitionTime;
        private final List<AgentStateTransition> transitions = new ArrayList<>();

        public SessionState(String traceId, String sessionId) {
            this.traceId = traceId;
            this.sessionId = sessionId;
            this.createdAt = LocalDateTime.now();
        }

        // Getters & Setters
        public String getTraceId() { return traceId; }
        public String getSessionId() { return sessionId; }
        public LocalDateTime getCreatedAt() { return createdAt; }
        public AgentState getCurrentState() { return currentState; }
        public void setCurrentState(AgentState currentState) { this.currentState = currentState; }
        public LocalDateTime getLastTransitionTime() { return lastTransitionTime; }
        public void setLastTransitionTime(LocalDateTime lastTransitionTime) { this.lastTransitionTime = lastTransitionTime; }
        public List<AgentStateTransition> getTransitions() { return transitions; }
        public void addTransition(AgentStateTransition t) { transitions.add(t); }

        /**
         * 计算空闲起算点：优先最后一次迁移时间，从未迁移过则用创建时间。
         * <p>没有这条回落，「建好就再没动过」的会话会被清理逻辑当成永不空闲。</p>
         */
        public LocalDateTime getIdleSince() {
            return lastTransitionTime != null ? lastTransitionTime : createdAt;
        }

        /**
         * 迁移记录快照
         * <p>
         * 必须<b>拷贝</b>而非直接返回 {@code transitions}：内部用的是普通 ArrayList，
         * 把它交给外部遍历时，若模型回调线程正好在 {@code addTransition}，
         * 调用方会撞上 {@link java.util.ConcurrentModificationException}——
         * 一次「只是看看审计轨迹」的读操作反而把请求打挂。
         * 拷贝在持锁状态下完成，与 {@code transition} 的写入互斥。
         * </p>
         */
        public List<AgentStateTransition> snapshotTransitions() {
            synchronized (this) {
                return List.copyOf(transitions);
            }
        }
    }

    // ==================== 内部方法（由 evictIdleSessions 代替）====================
}