package com.devops.agent.controller.dto;

import java.util.List;

/**
 * 监控总览单张卡片 DTO。
 */
public record OverviewCardDto(
        String name,
        String unit,
        String describe,
        boolean ok,
        String error,
        List<MetricSampleDto> samples
) {}
