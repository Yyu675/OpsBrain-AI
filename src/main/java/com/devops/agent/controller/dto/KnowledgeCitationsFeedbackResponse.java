package com.devops.agent.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "知识库引用权重回流响应")
public record KnowledgeCitationsFeedbackResponse(
        @Schema(description = "回流更新权重（boost）的切片数量")
        int boosted
) {
}
