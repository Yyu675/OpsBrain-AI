package com.devops.agent.domain.healing;

import java.time.LocalDateTime;

/**
 * 自愈执行台账行（对应表 {@code sys_healing_execution}，V7）。
 * <p>
 * record 字段名即列名语义的一一映射——审计查询的返回结构就是它本身，
 * 零转换回放。写入侧用 {@link #draft} 工厂（id/finishedAt 由库补全）。
 * </p>
 */
public record HealingExecution(
        Long id,
        String actionKey,
        String environment,
        String target,
        String paramsJson,
        Long alertId,
        String requestedBy,
        String gateDecision,
        Long approvalId,
        String executorKey,
        String status,
        String dryRunPlan,
        String output,
        String error,
        String preSnapshotJson,
        String undoToken,
        LocalDateTime createdAt,
        LocalDateTime finishedAt) {

    /** 状态机终态集合（批次 3 撤销动作加入 UNDONE）。 */
    public static final class Status {
        private Status() {}
        /** 审批中：等人工点头，执行器尚未被触碰 */
        public static final String PENDING_APPROVAL = "PENDING_APPROVAL";
        /** 执行成功 */
        public static final String SUCCEEDED = "SUCCEEDED";
        /** 执行失败（含演算失败的早夭） */
        public static final String FAILED = "FAILED";
        /** 治理门拒绝（DENIED / NO_EXECUTOR 共用一行记录） */
        public static final String REJECTED = "REJECTED";
        /** 已撤销（批次 3 回滚触发器写入） */
        public static final String UNDONE = "UNDONE";
        /** 撤销失败（原执行成功的事实不动，撤销尝试单独留痕） */
        public static final String UNDO_FAILED = "UNDO_FAILED";
    }

    /** 新建草稿（insert 前的内存形态：id / createdAt / finishedAt 未知）。 */
    public static HealingExecution draft(String actionKey, String environment, String target,
                                         String paramsJson, Long alertId, String requestedBy,
                                         String gateDecision, Long approvalId, String executorKey,
                                         String status, String dryRunPlan, String output,
                                         String error, String preSnapshotJson, String undoToken) {
        return new HealingExecution(null, actionKey, environment, target, paramsJson,
                alertId, requestedBy, gateDecision, approvalId, executorKey, status,
                dryRunPlan, output, error, preSnapshotJson, undoToken, null, null);
    }
}
