package com.devops.agent.controller.dto;

/**
 * 校准桶分布项。
 *
 * @param index          桶序号（0~9）
 * @param count          样本数
 * @param meanConfidence 平均置信度
 * @param accuracy       实际准确率
 * @param gap            偏差绝对值
 */
public record CalibrationBucket(
        int index,
        long count,
        Double meanConfidence,
        Double accuracy,
        Double gap
) {
}
