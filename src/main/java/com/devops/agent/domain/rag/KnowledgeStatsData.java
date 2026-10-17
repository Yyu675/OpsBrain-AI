package com.devops.agent.domain.rag;

import java.time.LocalDateTime;
import java.util.Map;

/** Typed internal projection for knowledge-base statistics. The by-source keys are data-driven. */
public record KnowledgeStatsData(
        long totalDocuments,
        long totalChunks,
        long activeChunks,
        long filteredOut,
        Map<String, Long> bySource,
        LocalDateTime lastUpdateTime,
        long hotQueryCount) {
}
