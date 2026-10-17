package com.devops.agent.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "实体删除确认响应")
public record EntityDeleteResponse(
        @Schema(description = "已删除的实体 ID")
        long id,

        @Schema(description = "删除是否成功")
        boolean deleted
) {
}
