package com.devops.agent.controller.dto;

/**
 * 待审审批单计数响应（GET /api/v1/approvals/pending/count，前端角标）。
 *
 * <p>P0-2b 强契约化：替代旧有的 {@code Map<String, Object>}。
 * 字段名 {@code pending} 严格对齐前端角标组件消费者 {@code approval.ts#pendingCount}。</p>
 *
 * @param pending 待审单数量（前端角标展示）
 */
public record PendingApprovalCountResponse(
        long pending
) {
}
