package com.devops.agent.controller.dto;

import java.util.List;

/**
 * 待人工介入 Saga 记录列表响应 DTO。
 */
public record SagaAttentionResponse(
        List<SagaRecordDetailDto> records,
        int count
) {}
