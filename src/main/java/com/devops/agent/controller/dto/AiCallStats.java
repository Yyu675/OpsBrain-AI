package com.devops.agent.controller.dto;

import java.math.BigDecimal;

/**
 * AI 调用统计汇总 DTO。
 */
public record AiCallStats(
        long totalCalls,
        long cacheHits,
        double cacheHitRate,
        BigDecimal totalCost,
        long avgLatencyMs
) {}
