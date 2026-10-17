package com.devops.agent.controller.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;

/**
 * 操作审计单条记录 DTO。
 * 对齐 sys_operation_audit 表各列。
 */
public record OperationAuditItem(
        Long id,
        @JsonProperty("trace_id") String traceId,
        @JsonProperty("actor_id") String actorId,
        @JsonProperty("actor_name") String actorName,
        String action,
        @JsonProperty("target_type") String targetType,
        @JsonProperty("target_id") String targetId,
        @JsonProperty("http_method") String httpMethod,
        @JsonProperty("http_path") String httpPath,
        @JsonProperty("status_code") Integer statusCode,
        Boolean success,
        @JsonProperty("biz_code") Integer bizCode,
        @JsonProperty("error_message") String errorMessage,
        @JsonProperty("client_ip") String clientIp,
        @JsonProperty("duration_ms") Integer durationMs,
        @JsonProperty("create_time") LocalDateTime createTime
) {}
