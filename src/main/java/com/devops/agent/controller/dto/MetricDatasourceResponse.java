package com.devops.agent.controller.dto;

import java.util.List;

/**
 * 监控数据源查询响应 DTO。
 */
public record MetricDatasourceResponse(
        List<MetricDatasourceDto> datasources,
        int total
) {}
