package com.devops.agent.controller.dto;

import java.util.List;

/**
 * 操作审计分页响应 DTO。
 */
public record AuditOperationsResponse(
        List<OperationAuditItem> items,
        int total,
        int page,
        int size,
        int totalPages
) {}
