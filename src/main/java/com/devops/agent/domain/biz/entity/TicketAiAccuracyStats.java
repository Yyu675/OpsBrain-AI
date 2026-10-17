package com.devops.agent.domain.biz.entity;

/** Typed accuracy metrics for AI analysis feedback. */
public record TicketAiAccuracyStats(
        long total,
        long rated,
        long helpful,
        long unhelpful,
        double helpfulRate) {
}
