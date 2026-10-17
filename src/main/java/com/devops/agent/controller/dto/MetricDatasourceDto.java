package com.devops.agent.controller.dto;

import java.util.List;

/**
 * 监控数据源健康状态 DTO。
 */
public record MetricDatasourceDto(
        String type,
        String name,
        String baseUrl,
        boolean enabled,
        boolean reachable,
        Long latencyMs,
        String error
) {}
