package com.devops.agent.controller.dto;

import com.devops.agent.domain.approval.ApprovalRequest;

import java.util.List;

/**
 * 审批分页响应（GET /api/v1/approvals）。
 *
 * <p>P0-2b 强契约化：替代原先弱类型的 {@code Map<String, Object>}。
 * 字段严格对齐前端 {@code approval.ts#ApprovalPage}。</p>
 *
 * @param items      当前页审批单列表
 * @param total      总审批单数
 * @param page       当前页码
 * @param size       每页大小
 * @param totalPages 总页数
 */
public record ApprovalPageResponse(
        List<ApprovalRequest> items,
        int total,
        int page,
        int size,
        int totalPages
) {
    public static ApprovalPageResponse of(List<ApprovalRequest> items, int total, int page, int size) {
        int safeSize = Math.max(1, size);
        int totalPages = (int) Math.ceil((double) total / safeSize);
        return new ApprovalPageResponse(items, total, page, safeSize, totalPages);
    }
}
