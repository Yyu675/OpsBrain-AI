package com.devops.agent.controller.dto;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 知识库综合统计响应 DTO。
 */
public record KnowledgeStatsResponse(
        long totalDocuments,
        long totalChunks,
        long activeChunks,
        long filteredOut,
        Map<String, Long> bySource,
        LocalDateTime lastUpdateTime,
        long hotQueryCount
) {}
