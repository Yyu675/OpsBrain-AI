package com.devops.agent.controller.dto;

import java.util.List;

/**
 * 假设校准指标与分布。
 *
 * @param ratedTotal         判定集规模
 * @param helpful            正向反馈数
 * @param wrong              负向反馈数
 * @param excludedPartial    豁免部分正确数
 * @param excludedUnknown    豁免未知反馈数
 * @param excludedInvalid    豁免非法置信度数
 * @param ece                期望校准误差
 * @param empiricalAccuracy  经验准确率
 * @param meanConfidence     平均置信度
 * @param buckets            校准分桶详情
 */
public record HypothesisCalibration(
        long ratedTotal,
        long helpful,
        long wrong,
        long excludedPartial,
        long excludedUnknown,
        long excludedInvalid,
        Double ece,
        Double empiricalAccuracy,
        Double meanConfidence,
        List<CalibrationBucket> buckets
) {
}
