package com.devops.agent.controller.dto;

import java.util.List;

/**
 * 指标趋势区间查询响应 DTO。
 */
public record MetricRangeResponse(
        String metric,
        Long from,
        Long to,
        Integer step,
        Integer hours,
        List<MetricSeriesDto> series
) {}
