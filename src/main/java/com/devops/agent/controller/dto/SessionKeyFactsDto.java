package com.devops.agent.controller.dto;

import java.util.List;

/**
 * 会话关键事实 DTO。
 */
public record SessionKeyFactsDto(
        String intent,
        List<String> confirmedFacts,
        String conclusion,
        List<String> pendingRisks
) {}
