package com.devops.agent.controller.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.sql.Timestamp;

@Schema(description = "诊断根因假设项")
public record DiagnosisHypothesisItem(
        Long id,
        @JsonProperty("session_trace_id") String sessionTraceId,
        Integer rank,
        String statement,
        String reasoning,
        Double confidence,
        @JsonProperty("evidence_ids") String evidenceIds,
        @JsonProperty("contradict_ids") String contradictIds,
        @JsonProperty("suggested_action") String suggestedAction,
        @JsonProperty("created_at") Timestamp createdAt
) {
}
