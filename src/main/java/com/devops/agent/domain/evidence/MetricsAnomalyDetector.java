package com.devops.agent.domain.evidence;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 指标异常检测：IQR（四分位距）法（路线图 §5.2 1-1.3「3-sigma 或 IQR」取 IQR）。
 * <p>
 * 选型理由（写死在此防止被随手换掉）：
 * 3-sigma 假设正态分布，而运维指标（CPU、错误率、P99）普遍右偏、
 * 尖峰肥尾，均值/方差被自身要检测的尖峰拉走（掩蔽效应）；
 * IQR 用分位数，天然抗离群值，小样本（30m/1m 步长≈30 点）下也稳定。
 * </p>
 * <p>
 * 口径：基线 = 中位数（不带量纲，可在不同指标间横向比较）；
 * 偏离倍数 = 当前值 / 基线；异常 = 落在 [Q1 - 1.5·IQR, Q3 + 1.5·IQR] 之外。
 * 标准 Tukey 1.5 栅栏；基线为 0 时退化为「当前值 > 上栅栏」判定
 * （错误率这类常态为 0 的指标，除以零没有意义，只有「从零到有」值得报）。
 * </p>
 */
public final class MetricsAnomalyDetector {

    /** Tukey 栅栏系数：经典箱线图离群值定义。调大=更迟钝但假阳性更少。 */
    private static final double FENCE_K = 1.5;

    /** 偏离倍数达到该值才上报——抑制「正常抖动被标异常」的噪音告警（20% 阈值）。 */
    private static final double MIN_DEVIATION_FACTOR = 1.2;

    /** 单序列内最多保留的异常点样本数（首 2 + 峰值 1），防止证据体被刷屏。 */
    private static final int MAX_SAMPLES_PER_SERIES = 3;

    private MetricsAnomalyDetector() {}

    /** 一个序列的检测结论（字段名即 JSON 键名，逐字为准）。 */
    public record SeriesVerdict(
            String metricLabels,
            double baseline,
            double currentValue,
            double deviationFactor,
            int pointCount,
            int anomalyCount,
            List<Map<String, Object>> samples) {

        /** 汇总用：最大偏离倍数（无异常时也为当前/基线，用于排序不用于判异）。 */
        public boolean anomalous() { return anomalyCount > 0; }
    }

    /**
     * 对一条时间序列做检测。
     *
     * @param metricLabels 序列标签（Merged metric{...} 字符串，可溯源）
     * @param values       按时间升序的值（null 值由调用方过滤）
     * @return 判定结果；points&lt;4 时 anomalyCount 恒 0（样本不足以支撑四分位）
     */
    public static SeriesVerdict analyze(String metricLabels, List<Double> values) {
        int n = values.size();
        if (n == 0) {
            return new SeriesVerdict(metricLabels, 0, 0, 0, 0, 0, List.of());
        }
        double current = values.get(n - 1);
        if (n < 4) {
            return new SeriesVerdict(metricLabels, median(values), current, 0, n, 0, List.of());
        }
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(Comparator.naturalOrder());
        double q1 = percentile(sorted, 25);
        double q3 = percentile(sorted, 75);
        double iqr = q3 - q1;
        double upperFence = q3 + FENCE_K * iqr;
        double lowerFence = q1 - FENCE_K * iqr;
        double baseline = percentile(sorted, 50);

        List<Map<String, Object>> samples = new ArrayList<>();
        int anomalyCount = 0;
        double maxDev = 0;
        for (int i = 0; i < n; i++) {
            double v = values.get(i);
            boolean out = v > upperFence || v < lowerFence;
            // Tukey 零宽栅栏退化：IQR=0（大部分点同值）时栅栏=同值，任何不同值都算异常——
            // 这正是我们想要的（平稳基线的任何跳变都可见），无需特判。
            if (!out) continue;
            if (baseline != 0 && Math.abs(v / baseline) < MIN_DEVIATION_FACTOR) continue;
            anomalyCount++;
            if (baseline == 0 ? v != 0 : Math.abs(v / baseline) > maxDev) {
                maxDev = baseline == 0 ? Double.POSITIVE_INFINITY : Math.abs(v / baseline);
            }
            if (samples.size() < MAX_SAMPLES_PER_SERIES) {
                samples.add(Map.of(
                        "index", i,
                        "value", round3(v),
                        "position", i == n - 1 ? "latest" : (i == 0 ? "earliest" : "middle")));
            }
        }
        double deviationFactor = baseline == 0
                ? (current == 0 ? 0 : Double.POSITIVE_INFINITY)
                : current / baseline;
        return new SeriesVerdict(metricLabels, round3(baseline), round3(current),
                round4(deviationFactor), n, anomalyCount, List.copyOf(samples));
    }

    /** 线性插值分位数（R-7 口径，与 numpy 默认一致）。 */
    static double percentile(List<Double> sorted, double p) {
        int n = sorted.size();
        if (n == 1) return sorted.get(0);
        double idx = (p / 100.0) * (n - 1);
        int lo = (int) Math.floor(idx);
        int hi = Math.min(n - 1, lo + 1);
        double frac = idx - lo;
        return sorted.get(lo) + (sorted.get(hi) - sorted.get(lo)) * frac;
    }

    private static double median(List<Double> values) {
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(Comparator.naturalOrder());
        return percentile(sorted, 50);
    }

    private static double round3(double v) {
        if (Double.isInfinite(v) || Double.isNaN(v)) return v;
        return Math.round(v * 1000.0) / 1000.0;
    }

    private static double round4(double v) {
        if (Double.isInfinite(v) || Double.isNaN(v)) return v;
        return Double.parseDouble(String.format(Locale.ROOT, "%.4f", v));
    }
}
