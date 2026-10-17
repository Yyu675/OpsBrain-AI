package com.devops.agent.controller.dto;

/**
 * Agent 状态管理器会话驻留统计（GET /api/v1/agent/traces/stats）。
 *
 * @param activeSessions     当前活跃会话数
 * @param idleTimeoutMinutes 空闲超时时间（分钟）
 */
public record AgentTraceStatsResponse(
        int activeSessions,
        int idleTimeoutMinutes
) {
}
