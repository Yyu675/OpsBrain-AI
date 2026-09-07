package com.devops.agent.domain.evidence;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * IQR 检测器纯函数测试（S1-1 的判异红线全部钉在这里）。
 */
class MetricsAnomalyDetectorTest {

    @Test
    void emptySeriesYieldsZeroVerdict() {
        var v = MetricsAnomalyDetector.analyze("{}", List.of());
        assertEquals(0, v.anomalyCount());
        assertFalse(v.anomalous());
        assertEquals(0, v.pointCount());
    }

    @Test
    void shortSeriesNeverJudged() {
        var v = MetricsAnomalyDetector.analyze("{}", List.of(1.0, 2.0, 100.0));
        assertEquals(0, v.anomalyCount(), "样本<4 不足以支撑四分位，静默不判异");
        assertEquals(100.0, v.currentValue());
    }

    @Test
    void stableSeriesNoAnomaly() {
        List<Double> vals = new ArrayList<>();
        for (int i = 0; i < 30; i++) vals.add(50.0 + (i % 3)); // 50~52 稳定抖动
        var v = MetricsAnomalyDetector.analyze("{}", vals);
        assertEquals(0, v.anomalyCount());
        assertTrue(v.deviationFactor() > 0.9 && v.deviationFactor() < 1.1);
    }

    @Test
    void singleSpikeIsDetected() {
        var base = MetricsAnomalyDetector.analyze("{}", buildFlatThenSpike());
        assertTrue(base.anomalyCount() >= 1, "5.0 尖峰必须被 IQR 栅栏捕获");
        assertTrue(base.deviationFactor() > 2, "末值/基线偏离倍数应显著");
    }

    private static List<Double> buildFlatThenSpike() {
        List<Double> vals = new ArrayList<>();
        for (int i = 0; i < 29; i++) vals.add(0.10);
        vals.add(5.0); // 末值尖峰
        return vals;
    }

    @Test
    void zeroBaselineRiseFromZeroCounts() {
        List<Double> vals = new ArrayList<>();
        for (int i = 0; i < 30; i++) vals.add(0.0); // 错误率常态 0
        vals.set(15, 0.02); // 从零到有
        var v = MetricsAnomalyDetector.analyze("{}", vals);
        assertEquals(1, v.anomalyCount(), "基线=0 时『从零到有』即偏离无穷，必须报");
    }

    @Test
    void tinyDeviationIsNoiseNotAnomaly() {
        List<Double> vals = new ArrayList<>();
        for (int i = 0; i < 30; i++) vals.add(100.0);
        vals.set(10, 105.0); // 5% 偏离 < 20% 阈
        // IQR=0（几乎全同值）→ 栅栏=100，105 出栅栏但 <1.2x 抑制为噪音
        var v = MetricsAnomalyDetector.analyze("{}", vals);
        assertEquals(0, v.anomalyCount(), "低于偏离阈值的出栅栏值是噪音");
    }

    @Test
    void percentileMatchesR7Definition() {
        // R-7 线性插值：n=4 时 P25=idx 0.75 → v0+0.75*(v1-v0)
        double p25 = MetricsAnomalyDetector.percentile(List.of(0.0, 10.0, 20.0, 30.0), 25);
        assertEquals(7.5, p25, 1e-9);
        double p50 = MetricsAnomalyDetector.percentile(List.of(0.0, 10.0, 20.0, 30.0), 50);
        assertEquals(15.0, p50, 1e-9);
    }
}
