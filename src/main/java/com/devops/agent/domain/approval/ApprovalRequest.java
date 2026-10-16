package com.devops.agent.domain.approval;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 审批单实体（方向 D：L3 人机协同审批）
 *
 * <p>对应 {@code sys_approval_request} 表。承载<b>可重放的动作上下文</b>（{@link #payload}）——
 * 审批通过后据此执行动作。此前审批只有标记位、动作被丢弃，「人机协同」名不副实。</p>
 *
 * @author OpsBrain AI
 * @since 2026-08-20
 */
@Data
public class ApprovalRequest {

    private Long id;

    /** 动作类型：CREATE_TICKET / EXECUTE_SCRIPT（预留） */
    private String actionType;

    /** 触发审批的工具名（对齐 @ToolMeta.name） */
    private String toolName;

    /** 风险等级，复用 ToolRiskLevel 枚举值（不新建 ActionPermissionLevel，见其 javadoc） */
    private String riskLevel;

    /** 人可读的「要做什么」，审批列表展示 */
    private String summary;

    /**
     * 可重放的动作上下文（JSON 原文）
     * <p>批准时由编排层反序列化并执行。不存则批准后无从执行——本表存在的核心理由。</p>
     */
    private String payload;

    /** 申请方：AI / 用户名 */
    private String requester;

    /** 关联发起请求，可回放整条 Agent 链路 */
    private String traceId;
    private String sessionId;

    /** 状态（见 {@link ApprovalStatus}） */
    private String status;

    /** 审批人（来自 Sa-Token 真实身份，非前端传入——防伪造） */
    private String approver;
    private LocalDateTime decidedAt;
    /** 决策理由（驳回必填，批准可选） */
    private String decisionReason;
    /** 审批时限，超时由定时任务标 EXPIRED */
    private LocalDateTime expiresAt;

    /** 执行时刻与结果（批准后） */
    private LocalDateTime executedAt;
    private String executeResult;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    // ==================== 派生 ====================

    /** 状态枚举（安全解析） */
    public ApprovalStatus statusEnum() {
        return ApprovalStatus.parse(status);
    }

    /** 是否可审批 */
    public boolean isDecidable() {
        return statusEnum().isDecidable();
    }

    /** 是否已超时（供前端标红提醒；实际 EXPIRED 由定时任务固化） */
    public boolean isOverdue() {
        return statusEnum() == ApprovalStatus.PENDING
                && expiresAt != null
                && expiresAt.isBefore(LocalDateTime.now());
    }

    /** 是否高危（HIGH_RISK_EXECUTION）——前端强标识 */
    public boolean isHighRisk() {
        return "HIGH_RISK_EXECUTION".equalsIgnoreCase(riskLevel);
    }
}
