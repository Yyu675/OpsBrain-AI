package com.devops.agent.domain.biz.entity;

import java.util.Map;

/** Typed ticket statistics projection; status and priority maps are data-driven dictionaries. */
public record TicketStatsData(
        long total,
        long todayNew,
        Map<String, Long> byStatus,
        long pending,
        long processing,
        long resolved,
        Map<String, Long> byPriority,
        long urgentPending,
        long unassignedOpen) {
}
