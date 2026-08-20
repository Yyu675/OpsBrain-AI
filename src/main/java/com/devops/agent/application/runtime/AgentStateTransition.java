package com.devops.agent.application.runtime;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * Agent 状态迁移记录
 * <p>
 * 每次状态变更生成一条记录，用于审计、回放、一致性校验。
 * 字段设计参考 Agent Methodology §13.1 运行日志标准。
 * </p>
 *
 * @author OpsBrain AI
 * @since 2026-08-08
 */
@Data
public class AgentStateTransition {

    /**
     * 迁移唯一 ID
     */
    private String id;

    /**
     * 关联的 Trace ID（全链路追踪）
     */
    private String traceId;

    /**
     * 会话 ID（多轮对话归属）
     */
    private String sessionId;

    /**
     * 来源状态
     */
    private AgentState fromState;

    /**
     * 目标状态
     */
    private AgentState toState;

    /**
     * 触发器类型
     */
    private TriggerType triggerType;

    /**
     * 触发详情（如工具名、错误信息、审批人等）
     */
    private String triggerDetail;

    /**
     * 操作人（系统/用户/定时器）
     */
    private String operator;

    /**
     * 迁移时间戳
     */
    private LocalDateTime timestamp;

    /**
     * 迁移耗时（毫秒，从上一状态到此状态）
     */
    private long durationMs;

    /**
     * 附加元数据（JSON，存放工具参数、审批意见等）
     */
    private String metadata;

    /**
     * 触发器类型枚举
     */
    public enum TriggerType {
        /** 用户发起请求 */
        USER_REQUEST,
        /** 安全检查通过 */
        SECURITY_PASSED,
        /** 缓存命中 */
        CACHE_HIT,
        /** 路由分流完成 */
        ROUTED,
        /** 检索完成 */
        RETRIEVAL_COMPLETED,
        /** 模型决定调用工具 */
        TOOL_PLANNED,
        /** 工具开始执行 */
        TOOL_STARTED,
        /** 工具执行完成 */
        TOOL_COMPLETED,
        /** 工具执行失败 */
        TOOL_FAILED,
        /** 模型生成草稿 */
        DRAFT_GENERATED,
        /** 高风险需审批 */
        APPROVAL_REQUIRED,
        /** 人工授权通过 */
        APPROVAL_GRANTED,
        /** 人工拒绝/升级 */
        APPROVAL_DENIED,
        /** 自动执行开始 */
        EXECUTION_STARTED,
        /** 执行结果观测 */
        OBSERVATION_COMPLETED,
        /** 成功终态 */
        SUCCESS,
        /** 失败终态 */
        FAILED,
        /** 开始补偿 */
        COMPENSATION_STARTED,
        /** 补偿完成 */
        COMPENSATION_COMPLETED,
        /** 人工接管 */
        MANUAL_TAKEOVER,
        /** 超时 */
        TIMEOUT,
        /** 系统错误 */
        SYSTEM_ERROR
    }

    // ==================== 静态工厂方法 ====================

    public static AgentStateTransition of(
            String traceId, String sessionId,
            AgentState from, AgentState to,
            TriggerType trigger, String detail, String operator) {

        AgentStateTransition transition = new AgentStateTransition();
        transition.setId(java.util.UUID.randomUUID().toString().substring(0, 8));
        transition.setTraceId(traceId);
        transition.setSessionId(sessionId);
        transition.setFromState(from);
        transition.setToState(to);
        transition.setTriggerType(trigger);
        transition.setTriggerDetail(detail);
        transition.setOperator(operator);
        transition.setTimestamp(LocalDateTime.now());
        return transition;
    }

    public static AgentStateTransition of(
            String traceId, String sessionId,
            AgentState from, AgentState to,
            TriggerType trigger, String detail, String operator, long durationMs, String metadata) {

        AgentStateTransition transition = of(traceId, sessionId, from, to, trigger, detail, operator);
        transition.setDurationMs(durationMs);
        transition.setMetadata(metadata);
        return transition;
    }
}