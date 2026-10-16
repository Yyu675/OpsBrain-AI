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
        // R-7 线性插值口径逐点钉死（idx = p/100 * (n-1)，取整两邻线性插值）：
        // n=4: P25 idx 0.75 → 0+0.75*(10-0)=7.5（插值区）；
        // n=5: P25 idx 1.0 → 精确落点 10；P50 idx 2.0 → 20；P75 idx 3.0 → 30；
        // n=10: P10 idx 0.9 → 0+0.9*(10-0)=9.0（非整数插值）
        assertEquals(7.5, MetricsAnomalyDetector.percentile(
                List.of(0.0, 10.0, 20.0, 30.0), 25), 1e-9);
        assertEquals(10.0, MetricsAnomalyDetector.percentile(
                List.of(0.0, 10.0, 20.0, 30.0, 40.0), 25), 1e-9);
        assertEquals(20.0, MetricsAnomalyDetector.percentile(
                List.of(0.0, 10.0, 20.0, 30.0, 40.0), 50), 1e-9);
        assertEquals(30.0, MetricsAnomalyDetector.percentile(
                List.of(0.0, 10.0, 20.0, 30.0, 40.0), 75), 1e-9);
        assertEquals(9.0, MetricsAnomalyDetector.percentile(
                List.of(0.0, 10.0, 20.0, 30.0, 40.0, 50.0, 60.0, 70.0, 80.0, 90.0), 10), 1e-9);
    }
}
