package com.devops.agent.controller.dto;

/**
 * 诊断证据方向统计项。
 *
 * @param type        证据类型
 * @param total       总量
 * @param success     成功数
 * @param noData      无数据数
 * @param failed      失败数
 * @param unavailable 不可用数
 * @param successRate 成功率（0~1）
 */
public record DiagnosisDirectionStat(
        String type,
        long total,
        long success,
        long noData,
        long failed,
        long unavailable,
        double successRate
) {
}
