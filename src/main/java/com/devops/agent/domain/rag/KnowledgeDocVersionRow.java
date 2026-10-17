package com.devops.agent.domain.rag;

import java.time.LocalDateTime;

/** Fixed projection for a knowledge document's version-history list (without body content). */
public record KnowledgeDocVersionRow(
        Long id,
        Long docId,
        Integer version,
        String title,
        String category,
        String author,
        String contentHash,
        Integer contentLength,
        String changedBy,
        String changeReason,
        String changeType,
        LocalDateTime createTime) {}
