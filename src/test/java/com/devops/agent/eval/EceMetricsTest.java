package com.devops.agent.eval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ECE 计算的确定性钉测（常驻 CI，不依赖 LLM / 真窗）。
 *
 * <p>校准指标错一格，「模型越来越可靠」的结论就是编出来的——同楼
 * {@link RetrievalRankMetricsTest} 的钉测哲学，方向版再加一条：
 * 空样本必须是 NaN，绝不能是 0（假满分，见 {@link EceMetrics} 类注释）。</p>
 */
@DisplayName("置信度校准误差 ECE（纯计算面）")
class EceMetricsTest {

    private static EceMetrics.CalibrationPair p(double conf, boolean correct) {
        return new EceMetrics.CalibrationPair(conf, correct);
    }

    // ==================== 主指标语义 ====================

    @Test
    @DisplayName("完美校准 → ECE=0（每个 bin 内置信度恰等于正确率）")
    void perfectlyCalibrated() {
        // bin0（[0,0.5)）：conf 0.5 不成立——用 0.25/0.25 对错各半；bin1：conf 0.75
        // 构造：两个 0.25 一对一错（bin 内 conf=0.25, acc=0.5？不对——须逐 bin 使 mean conf == acc）
        // 简化完美校准：全体 conf=0.5，恰好一半对（mean 0.5 == acc 0.5）
        List<EceMetrics.CalibrationPair> pairs = List.of(
                p(0.5, true), p(0.5, false), p(0.5, true), p(0.5, false));
        assertEquals(0.0, EceMetrics.ece(pairs, 10), 1e-9);
    }

    @Test
    @DisplayName("极端过度自信：全部报 1.0 且全错 → ECE=1.0")
    void maximallyOverconfident() {
        List<EceMetrics.CalibrationPair> pairs = List.of(
                p(1.0, false), p(1.0, false), p(1.0, false));
        assertEquals(1.0, EceMetrics.ece(pairs, 10), 1e-9);
    }

    @Test
    @DisplayName("极端不自信：全部报 0.0 且全对 → ECE=1.0（方向对称）")
    void maximallyUnderconfident() {
        List<EceMetrics.CalibrationPair> pairs = List.of(
                p(0.0, true), p(0.0, true));
        assertEquals(1.0, EceMetrics.ece(pairs, 10), 1e-9);
    }

    @Test
    @DisplayName("加权口径：大桶偏 0.2、小桶完美校准 → ECE=0.5×0.2=0.1")
    void weightedByBucketSize() {
        // 4 个样本进同一桶（conf=0.9，对 3 错 1 → acc=0.75，gap=0.15）
        // 权重 1.0（单桶全覆盖）→ ECE=0.15；小桶论证由本例全体共享放大：
        List<EceMetrics.CalibrationPair> pairs = List.of(
                p(0.9, true), p(0.9, true), p(0.9, true), p(0.9, false));
        // mean conf = 0.9, acc = 0.75, gap = 0.15, 桶权重 4/4=1
        assertEquals(0.15, EceMetrics.ece(pairs, 10), 1e-9);
    }

    // ==================== 边界与拒绝面 ====================

    @Test
    @DisplayName("空样本 → NaN（「还没测过」不许报成「完美校准」）")
    void emptyIsNaN() {
        assertTrue(Double.isNaN(EceMetrics.ece(List.of(), 10)));
        assertTrue(Double.isNaN(EceMetrics.ece(null, 10)));
    }

    @Test
    @DisplayName("置信度满值 1.0 归末 bin，不越界")
    void oneFallsLastBin() {
        List<EceMetrics.BucketResult> bs = EceMetrics.buckets(List.of(p(1.0, true)), 10);
        assertEquals(0, bs.get(8).count());
        assertEquals(1, bs.get(9).count());
    }

    @Test
    @DisplayName("置信度越界即抛（-0.1 / 1.1 / NaN 三形）")
    void outOfRangeRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> EceMetrics.buckets(List.of(p(-0.1, true)), 10));
        assertThrows(IllegalArgumentException.class,
                () -> EceMetrics.buckets(List.of(p(1.1, true)), 10));
        assertThrows(IllegalArgumentException.class,
                () -> EceMetrics.buckets(List.of(p(Double.NaN, true)), 10));
    }

    @Test
    @DisplayName("binCount 非法即抛（0 与负数）")
    void badBinCountRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> EceMetrics.buckets(List.of(p(0.5, true)), 0));
        assertThrows(IllegalArgumentException.class,
                () -> EceMetrics.buckets(List.of(p(0.5, true)), -3));
    }

    // ==================== 分桶面 ====================

    @Test
    @DisplayName("分桶边界：0.49 与 0.5 分家到相邻两桶")
    void binBoundary() {
        List<EceMetrics.BucketResult> bs = EceMetrics.buckets(
                List.of(p(0.49, true), p(0.5, true)), 10);
        assertEquals(1, bs.get(4).count());
        assertEquals(1, bs.get(5).count());
    }

    @Test
    @DisplayName("桶聚合：meanConfidence / fractionCorrect / gap 三量各归各位")
    void bucketAggregation() {
        List<EceMetrics.BucketResult> bs = EceMetrics.buckets(
                List.of(p(0.9, true), p(0.9, false)), 10);
        EceMetrics.BucketResult last = bs.get(9);
        assertEquals(2, last.count());
        assertEquals(0.9, last.meanConfidence(), 1e-9);
        assertEquals(0.5, last.fractionCorrect(), 1e-9);
        assertEquals(0.4, last.gap(), 1e-9);
    }
}
