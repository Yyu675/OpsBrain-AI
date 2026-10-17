package com.devops.agent.controller.dto;

import java.time.LocalDateTime;

/**
 * 缺复盘工单摘要条目 DTO。
 */
public record MissingPostmortemItemDto(
        String id,
        String title,
        String status,
        LocalDateTime createTime
) {}
