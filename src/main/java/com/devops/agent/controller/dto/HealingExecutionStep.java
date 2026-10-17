package com.devops.agent.controller.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;

/** One stable timeline entry stored in the execution journal's steps_json. */
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "自愈执行步骤时间节点")
public record HealingExecutionStep(
        @Schema(description = "步骤标识") String name,
        @Schema(description = "步骤结果") String status,
        @Schema(description = "步骤摘要") String detail,
        @Schema(description = "发生时间，ISO-8601") String at
) {}
