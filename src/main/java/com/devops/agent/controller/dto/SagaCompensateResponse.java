package com.devops.agent.controller.dto;

import java.util.List;

/**
 * Saga 手动重试补偿响应 DTO。
 */
public record SagaCompensateResponse(
        String sagaId,
        int compensatedCount,
        int failedCount,
        List<String> compensated,
        List<String> failed,
        boolean fullySucceeded
) {}
