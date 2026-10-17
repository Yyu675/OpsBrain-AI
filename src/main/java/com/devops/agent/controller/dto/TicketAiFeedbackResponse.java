package com.devops.agent.controller.dto;

/**
 * AI 分析用户反馈结果 DTO。
 */
public record TicketAiFeedbackResponse(
        Long analysisId,
        boolean helpful,
        int knowledgeBoosted
) {}
