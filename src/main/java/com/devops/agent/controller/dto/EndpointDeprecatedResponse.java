package com.devops.agent.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "废弃端点停用引导响应")
public record EndpointDeprecatedResponse(
        @Schema(description = "是否已废弃")
        boolean deprecated,
        @Schema(description = "停用原因")
        String reason,
        @Schema(description = "建议迁移的新端点")
        String migrateTo
) {
}
