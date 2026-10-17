package com.devops.agent.controller.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * AI 调用日志列表项 DTO（列表态，不含全文）。
 */
public record AiCallLogItem(
        Long id,
        @JsonProperty("trace_id") String traceId,
        @JsonProperty("model_name") String modelName,
        @JsonProperty("is_cached") Boolean isCached,
        @JsonProperty("latency_ms") Integer latencyMs,
        @JsonProperty("cost_rmb") BigDecimal costRmb,
        @JsonProperty("operation_type") String operationType,
        @JsonProperty("operator_id") String operatorId,
        @JsonProperty("affected_resources") String affectedResources,
        @JsonProperty("query_preview") String queryPreview,
        @JsonProperty("create_time") LocalDateTime createTime
) {}
