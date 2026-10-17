package com.devops.agent.controller.dto;

import java.util.List;

/**
 * 诊断会话汇总统计。
 *
 * @param total              窗口内会话总数
 * @param byStatus           状态分布
 * @param avgDurationSeconds 平均耗时秒数（无完成会话时为 null）
 * @param avgCostRmb         单次诊断均价（无完成会话时为 null）
 * @param sufficiency        充分性分布
 */
public record DiagnosisSessionsStats(
        long total,
        List<DiagnosisStatusCount> byStatus,
        Double avgDurationSeconds,
        Double avgCostRmb,
        List<DiagnosisSufficiencyStat> sufficiency
) {
}
