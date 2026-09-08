package com.devops.agent.eval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分桶校准器的确定性钉测（常驻 CI，不依赖真窗）。
 *
 * <p>4-2.4「校准后 ECE 有下降」不能只靠口号：本类把「什么样的校准集
 * 必然带来 ECE 下降」构造出来钉死，真窗首开日直接复用同一公式。</p>
 */
@DisplayName("分桶置信度校准（4-2.3 纯计算面）")
class BucketCalibratorTest {

    private static EceMetrics.CalibrationPair p(double conf, boolean correct) {
        return new EceMetrics.CalibrationPair(conf, correct);
    }

    // ==================== 主映射语义 ====================

    @Test
    @DisplayName("过度自信被拉回：bin 报 0.9 而历史只对一半 → 修正为 0.5")
    void overconfidencePulledDown() {
        List<EceMetrics.CalibrationPair> calib = List.of(
                p(0.92, true), p(0.94, false), p(0.95, false),
                p(0.9, true), p(0.93, false), p(0.96, false));
        // 6 条全落 [0.9,1.0] bin，对 2 错 4 → acc=1/3
        assertEquals(1.0 / 3.0, BucketCalibrator.corrected(calib, 10, 0.9), 1e-9);
    }

    @Test
    @DisplayName("不自信被抬上：bin 报 0.2 而历史全对 → 修正为 1.0")
    void underconfidenceLifted() {
        List<EceMetrics.CalibrationPair> calib = List.of(
                p(0.1, true), p(0.2, true), p(0.15, true));
        assertEquals(1.0, BucketCalibrator.corrected(calib, 10, 0.1), 1e-9);
    }

    @Test
    @DisplayName("空 bin 回退全局正确率（有据估计），不回退原值")
    void emptyBinFallsBackToGlobal() {
        // 校准集只在低 bin 有样本：acc 全局 = 1/4
        List<EceMetrics.CalibrationPair> calib = List.of(
                p(0.1, true), p(0.2, false), p(0.3, false), p(0.4, false));
        assertEquals(0.25, BucketCalibrator.corrected(calib, 10, 0.95), 1e-9);
    }

    @Test
    @DisplayName("空校准集 → 恒等映射（没数据不许假装会校准）")
    void emptyCalibrationIsIdentity() {
        assertEquals(0.83, BucketCalibrator.corrected(List.of(), 10, 0.83), 1e-9);
        assertEquals(0.83, BucketCalibrator.corrected(null, 10, 0.83), 1e-9);
    }

    @Test
    @DisplayName("待修正置信度越界即抛")
    void outOfRangeRejected() {
        List<EceMetrics.CalibrationPair> calib = List.of(p(0.5, true));
        assertThrows(IllegalArgumentException.class,
                () -> BucketCalibrator.corrected(calib, 10, 1.1));
    }

    // ==================== 4-2.4 前后对比证据面 ====================

    @Test
    @DisplayName("校准后 ECE 必然下降（构造：自信与正确率系统偏离的评测集）")
    void recalibrationReducesEce() {
        // 校准分布：两个 bin，bin[0.9] 历史 acc=0.5，bin[0.1] 历史 acc=1.0
        List<EceMetrics.CalibrationPair> calib = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            calib.add(p(0.9, i % 2 == 0));   // [0.9,1.0]: 5/10 对
            calib.add(p(0.1, true));          // [0.0,0.1)? 0.1 落 bin1 —— 用 0.05 归 bin0
        }
        // 评测集同分布：自信自报严重失真
        List<EceMetrics.CalibrationPair> eval = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            eval.add(p(0.9, i % 2 == 0));
            eval.add(p(0.05, true));
        }
        double eceBefore = EceMetrics.ece(eval, 10);
        double eceAfter = EceMetrics.ece(BucketCalibrator.recalibrate(calib, eval, 10), 10);
        assertTrue(eceBefore > 0.3, "构造的前置信度失真应足够大，实测=" + eceBefore);
        assertTrue(eceAfter < eceBefore, "校准后 ECE 必须下降：before=" + eceBefore + " after=" + eceAfter);
    }

    @Test
    @DisplayName("recalibrate 保 correct 原值、空评测集返空")
    void recalibrateKeepsCorrect() {
        List<EceMetrics.CalibrationPair> calib = List.of(p(0.9, false), p(0.9, false));
        List<EceMetrics.CalibrationPair> out = BucketCalibrator.recalibrate(calib, List.of(p(0.9, true)), 10);
        assertEquals(1, out.size());
        assertEquals(0.0, out.get(0).confidence(), 1e-9); // acc=0 → 修正到 0
        assertTrue(out.get(0).correct());                  // correct 不动
        assertTrue(BucketCalibrator.recalibrate(calib, List.of(), 10).isEmpty());
    }
}
