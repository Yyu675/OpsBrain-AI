package com.devops.agent.domain.rag;

/** 全局标签字典视图。文档关联表保留字符串以兼容旧数据。 */
public record KnowledgeTag(
        Long id,
        String name,
        String description,
        String color,
        long usageCount
) {}
