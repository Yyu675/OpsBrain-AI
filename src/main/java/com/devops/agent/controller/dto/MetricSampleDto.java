package com.devops.agent.controller.dto;

import java.util.Map;

/**
 * 瞬时采样点 DTO。
 */
public record MetricSampleDto(
        Map<String, String> labels,
        Double value,
        Long timestamp
) {}
