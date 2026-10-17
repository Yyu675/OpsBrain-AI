package com.devops.agent.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "知识库扁平分类项")
public record KnowledgeCategoryCountResponse(
        @Schema(description = "分类名称")
        String name,
        @Schema(description = "文档数量")
        long count
) {
}
