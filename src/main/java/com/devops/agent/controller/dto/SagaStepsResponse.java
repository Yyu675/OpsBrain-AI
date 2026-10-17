package com.devops.agent.controller.dto;

import java.util.List;

/**
 * Saga 完整步骤回放响应 DTO。
 */
public record SagaStepsResponse(
        String sagaId,
        List<SagaRecordDetailDto> steps,
        int stepCount
) {}
