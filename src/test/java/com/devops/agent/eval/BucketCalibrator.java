package com.devops.agent.eval;

import java.util.ArrayList;
import java.util.List;

/**
 * 分桶校准器（路线图 §8.2 步骤 4-2.3，D-06 已拍方案 A）。
 *
 * <h3>干什么</h3>
 * 用一批「置信度-对错」校准样本，给新置信度做后验修正：
 * 模型在某个置信度 bin 里的<b>历史真实正确率</b>，才是那个 bin 的自报
 * 置信度应该修正到的值。例：模型在 [0.9,1.0] bin 报 0.9 但历史上只对五成，
 * 校准后 0.9 → 0.5。这是「置信度别骗用户」的第一道数学护具。
 *
 * <h3>温度缩放为什么不选</h3>
 * 它需要 logits，LangChain4j 未暴露（D-06 在案）。分桶校准只依赖
 * (confidence, correct) 对——与 {@link EceMetrics} 同一张原料表，零新增采集面。
 *
 * <h3>两条诚实红线</h3>
 * ① 空校准集 → <b>恒等映射</b>（没数据不许假装会校准，原样通过并把「未校准」
 * 写在报告口径里）；② 空 bin 回退<b>全局正确率</b>而非值保持——一个从未见
 * 过样本的置信度区间，唯一有据的估计是全局均值；保持原值等于把未知区间当已知。
 */
final class BucketCalibrator {

    private BucketCalibrator() {
    }

    /**
     * 修正单个置信度。
     *
     * @param calibrationSet 校准样本；(confidence, correct) 对，允许为空（恒等）
     * @param binCount 分桶数（与 ECE 同宽，惯例 10）
     * @param confidence 待修正置信度，[0,1]（越界即抛，同 {@link EceMetrics} 戒）
     */
    static double corrected(List<EceMetrics.CalibrationPair> calibrationSet, int binCount, double confidence) {
        if (Double.isNaN(confidence) || confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("置信度必须落在 [0,1]，收到：" + confidence);
        }
        if (calibrationSet == null || calibrationSet.isEmpty()) {
            return confidence; // 红线①：无校准数据，恒等通过（不许伪造校准能力）
        }
        List<EceMetrics.BucketResult> buckets = EceMetrics.buckets(calibrationSet, binCount);
        int idx = Math.min((int) (confidence * binCount), binCount - 1);
        EceMetrics.BucketResult bin = buckets.get(idx);
        if (bin.count() > 0) {
            return bin.fractionCorrect();
        }
        // 红线②：空 bin 回退全局正确率（有据），不回退原值（无据）
        long correct = calibrationSet.stream().filter(EceMetrics.CalibrationPair::correct).count();
        return correct / (double) calibrationSet.size();
    }

    /**
     * 批量修正：把评测集的每条 (confidence, correct) 的置信度改为校准后值，
     * correct 原样保留——产出可直接喂 {@link EceMetrics#ece} 算「校准后 ECE」，
     * 与校准前 ECE 对照即 4-2.4 的前后对比证据。
     */
    static List<EceMetrics.CalibrationPair> recalibrate(
            List<EceMetrics.CalibrationPair> calibrationSet,
            List<EceMetrics.CalibrationPair> evalSet,
            int binCount) {
        if (evalSet == null || evalSet.isEmpty()) {
            return List.of();
        }
        List<EceMetrics.CalibrationPair> out = new ArrayList<>(evalSet.size());
        for (EceMetrics.CalibrationPair p : evalSet) {
            out.add(new EceMetrics.CalibrationPair(corrected(calibrationSet, binCount, p.confidence()), p.correct()));
        }
        return out;
    }
}
