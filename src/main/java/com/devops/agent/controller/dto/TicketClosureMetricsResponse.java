package com.devops.agent.controller.dto;

/**
 * 工单闭环度量统计响应 DTO。
 */
public record TicketClosureMetricsResponse(
        long total,
        long firstResponded,
        long mitigated,
        long rootCauseConfirmed,
        long verified,
        long verifySkipped,
        Double mttaMinutes,
        Double mttmMinutes,
        Double mttrMinutes,
        Double skipRate,
        Long alertSourced,
        Long postmortemTotal,
        Long finishedTotal,
        Double postmortemRate,
        Integer windowDays,
        Long alertsInWindow,
        Long alertSourcedInWindow,
        Long incidentsInWindow,
        Long incidentsTotal
) {}
