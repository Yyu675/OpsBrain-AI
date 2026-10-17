package com.devops.agent.controller.dto;

/**
 * 变更事件上报响应 DTO。
 */
public record ChangeEventReportResponse(
        boolean success,
        boolean deduplicated,
        String message
) {}
