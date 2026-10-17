package com.devops.agent.controller.dto;

import java.util.List;

/**
 * AI 调用日志分页响应 DTO。
 */
public record AuditAiCallsResponse(
        List<AiCallLogItem> items,
        int total,
        int page,
        int size,
        int totalPages,
        AiCallStats stats
) {}
