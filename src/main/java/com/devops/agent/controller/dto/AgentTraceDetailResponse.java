package com.devops.agent.controller.dto;

import java.util.List;

/**
 * Agent 轨迹详情响应（GET /api/v1/agent/traces/{traceId}）。
 *
 * @param traceId          追踪 ID
 * @param found            会话是否存在
 * @param currentState     当前状态机器码
 * @param currentStateText 当前状态中文名
 * @param settled          是否已脱离自动链路
 * @param terminal         是否终态
 * @param transitions      状态迁移项列表
 * @param transitionCount  状态迁移项数量
 * @param totalDurationMs  总耗时（毫秒）
 * @param message          提示说明信息
 */
public record AgentTraceDetailResponse(
        String traceId,
        boolean found,
        String currentState,
        String currentStateText,
        boolean settled,
        boolean terminal,
        List<AgentTransitionItemResponse> transitions,
        int transitionCount,
        long totalDurationMs,
        String message
) {
    public static AgentTraceDetailResponse notFound(String traceId) {
        return new AgentTraceDetailResponse(
                traceId,
                false,
                null,
                null,
                false,
                false,
                List.of(),
                0,
                0L,
                "会话不存在：可能已超过 30 分钟空闲期被清理，或 traceId 有误"
        );
    }
}
