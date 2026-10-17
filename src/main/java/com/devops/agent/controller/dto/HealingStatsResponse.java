package com.devops.agent.controller.dto;

/**
 * 自愈执行台账统计聚合响应（GET /api/v1/healing/stats）。
 *
 * <p>P0-2b 强契约化：替代弱类型 {@code Map<String, Object>}。
 * 字段严格对齐前端 {@code healing.ts#HealingStats}。</p>
 *
 * @param total              总自愈执行数
 * @param succeeded          成功执行数
 * @param failed             失败执行数
 * @param rejected           拒绝执行数
 * @param pendingApproval    待审批执行数
 * @param undone             已撤销数
 * @param undoFailed         撤销失败数
 * @param autoTotal          自动化发起总数
 * @param autoClosedLoop     自动化闭环数
 * @param verifyPass         验证通过数
 * @param verifyFail         验证失败数
 * @param verifyUnknown      验证未知数
 * @param verifySkipped      验证跳过数
 * @param verifyPending      待验证数
 * @param autoClosedLoopRate 自动化闭环率（0~1，保留3位小数）
 * @param verifyFailRate     验证失败率（0~1，保留3位小数）
 * @param undoSuccessRate    撤销成功率（0~1，保留3位小数）
 */
public record HealingStatsResponse(
        long total,
        long succeeded,
        long failed,
        long rejected,
        long pendingApproval,
        long undone,
        long undoFailed,
        long autoTotal,
        long autoClosedLoop,
        long verifyPass,
        long verifyFail,
        long verifyUnknown,
        long verifySkipped,
        long verifyPending,
        double autoClosedLoopRate,
        double verifyFailRate,
        double undoSuccessRate
) {
}
