package com.devops.agent.domain.biz.entity;

/** Typed raw feedback counts aggregated from ticket AI analyses. */
public record TicketAiFeedbackStats(long total, long rated, long helpful, long unhelpful) {
}
