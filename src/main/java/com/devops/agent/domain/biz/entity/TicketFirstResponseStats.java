package com.devops.agent.domain.biz.entity;

/** Typed aggregate projection for ticket first-response SLA metrics. */
public record TicketFirstResponseStats(
        long responded,
        long notResponded,
        long breached,
        Double avgFirstResponseMinutes) {
}
