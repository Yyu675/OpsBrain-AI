package com.devops.agent.controller.dto;

import java.util.List;

/**
 * 时间序列采样点 DTO。
 */
public record SeriesPointDto(
        Long t,
        Double v
) {}
