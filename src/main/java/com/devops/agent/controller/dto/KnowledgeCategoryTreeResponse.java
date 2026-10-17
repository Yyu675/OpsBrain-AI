package com.devops.agent.controller.dto;

import java.util.List;

/**
 * 知识分类树形结构响应 DTO。
 */
public record KnowledgeCategoryTreeResponse(
        List<KnowledgeCategoryTreeNode> categories,
        List<KnowledgeTreeDocument> uncategorized
) {}
