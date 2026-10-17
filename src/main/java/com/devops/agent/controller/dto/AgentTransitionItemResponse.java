package com.devops.agent.controller.dto;

import java.time.LocalDateTime;

/**
 * Agent 状态机迁移单项记录（GET /api/v1/agent/traces/{traceId} -> transitions 项）。
 *
 * @param id            迁移记录 ID
 * @param sessionId     会话 ID
 * @param fromState     来源状态机器码
 * @param fromStateText 来源状态中文名
 * @param toState       目标状态机器码
 * @param toStateText   目标状态中文名
 * @param triggerType   触发器类型
 * @param triggerDetail 触发详情
 * @param operator      操作人/主体
 * @param timestamp     时间戳
 * @param durationMs    本段耗时（毫秒）
 * @param metadata      扩展元数据
 */
public record AgentTransitionItemResponse(
        String id,
        String sessionId,
        String fromState,
        String fromStateText,
        String toState,
        String toStateText,
        String triggerType,
        String triggerDetail,
        String operator,
        LocalDateTime timestamp,
        long durationMs,
        String metadata
) {
}
