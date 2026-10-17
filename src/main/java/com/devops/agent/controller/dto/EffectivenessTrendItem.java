package com.devops.agent.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;

@Schema(description = "效能指标每日快照行")
public record EffectivenessTrendItem(
        @Schema(description = "快照日期")
        LocalDate snapshot_date,
        @Schema(description = "工单总数")
        long total_tickets,
        @Schema(description = "已完结工单数")
        long finished_tickets,
        @Schema(description = "复盘文档数")
        long postmortem_count,
        @Schema(description = "告警触发的工单数")
        long alert_sourced_tickets,
        @Schema(description = "告警总数")
        long alerts_total,
        @Schema(description = "事件总数")
        long events_total,
        @Schema(description = "诊断总数")
        long diagnosis_total,
        @Schema(description = "充分诊断数")
        long diagnosis_sufficient,
        @Schema(description = "知识证据数")
        long knowledge_evidence,
        @Schema(description = "知识命中数")
        long knowledge_hits,
        @Schema(description = "自愈执行总数")
        long healing_total,
        @Schema(description = "自愈成功总数")
        long healing_succeeded
) {
}
