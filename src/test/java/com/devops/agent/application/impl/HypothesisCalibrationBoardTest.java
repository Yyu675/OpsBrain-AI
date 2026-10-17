package com.devops.agent.application.impl;

import com.devops.agent.controller.dto.CalibrationBucket;
import com.devops.agent.controller.dto.HypothesisCalibration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 假设校准看板面的确定性钉测；空判定集必须 null 而非伪装成完美校准。 */
@DisplayName("诊断假设置信度校准看板（4-2.1 数据面纯计算）")
class HypothesisCalibrationBoardTest {

    private static HypothesisCalibrationBoard.FeedbackRow row(double confidence, String feedback) {
        return new HypothesisCalibrationBoard.FeedbackRow(confidence, feedback);
    }

    @Test
    @DisplayName("完美校准：HELPFUL×2+WRONG×2 同置信度 0.5 → ECE=0、经验正确率=0.5")
    void perfectlyCalibrated() {
        HypothesisCalibration out = HypothesisCalibrationBoard.compose(List.of(
                row(0.5, "HELPFUL"), row(0.5, "HELPFUL"), row(0.5, "WRONG"), row(0.5, "WRONG")));
        assertEquals(4, out.ratedTotal());
        assertEquals(0.0, out.ece(), 1e-9);
        assertEquals(0.5, out.empiricalAccuracy(), 1e-9);
        assertEquals(0.5, out.meanConfidence(), 1e-9);
    }

    @Test
    @DisplayName("极端过度自信：0.95 全 WRONG → acc=0、ECE=0.95")
    void maximallyOverconfident() {
        HypothesisCalibration out = HypothesisCalibrationBoard.compose(List.of(
                row(0.93, "WRONG"), row(0.94, "WRONG"), row(0.98, "WRONG")));
        assertEquals(0.0, out.empiricalAccuracy(), 1e-9);
        assertEquals(0.95, out.ece(), 1e-9);
    }

    @Test
    @DisplayName("空判定集 → 三量 null，桶列长度恒 10")
    void emptyIsNullNotZero() {
        HypothesisCalibration out = HypothesisCalibrationBoard.compose(List.of());
        assertEquals(0, out.ratedTotal());
        assertNull(out.ece());
        assertNull(out.empiricalAccuracy());
        assertNull(out.meanConfidence());
        assertEquals(10, out.buckets().size());
    }

    @Test
    @DisplayName("PARTIAL 豁免但计数，未知反馈值豁免但计数，判定集不受污染")
    void ambiguousExcludedButCounted() {
        HypothesisCalibration out = HypothesisCalibrationBoard.compose(List.of(
                row(0.8, "PARTIAL"), row(0.7, "NEUTRAL"), row(0.9, "HELPFUL")));
        assertEquals(1, out.ratedTotal());
        assertEquals(1L, out.excludedPartial());
        assertEquals(1L, out.excludedUnknown());
        assertEquals(1.0, out.empiricalAccuracy(), 1e-9);
        assertEquals(0.1, out.ece(), 1e-9);
    }

    @Test
    @DisplayName("越界置信度：看板面豁免计数不崩页")
    void invalidConfidenceSkipped() {
        HypothesisCalibration out = HypothesisCalibrationBoard.compose(List.of(
                row(1.3, "HELPFUL"), row(-0.2, "WRONG"), row(0.5, "HELPFUL")));
        assertEquals(1, out.ratedTotal());
        assertEquals(2L, out.excludedInvalid());
    }

    @Test
    @DisplayName("满置信度 1.0 归末桶不越界；桶面 gap 与加权 ECE 归位")
    void lastBinAndWeightedEce() {
        HypothesisCalibration out = HypothesisCalibrationBoard.compose(List.of(
                row(1.0, "HELPFUL"), row(1.0, "WRONG"), row(0.05, "HELPFUL"), row(0.05, "HELPFUL")));
        assertEquals(0.725, out.ece(), 1e-9);
        CalibrationBucket last = out.buckets().get(9);
        assertEquals(2, last.count());
        assertEquals(1.0, last.meanConfidence(), 1e-9);
        assertEquals(0.5, last.accuracy(), 1e-9);
    }
}
