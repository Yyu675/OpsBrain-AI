package com.devops.agent.controller.dto;

import java.util.Map;

/**
 * 工单总体统计响应 DTO。
 */
public record TicketStatsResponse(
        long total,
        long todayNew,
        Map<String, Long> byStatus,
        long pending,
        long processing,
        long resolved,
        Map<String, Long> byPriority,
        long urgentPending,
        long unassignedOpen
) {}
