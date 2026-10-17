package com.devops.agent.controller.dto;

/**
 * 首响统计（MTTA）响应 DTO。
 */
public record TicketFirstResponseStatsResponse(
        long responded,
        long notResponded,
        long breached,
        Double avgFirstResponseMinutes
) {}
