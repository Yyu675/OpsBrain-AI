package com.devops.agent.domain.rag;

/** Typed category aggregation row from the knowledge-document index. */
public record KnowledgeCategoryCount(String name, long count) {
}
