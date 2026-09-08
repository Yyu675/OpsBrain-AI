package com.devops.agent.application.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 假设校准看板面的确定性钉测（S4-2 数据面，常驻 CI，不依赖库）。
 *
 * <p>口径见 {@link HypothesisCalibrationBoard} 类注释；方向版特别提醒：
 * 空判定集必须 null（「还没人反馈」不是「校准误差 0」）。</p>
 */
@DisplayName("诊断假设置信度校准看板（4-2.1 数据面纯计算）")
class HypothesisCalibrationBoardTest {

    private static Map<String, Object> row(double confidence, String feedback) {
        return Map.of("confidence", confidence, "feedback", feedback);
    }

    @Test
    @DisplayName("完美校准：HELPFUL×2+WRONG×2 同置信度 0.5 → ECE=0、经验正确率=0.5")
    void perfectlyCalibrated() {
        Map<String, Object> out = HypothesisCalibrationBoard.compose(List.of(
                row(0.5, "HELPFUL"), row(0.5, "HELPFUL"),
                row(0.5, "WRONG"), row(0.5, "WRONG")));
        assertEquals(4, out.get("ratedTotal"));
        assertEquals(0.0, (Double) out.get("ece"), 1e-9);
        assertEquals(0.5, (Double) out.get("empiricalAccuracy"), 1e-9);
        assertEquals(0.5, (Double) out.get("meanConfidence"), 1e-9);
    }

    @Test
    @DisplayName("极端过度自信：0.95 全 WRONG → acc=0、ECE=0.95")
    void maximallyOverconfident() {
        Map<String, Object> out = HypothesisCalibrationBoard.compose(List.of(
                row(0.93, "WRONG"), row(0.94, "WRONG"), row(0.98, "WRONG")));
        // 桶均值 0.95，acc 0 → ECE=0.95
        assertEquals(0.0, (Double) out.get("empiricalAccuracy"), 1e-9);
        assertEquals(0.95, (Double) out.get("ece"), 1e-9);
    }

    @Test
    @DisplayName("空判定集 → 三量 null（「还没人反馈」≠「校准误差 0」），桶列长度恒 10")
    void emptyIsNullNotZero() {
        Map<String, Object> out = HypothesisCalibrationBoard.compose(List.of());
        assertEquals(0, out.get("ratedTotal"));
        assertNull(out.get("ece"));
        assertNull(out.get("empiricalAccuracy"));
        assertNull(out.get("meanConfidence"));
        assertEquals(10, ((List<?>) out.get("buckets")).size());
    }

    @Test
    @DisplayName("PARTIAL 豁免但计数，未知反馈值豁免但计数，判定集不受污染")
    void ambiguousExcludedButCounted() {
        Map<String, Object> out = HypothesisCalibrationBoard.compose(List.of(
                row(0.8, "PARTIAL"), row(0.7, "NEUTRAL"), // NEUTRAL 形若新反馈值
                row(0.9, "HELPFUL")));
        assertEquals(1, out.get("ratedTotal"));
        assertEquals(1L, out.get("excludedPartial"));
        assertEquals(1L, out.get("excludedUnknown"));
        // 判定集只剩一条 0.9+HELPFUL → acc=1、gap=0.1 → ECE=0.1
        assertEquals(1.0, (Double) out.get("empiricalAccuracy"), 1e-9);
        assertEquals(0.1, (Double) out.get("ece"), 1e-9);
    }

    @Test
    @DisplayName("越界置信度：看板面豁免计数不崩页（生产面纪律，与评测面硬拒互为表里）")
    void invalidConfidenceSkipped() {
        Map<String, Object> out = HypothesisCalibrationBoard.compose(List.of(
                row(1.3, "HELPFUL"), row(-0.2, "WRONG"), row(0.5, "HELPFUL")));
        assertEquals(1, out.get("ratedTotal"));
        assertEquals(2L, out.get("excludedInvalid"));
    }

    @Test
    @DisplayName("满置信度 1.0 归末桶不越界；桶面 gap 与加权 ECE 归位")
    void lastBinAndWeightedEce() {
        Map<String, Object> out = HypothesisCalibrationBoard.compose(List.of(
                row(1.0, "HELPFUL"), row(1.0, "WRONG"),      // 末桶：conf 1.0 acc 0.5 gap 0.5
                row(0.05, "HELPFUL"), row(0.05, "HELPFUL"))); // 首桶：conf 0.05 acc 1.0 gap 0.95
        // ECE = 0.5×0.5 + 0.5×0.95 = 0.25+0.475 = 0.725
        assertEquals(0.725, (Double) out.get("ece"), 1e-9);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> buckets = (List<Map<String, Object>>) out.get("buckets");
        Map<String, Object> last = buckets.get(9);
        assertEquals(2, last.get("count"));
        assertEquals(1.0, (Double) last.get("meanConfidence"), 1e-9);
        assertEquals(0.5, (Double) last.get("accuracy"), 1e-9);
    }
}
