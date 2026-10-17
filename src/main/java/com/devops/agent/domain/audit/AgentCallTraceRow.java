package com.devops.agent.domain.audit;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Typed AI-call row returned by audit trace-id lookups. */
public record AgentCallTraceRow(
        Long id,
        String traceId,
        String userQuery,
        String agentAnswer,
        String modelName,
        Boolean isCached,
        Integer latencyMs,
        BigDecimal costRmb,
        String citations,
        String operationType,
        String affectedResources,
        String operatorId,
        LocalDateTime createTime) {
}
