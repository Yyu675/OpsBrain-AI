package com.devops.agent.controller.dto;

import java.util.List;

/**
 * 内置指标目录响应 DTO。
 */
public record MetricCatalogResponse(
        List<MetricMetaDto> metrics,
        boolean enabled
) {}
