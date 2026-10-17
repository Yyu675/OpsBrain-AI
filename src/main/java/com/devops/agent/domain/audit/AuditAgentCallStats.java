package com.devops.agent.domain.audit;

import java.math.BigDecimal;

/** Fixed aggregate result for a filtered AI-call audit query. */
public record AuditAgentCallStats(
        long totalCalls,
        long cacheHits,
        double cacheHitRate,
        BigDecimal totalCost,
        long avgLatencyMs
) {
}
