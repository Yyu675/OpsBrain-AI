package com.devops.agent.controller.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.sql.Timestamp;

@Schema(description = "诊断证据采集项")
public record DiagnosisEvidenceItem(
        Long id,
        @JsonProperty("trace_id") String traceId,
        @JsonProperty("agent_name") String agentName,
        @JsonProperty("evidence_type") String evidenceType,
        String status,
        String title,
        String content,
        @JsonProperty("source_ref") String sourceRef,
        @JsonProperty("relevance_score") Double relevanceScore,
        @JsonProperty("collected_at") Timestamp collectedAt
) {
}
