package com.devops.agent.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "知识库索引重建失败项")
public record KnowledgeBaseReindexFailure(
        long docId,
        String title,
        String indexStatus,
        String error
) {}
