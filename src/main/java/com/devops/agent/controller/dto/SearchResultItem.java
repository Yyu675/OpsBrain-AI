package com.devops.agent.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "跨模块全局搜索项")
public record SearchResultItem(
        @Schema(description = "实体ID")
        String id,
        @Schema(description = "实体标题/名称")
        String title,
        @Schema(description = "实体类型（ticket / knowledge / alert）")
        String kind,
        @Schema(description = "子状态或关联信息")
        String sub
) {
}
