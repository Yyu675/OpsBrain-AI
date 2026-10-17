package com.devops.agent.controller.dto;

import java.time.LocalDateTime;

/**
 * 历史会话摘要项 DTO。
 */
public record SessionSummaryItem(
        String sessionId,
        String traceId,
        String summary,
        Integer turnCount,
        Integer totalTokens,
        Double totalCostRmb,
        String finalState,
        String relatedTickets,
        SessionKeyFactsDto keyFacts,
        LocalDateTime createTime,
        LocalDateTime updateTime
) {}
