package com.devops.agent.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "诊断假设质量反馈响应")
public record HypothesisFeedbackResponse(
        @Schema(description = "假设 ID")
        long hypothesisId,

        @Schema(description = "反馈判定结果(HELPFUL/PARTIAL/WRONG)")
        String feedback,

        @Schema(description = "知识库回流命中并增强的切片数")
        int knowledgeBoosted
) {
}
