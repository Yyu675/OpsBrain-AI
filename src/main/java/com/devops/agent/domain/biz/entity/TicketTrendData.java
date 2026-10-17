package com.devops.agent.domain.biz.entity;

import java.util.List;

/** Typed, zero-filled daily ticket trend series. */
public record TicketTrendData(
        List<String> days,
        List<Long> created,
        List<Long> resolved,
        String module) {
}
