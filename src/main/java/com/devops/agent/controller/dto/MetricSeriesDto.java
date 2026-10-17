package com.devops.agent.controller.dto;

import java.util.List;
import java.util.Map;

/**
 * 区间查询时间线 DTO。
 */
public record MetricSeriesDto(
        Map<String, String> labels,
        List<SeriesPointDto> points
) {}
