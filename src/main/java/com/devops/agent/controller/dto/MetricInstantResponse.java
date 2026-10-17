package com.devops.agent.controller.dto;

import java.util.List;

/**
 * 瞬时指标查询响应 DTO。
 */
public record MetricInstantResponse(
        String metric,
        List<MetricSampleDto> samples
) {}
