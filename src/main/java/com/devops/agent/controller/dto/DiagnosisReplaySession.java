package com.devops.agent.controller.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.sql.Timestamp;

/** Existing diagnosis session row with wire names preserved verbatim. */
@Schema(name = "DiagnosisReplaySession")
public record DiagnosisReplaySession(
        @JsonProperty("id") Long id,
        @JsonProperty("trace_id") String traceId,
        @JsonProperty("alert_id") Long alertId,
        @JsonProperty("ticket_id") String ticketId,
        @JsonProperty("service") String service,
        @JsonProperty("status") String status,
        @JsonProperty("sufficiency") String sufficiency,
        @JsonProperty("summary") String summary,
        @JsonProperty("created_at") Timestamp createdAt,
        @JsonProperty("updated_at") Timestamp updatedAt
) implements DiagnosisReplaySessionView {
}
