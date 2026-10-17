package com.devops.agent.controller.dto;

import java.time.LocalDateTime;

/**
 * 知识分类树中文档节点 DTO。
 */
public record KnowledgeTreeDocument(
        Long id,
        String title,
        String category,
        Long categoryId,
        Integer version,
        String status,
        LocalDateTime updateTime
) {}
