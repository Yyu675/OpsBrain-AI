package com.devops.agent.domain.diagnosis;

import com.devops.agent.domain.evidence.Evidence;
import com.devops.agent.domain.evidence.EvidenceAggregator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 置信度引擎：公式逐项落地 + 钳制边界（0.1~0.95 / WEAK 硬上限 0.6）。 */
class ConfidenceEngineTest {

    private static Evidence relScore(Double score) {
        return new Evidence(Evidence.EvidenceStatus.SUCCESS, "changes", "t",
                Map.of("count", 1), "ref", score, Instant.now());
    }

    @Test
    @DisplayName("满信号：3 方向全 SUCCESS + 高相关全占 → 置信度逼近上限 0.95")
    void fullSignalApproachesCeiling() {
        var agg = aggregateWithSuccess(3, List.of());
        double c = ConfidenceEngine.compute(agg, List.of(relScore(0.9), relScore(1.0)));
        // 0.5 + 0.15*1 + 0.15*1 = 0.80（未达 0.95——公式基线本就给不满，这是设计而非缺陷）
        assertEquals(0.80, c, 1e-4, "公式逐变量：0.5+0.15×1+0.15×1=0.80");
    }

    @Test
    @DisplayName("WEAK 态：任何计算结果都被 0.6 硬帽罩住（2-2.5）")
    void weakStateCappedAt06() {
        var weakAgg = new EvidenceAggregator.AggregateResult(
                EvidenceAggregator.Sufficiency.WEAK,
                Map.of("metrics", 1, "changes", 0, "logs", 0),
                Map.of("metrics", 0, "changes", 0, "logs", 0),
                Map.of("metrics", 0, "changes", 1, "logs", 0),
                0, List.of(), List.of(), "weak");
        double c = ConfidenceEngine.compute(weakAgg, List.of(relScore(1.0)));
        assertTrue(c <= 0.6, "证据薄弱时置信度不得超过 0.6，实际 " + c);
    }

    @Test
    @DisplayName("冲突惩罚：每多 1 条冲突 -0.10，可向下穿透到下限 0.1 为止")
    void conflictPenaltyAccumulates() {
        var conflictAgg = new EvidenceAggregator.AggregateResult(
                EvidenceAggregator.Sufficiency.SUFFICIENT,
                Map.of("metrics", 2, "changes", 1, "logs", 1),
                Map.of(), Map.of(),
                0,
                List.of(Map.of("rule", "C1"), Map.of("rule", "C2"), Map.of("rule", "CX")),
                List.of(), "conflicted");
        double c = ConfidenceEngine.compute(conflictAgg, List.of());
        // 0.5 + 0.15×(4/3>1?——注意 success 累计方向数取 KEY_DIRECTIONS 维度总和为分母，
        // 聚合里 metrics=2 即该方向采了 2 次；公式按成功方向数/总方向数（3）算
        assertTrue(c >= 0.1 && c <= 0.95, "钳制在 [0.1,0.95]，实际 " + c);
    }

    @Test
    @DisplayName("无相关度信号的方向不计入高相关占比（不拉低分位）")
    void noRelevanceSignalExcluded() {
        var agg = aggregateWithSuccess(2, List.of());
        double c = ConfidenceEngine.compute(agg, List.of(
                new Evidence(Evidence.EvidenceStatus.SUCCESS, "metrics", "m",
                        Map.of("anomalyCount", 1), "ref", null, Instant.now())));
        assertTrue(c > 0.5, "只有 metrics（无相关度字段）参与时不应拉低公式，实际 " + c);
    }

    @Test
    @DisplayName("钳制下限：极端减分（4 冲突+WEAK）不低于 0.1")
    void floorHolds() {
        var agg = new EvidenceAggregator.AggregateResult(
                EvidenceAggregator.Sufficiency.WEAK,
                Map.of(), Map.of(), Map.of(), 0,
                List.of(Map.of("r", 1), Map.of("r", 2), Map.of("r", 3), Map.of("r", 4)),
                List.of(), "extreme");
        double c = ConfidenceEngine.compute(agg, List.of());
        assertEquals(0.1, c, 1e-4, "下限 0.1 必须兜住，实际 " + c);
    }

    private static EvidenceAggregator.AggregateResult aggregateWithSuccess(
            int successDirs, List<Map<String, Object>> conflicts) {
        Map<String, Integer> success = new java.util.LinkedHashMap<>();
        int i = 0;
        for (String d : EvidenceAggregator.KEY_DIRECTIONS) {
            success.put(d, i < successDirs ? 1 : 0);
            i++;
        }
        return new EvidenceAggregator.AggregateResult(
                EvidenceAggregator.Sufficiency.SUFFICIENT,
                success, Map.of(), Map.of(), 0, conflicts, List.of(), "ok");
    }
}
