package com.devops.agent.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;

@Schema(description = "工单根因聚合统计响应")
public record TicketRootCauseStatsResponse(
        @Schema(description = "根因分类与工单数映射表")
        Map<String, Long> stats
) {
}
