package com.devops.agent.controller.dto;

import java.util.Map;

/**
 * 实时监控首屏总览响应 DTO。
 */
public record MetricOverviewResponse(
        Map<String, OverviewCardDto> cards,
        Long timestamp
) {}
