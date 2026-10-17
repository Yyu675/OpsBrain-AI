package com.devops.agent.controller.dto;

import com.devops.agent.domain.biz.entity.TicketAction;

/**
 * 记录处置动作响应 DTO。
 */
public record TicketActionResponse(
        Long id,
        TicketAction action
) {}
