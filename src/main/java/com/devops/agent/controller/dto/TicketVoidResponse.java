package com.devops.agent.controller.dto;

/**
 * 作废工单响应 DTO。
 */
public record TicketVoidResponse(
        String ticketId,
        String message
) {}
