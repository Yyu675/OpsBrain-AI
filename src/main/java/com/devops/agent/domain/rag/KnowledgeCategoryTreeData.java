package com.devops.agent.domain.rag;

import java.time.LocalDateTime;
import java.util.List;

/** Typed internal projection used to assemble the category tree response. */
public record KnowledgeCategoryTreeData(List<Node> categories, List<Document> uncategorized) {

    public record Node(Long id, Long parentId, String name, Integer sortOrder, Long docCount,
                       List<Document> documents) {}

    public record Document(Long id, String title, String category, Long categoryId,
                           Integer version, String status, LocalDateTime updateTime) {}
}
