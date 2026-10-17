package com.devops.agent.controller.dto;

import java.util.List;

/**
 * 指标元数据描述 DTO。
 */
public record MetricMetaDto(
        String id,
        String name,
        String unit,
        String describe
) {}
