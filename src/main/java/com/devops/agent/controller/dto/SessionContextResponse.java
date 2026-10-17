package com.devops.agent.controller.dto;

import java.util.List;

/**
 * 会话上下文预览响应 DTO。
 */
public record SessionContextResponse(
        String sessionId,
        List<String> recentHistory,
        String keyFactsText,
        boolean hasHistory
) {}
