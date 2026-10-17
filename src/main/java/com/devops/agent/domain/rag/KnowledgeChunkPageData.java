package com.devops.agent.domain.rag;

import java.util.List;

/** Typed internal page projection for active knowledge chunks. */
public record KnowledgeChunkPageData(
        List<KnowledgeChunkView> content,
        long totalElements,
        int totalPages,
        int currentPage,
        int pageSize) {
}
