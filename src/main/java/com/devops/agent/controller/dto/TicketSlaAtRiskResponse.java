package com.devops.agent.controller.dto;

import java.util.List;

/**
 * SLA 风险工单清单响应 DTO。
 */
public record TicketSlaAtRiskResponse(
        int total,
        int withinMinutes,
        List<com.devops.agent.domain.biz.entity.DevOpsTicket> tickets
) {}
