package com.devops.agent.controller.dto;

import java.util.List;

/**
 * 审计筛选候选值响应 DTO。
 */
public record AuditFilterOptionsResponse(
        List<String> models,
        List<String> operationTypes,
        List<String> actions,
        List<String> targetTypes
) {}
