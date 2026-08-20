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
     */
    private void evictIdleSessions() {
        LocalDateTime now = LocalDateTime.now();
        int before = sessionStates.size();
        sessionStates.values().removeIf(s -> {
            if (s.getLastTransitionTime() == null) return false;
            return java.time.Duration.between(s.getLastTransitionTime(), now).toMillis() > SESSION_IDLE_TIMEOUT_MS;
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

        AgentState fromState = session.getCurrentState();

        // 校验合法迁移
        if (!AgentState.canTransition(fromState, toState)) {
            log.warn("⚠️ [StateManager] 非法状态迁移 | traceId={} | from={} | to={} | trigger={}",
                    traceId, fromState, toState, trigger);
            return null;
        }

        // 计算耗时
        long durationMs = 0;
        if (session.getLastTransitionTime() != null) {
            durationMs = java.time.Duration.between(session.getLastTransitionTime(), LocalDateTime.now()).toMillis();
        }

        // 创建迁移记录
        AgentStateTransition transition = AgentStateTransition.of(
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
     */
    private static class SessionState {
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
    }

    // ==================== 内部方法（由 evictIdleSessions 代替）====================
}