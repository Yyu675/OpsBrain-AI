package com.devops.agent.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "文档移动分类确认响应")
public record DocumentMoveResponse(
        @Schema(description = "文档 ID")
        long id,

        @Schema(description = "移动是否成功")
        boolean moved
) {
}
