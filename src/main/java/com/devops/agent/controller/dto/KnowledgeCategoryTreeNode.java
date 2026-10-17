package com.devops.agent.controller.dto;

import java.util.List;

/**
 * 知识分类树中包含文档的分类节点 DTO。
 */
public record KnowledgeCategoryTreeNode(
        Long id,
        Long parentId,
        String name,
        Integer sortOrder,
        Long docCount,
        List<KnowledgeTreeDocument> documents
) {}
