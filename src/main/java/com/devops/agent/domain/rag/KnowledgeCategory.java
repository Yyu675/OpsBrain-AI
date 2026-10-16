package com.devops.agent.domain.rag;

import java.time.LocalDateTime;

/** Knowledge-base directory category. */
public record KnowledgeCategory(
        Long id,
        Long parentId,
        String name,
        int sortOrder,
        long docCount,
        LocalDateTime createTime,
        LocalDateTime updateTime
) {}
