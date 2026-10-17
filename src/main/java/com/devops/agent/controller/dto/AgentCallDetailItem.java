package com.devops.agent.controller.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 单条 AI 调用详情 DTO（下钻态，含问答与引用）。
 */
public record AgentCallDetailItem(
        Long id,
        @JsonProperty("trace_id") String traceId,
        @JsonProperty("user_query") String userQuery,
        @JsonProperty("agent_answer") String agentAnswer,
        @JsonProperty("model_name") String modelName,
        @JsonProperty("is_cached") Boolean isCached,
        @JsonProperty("latency_ms") Integer latencyMs,
        @JsonProperty("cost_rmb") BigDecimal costRmb,
        String citations,
        @JsonProperty("operation_type") String operationType,
        @JsonProperty("affected_resources") String affectedResources,
        @JsonProperty("operator_id") String operatorId,
        @JsonProperty("create_time") LocalDateTime createTime
) {}
