package com.devops.agent.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "工单复盘时间线草稿响应")
public record PostmortemDraftResponse(
        @Schema(description = "生成的时间线草稿内容")
        String timeline
) {
}
