package com.devops.agent.controller.dto;

import java.util.List;

/**
 * 链路详情响应 DTO。
 */
public record AuditTraceDetailResponse(
        String traceId,
        AgentCallDetailItem aiCall,
        List<OperationAuditItem> operations
) {}
