package com.devops.agent.domain.approval;

/**
 * 审批单状态机（方向 D：L3 人机协同审批）
 *
 * <pre>
 * PENDING ──approve──→ APPROVED ──execute──→ EXECUTED
 *    │                     │                    ↘ EXECUTE_FAILED
 *    ├──reject──→ REJECTED
 *    └──timeout─→ EXPIRED
 * </pre>
 *
 * <h3>为何 APPROVED 与 EXECUTED 分开</h3>
 * 批准后执行仍可能失败（DB 不可用、参数已失效）。若合并为一态，「已批准但没执行成功」
 * 会被误认为「已生效」——运维据此判断会出错。遵循「既成事实必须固化」契约（同 B1 首响超时）。
 *
 * @author OpsBrain AI
 * @since 2026-08-20
 */
public enum ApprovalStatus {

    /** 待审批（初始态） */
    PENDING,

    /** 已批准，待执行/执行中 */
    APPROVED,

    /** 已驳回（终态，需填理由） */
    REJECTED,

    /** 超时未审批（终态，由定时任务标记） */
    EXPIRED,

    /** 已批准且执行成功（终态） */
    EXECUTED,

    /** 已批准但执行失败（终态，需人工介入——审批意图已表达，动作未生效） */
    EXECUTE_FAILED;

    /** 是否终态（不可再流转） */
    public boolean isTerminal() {
        return this == REJECTED || this == EXPIRED || this == EXECUTED || this == EXECUTE_FAILED;
    }

    /** 是否可被审批（仅 PENDING 可批准/驳回） */
    public boolean isDecidable() {
        return this == PENDING;
    }

    /** 安全解析：未知值回退 PENDING（不抛异常中断查询） */
    public static ApprovalStatus parse(String raw) {
        if (raw == null || raw.isBlank()) return PENDING;
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return PENDING;
        }
    }
}
