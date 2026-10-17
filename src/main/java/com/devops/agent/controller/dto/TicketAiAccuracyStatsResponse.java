package com.devops.agent.controller.dto;

/**
 * AI 分析准确率统计响应 DTO。
 */
public record TicketAiAccuracyStatsResponse(
        long total,
        long rated,
        long helpful,
        long unhelpful,
        Double helpfulRate
) {}
