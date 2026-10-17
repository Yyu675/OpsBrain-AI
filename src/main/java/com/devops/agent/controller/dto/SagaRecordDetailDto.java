package com.devops.agent.controller.dto;

import java.time.LocalDateTime;

/**
 * Saga 执行记录详情 DTO。
 */
public record SagaRecordDetailDto(
        Long id,
        String traceId,
        String sessionId,
        String sagaId,
        Integer stepSeq,
        String toolName,
        String riskLevel,
        String riskLevelLabel,
        String state,
        String stateLabel,
        boolean needsAttention,
        String failureType,
        String failureHint,
        String errorMessage,
        Boolean compensable,
        String compensationAction,
        String businessKey,
        LocalDateTime compensatedAt,
        String compensationError,
        Integer attemptCount,
        Integer durationMs,
        LocalDateTime createTime,
        LocalDateTime updateTime
) {}
