package com.devops.agent.application.runtime;

import java.time.LocalDate;

/** Typed daily snapshot row used by effectiveness trend queries. */
public record EffectivenessTrendRow(
        LocalDate snapshotDate,
        long totalTickets,
        long finishedTickets,
        long postmortemCount,
        long alertSourcedTickets,
        long alertsTotal,
        long eventsTotal,
        long diagnosisTotal,
        long diagnosisSufficient,
        long knowledgeEvidence,
        long knowledgeHits,
        long healingTotal,
        long healingSucceeded) {
}
