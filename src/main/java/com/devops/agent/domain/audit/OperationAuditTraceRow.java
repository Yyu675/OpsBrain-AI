package com.devops.agent.domain.audit;

import java.time.LocalDateTime;

/** Typed operation-audit row returned by trace-id lookups. */
public record OperationAuditTraceRow(
        Long id,
        String traceId,
        String actorId,
        String actorName,
        String action,
        String targetType,
        String targetId,
        String httpMethod,
        String httpPath,
        Integer statusCode,
        Boolean success,
        Integer bizCode,
        String errorMessage,
        String clientIp,
        Integer durationMs,
        LocalDateTime createTime) {
}
