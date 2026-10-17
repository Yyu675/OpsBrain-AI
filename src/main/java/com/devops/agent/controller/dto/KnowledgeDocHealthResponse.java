package com.devops.agent.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "文档级反馈健康度")
public record KnowledgeDocHealthResponse(
        @Schema(description = "文档ID")
        Long docId,
        @Schema(description = "文档标题")
        String title,
        @Schema(description = "点赞（有用）次数")
        long helpful,
        @Schema(description = "点踩（有误）次数")
        long wrong,
        @Schema(description = "净反馈（点踩 - 点赞）")
        long net
) {
}
