package com.devops.agent.domain.biz.entity;

/** Typed internal projection for ticket lifecycle and closure metrics. */
public record TicketClosureMetricsData(
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
        Long incidentsTotal) {

    public TicketClosureMetricsData withPostmortemMetrics(
            long alertSourced, long postmortemTotal, long finishedTotal, Double postmortemRate) {
        return new TicketClosureMetricsData(total, firstResponded, mitigated, rootCauseConfirmed,
                verified, verifySkipped, mttaMinutes, mttmMinutes, mttrMinutes, skipRate,
                alertSourced, postmortemTotal, finishedTotal, postmortemRate,
                windowDays, alertsInWindow, alertSourcedInWindow, incidentsInWindow, incidentsTotal);
    }

    public TicketClosureMetricsData withWindowMetrics(
            int windowDays, long alertsInWindow, long alertSourcedInWindow, long incidentsInWindow) {
        return new TicketClosureMetricsData(total, firstResponded, mitigated, rootCauseConfirmed,
                verified, verifySkipped, mttaMinutes, mttmMinutes, mttrMinutes, skipRate,
                alertSourced, postmortemTotal, finishedTotal, postmortemRate,
                windowDays, alertsInWindow, alertSourcedInWindow, incidentsInWindow, incidentsTotal);
    }

    public TicketClosureMetricsData withIncidentsTotal(long incidentsTotal) {
        return new TicketClosureMetricsData(total, firstResponded, mitigated, rootCauseConfirmed,
                verified, verifySkipped, mttaMinutes, mttmMinutes, mttrMinutes, skipRate,
                alertSourced, postmortemTotal, finishedTotal, postmortemRate,
                windowDays, alertsInWindow, alertSourcedInWindow, incidentsInWindow, incidentsTotal);
    }
}
