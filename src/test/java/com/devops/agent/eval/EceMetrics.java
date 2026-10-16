package com.devops.agent.eval;

import java.util.ArrayList;
import java.util.List;

/**
 * 期望校准误差 ECE（Expected Calibration Error，路线图 §8.1 S4-2 的计算面）。
 *
 * <h3>为什么现在写骨架（真窗未开）</h3>
 * S4-2 的堵点是 EVAL_LLM 真窗数据（置信度-正确性成对样本），但<b>公式本身</b>
 * 与数据无关，可以先用确定性单测钉死——与 {@link RetrievalRankMetrics} 的
 * 「计算与数据源分层」同一条戒律。真窗首开日只剩三步：数据源产成对样本
 * （挂 {@code ConfidenceEngine} 输出与逐题判定结果）→ 调本类 →
 * {@code EvalMetricsWriter.mergeLayer} 以 {@code ece} 键落盘，compare 侧
 * 方向词表已随批 33 预接（rise>2pt 即劣化）。
 *
 * <h3>空样本为什么不许报 0</h3>
 * ECE 是「越低越好」的指标：空样本返回 0 等于把「还没测过」报成「完美校准」
 * ——这是方向版回归里最难察觉的假满分。本类遇空返回 {@link Double#NaN}，
 * 落盘侧见 NaN 应跳过该键（「留空拒伪造」口径的方向镜像，报告 102 §三同族）。
 *
 * <h3>公式</h3>
 * {@code ECE = Σ_k (|B_k|/N) · |acc(B_k) − conf(B_k)|}，等宽 bin，
 * 置信度必须落在 [0,1]（越界即抛——静默钳位会把上游单位错写成永恒的 100%/1 之争）。
 */
final class EceMetrics {

    private EceMetrics() {
    }

    /** 单个样本的校准面：模型报出的置信度（[0,1]）+ 该回答是否事实正确。 */
    record CalibrationPair(double confidence, boolean correct) {
    }

    /** 单个 bin 的聚合面（诊断与报表共用的中间量）。 */
    record BucketResult(int count, double meanConfidence, double fractionCorrect, double gap) {
    }

    /**
     * ECE 主指标。
     *
     * @return [0,1] 的校准误差；样本空返回 {@link Double#NaN}（见类注释）
     * @throws IllegalArgumentException binCount 非正数或任一置信度越出 [0,1]
     */
    static double ece(List<CalibrationPair> pairs, int binCount) {
        if (pairs == null || pairs.isEmpty()) {
            return Double.NaN;
        }
        List<BucketResult> buckets = buckets(pairs, binCount);
        double n = pairs.size();
        double sum = 0.0;
        for (BucketResult b : buckets) {
            if (b.count() > 0) {
                sum += (b.count() / n) * b.gap();
            }
        }
        return sum;
    }

    /** 分桶面：返回长度恰为 binCount 的桶序列，空桶 count=0（gap 给 0 防 NaN 污染报表）。 */
    static List<BucketResult> buckets(List<CalibrationPair> pairs, int binCount) {
        if (binCount <= 0) {
            throw new IllegalArgumentException("binCount 必须为正整数，收到：" + binCount);
        }
        if (pairs == null || pairs.isEmpty()) {
            List<BucketResult> empty = new ArrayList<>(binCount);
            for (int i = 0; i < binCount; i++) {
                empty.add(new BucketResult(0, 0.0, 0.0, 0.0));
            }
            return empty;
        }
        double[] confSum = new double[binCount];
        double[] correctSum = new double[binCount];
        int[] counts = new int[binCount];
        for (CalibrationPair p : pairs) {
            double c = p.confidence();
            if (Double.isNaN(c) || c < 0.0 || c > 1.0) {
                throw new IllegalArgumentException("置信度必须落在 [0,1]，收到：" + c
                        + "——别用钳位糊弄单位，上游修对再进来");
            }
            // 1.0 归最后一个 bin（等宽切法 [0,1) 会把满置信度甩出界）
            int idx = Math.min((int) (c * binCount), binCount - 1);
            counts[idx]++;
            confSum[idx] += c;
            correctSum[idx] += p.correct() ? 1.0 : 0.0;
        }
        List<BucketResult> out = new ArrayList<>(binCount);
        for (int i = 0; i < binCount; i++) {
            if (counts[i] == 0) {
                out.add(new BucketResult(0, 0.0, 0.0, 0.0));
            } else {
                double meanConf = confSum[i] / counts[i];
                double fracCorrect = correctSum[i] / counts[i];
                out.add(new BucketResult(counts[i], meanConf, fracCorrect, Math.abs(fracCorrect - meanConf)));
            }
        }
        return out;
    }
}
