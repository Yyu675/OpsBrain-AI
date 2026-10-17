package com.devops.agent.controller.dto;

import java.util.List;

/**
 * 诊断区看板响应（GET /api/v1/dashboard/diagnosis-board）。
 *
 * <p>P0-2b 强契约化：替代弱类型 {@code Map<String, Object>}。
 * 字段严格对齐前端 {@code dashboard.ts#DiagnosisBoard}。</p>
 *
 * @param windowDays         窗口天数
 * @param sessions           会话汇总
 * @param sessionTrend       诊断趋势
 * @param evidenceDirections 证据方向分布
 * @param attentionTypes     需关注方向
 * @param calibration        假设置信度校准读数
 */
public record DiagnosisBoardResponse(
        int windowDays,
        DiagnosisSessionsStats sessions,
        DiagnosisSessionTrend sessionTrend,
        List<DiagnosisDirectionStat> evidenceDirections,
        List<String> attentionTypes,
        HypothesisCalibration calibration
) {
}
