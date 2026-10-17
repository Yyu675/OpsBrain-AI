package com.devops.agent.domain.audit;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Fixed, privacy-limited list row for the AI-call audit page (no full query/answer). */
public record AgentCallLogRow(
        Long id,
        String traceId,
        String modelName,
        Boolean isCached,
        Integer latencyMs,
        BigDecimal costRmb,
        String operationType,
        String operatorId,
        String affectedResources,
        String queryPreview,
        LocalDateTime createTime
) {
}
