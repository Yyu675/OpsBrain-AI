package com.devops.agent.controller.dto;

import java.util.List;

/**
 * 历史会话列表分页响应 DTO。
 */
public record SessionListResponse(
        List<SessionSummaryItem> sessions,
        long total,
        int page,
        int size,
        int totalPages
) {}
