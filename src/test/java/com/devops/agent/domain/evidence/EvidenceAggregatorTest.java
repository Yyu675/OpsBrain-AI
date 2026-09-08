package com.devops.agent.domain.evidence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static com.devops.agent.domain.evidence.Evidence.EvidenceStatus.*;
import static org.junit.jupiter.api.Assertions.*;

/** 充分性判定（路线图规则逐行）+ 冲突规则 C1/C2 的确定性断言。 */
class EvidenceAggregatorTest {

    private static Evidence ev(Evidence.EvidenceStatus st, String type, Map<String, Object> content) {
        return new Evidence(st, type, "t", content, "ref", null, Instant.now());
    }

    private static Evidence ev(Evidence.EvidenceStatus st, String type, Map<String, Object> content, Double rel) {
        return new Evidence(st, type, "t", content, "ref", rel, Instant.now());
    }

    @Test
    @DisplayName("规则一：SUCCESS≥2 且 FAILED=0 → SUFFICIENT")
    void sufficientWhenTwoSuccess() {
        var r = EvidenceAggregator.aggregate(List.of(
                ev(SUCCESS, "metrics", Map.of("anomalyCount", 1)),
                ev(SUCCESS, "changes", Map.of("count", 0), 0.1),   // 弱变更不触发 C1（rel<0.6 且 count=0）
                ev(NO_DATA, "logs", Map.of())));
        assertSame(EvidenceAggregator.Sufficiency.SUFFICIENT, r.sufficiency());
        assertEquals(1.0, r.confidenceCeiling());
    }

    @Test
    @DisplayName("规则二：SUCCESS≥1 且 FAILED=1 → WEAK（置信度上限 0.6）")
    void weakWhenOneFailOneSuccess() {
        var r = EvidenceAggregator.aggregate(List.of(
                ev(SUCCESS, "metrics", Map.of("anomalyCount", 0)),
                ev(FAILED, "changes", Map.of())));
        assertSame(EvidenceAggregator.Sufficiency.WEAK, r.sufficiency());
        assertEquals(0.6, r.confidenceCeiling());
    }

    @Test
    @DisplayName("规则三：FAILED≥2 → INSUFFICIENT（终止推理）")
    void insufficientWhenTwoFailed() {
        var r = EvidenceAggregator.aggregate(List.of(
                ev(FAILED, "metrics", Map.of()),
                ev(FAILED, "logs", Map.of())));
        assertSame(EvidenceAggregator.Sufficiency.INSUFFICIENT, r.sufficiency());
        assertEquals(0.0, r.confidenceCeiling());
        assertTrue(r.summary().contains("证据不足"));
    }

    @Test
    @DisplayName("规则四：全部 NO_DATA → INSUFFICIENT")
    void insufficientWhenAllNoData() {
        var r = EvidenceAggregator.aggregate(List.of(
                ev(NO_DATA, "metrics", Map.of()),
                ev(NO_DATA, "changes", Map.of())));
        assertSame(EvidenceAggregator.Sufficiency.INSUFFICIENT, r.sufficiency());
    }

    @Test
    @DisplayName("C1：有变更（高相关）+指标零异常 → 冲突被列出并降置信")
    void c1ChangeWithoutMetricWaveIsConflict() {
        var changes = ev(SUCCESS, "changes", Map.of("count", 2), 1.0);
        var metrics = ev(SUCCESS, "metrics", Map.of("anomalyCount", 0));
        var r = EvidenceAggregator.aggregate(List.of(changes, metrics));
        assertEquals(1, r.conflicts().size());
        assertEquals("C1", r.conflicts().get(0).get("rule"));
    }

    @Test
    @DisplayName("C1 不触发：弱相关变更（rel<0.6 的 2h 前发布)不该制造矛盾感")
    void c1SkipsWeakRelevanceChanges() {
        var changes = ev(SUCCESS, "changes", Map.of("count", 2), 0.4);
        var metrics = ev(SUCCESS, "metrics", Map.of("anomalyCount", 0));
        var r = EvidenceAggregator.aggregate(List.of(changes, metrics));
        assertTrue(r.conflicts().isEmpty());
    }

    @Test
    @DisplayName("C2：ERROR 日志模式有+指标零异常 → 冲突（label 失配提示）")
    void c2ErrorLogsWithoutMetricWaveIsConflict() {
        var logs = ev(SUCCESS, "logs", Map.of("patternCount", 3, "level", "ERROR"));
        var metrics = ev(SUCCESS, "metrics", Map.of("anomalyCount", 0));
        var r = EvidenceAggregator.aggregate(List.of(logs, metrics));
        assertEquals(1, r.conflicts().size());
        assertEquals("C2", r.conflicts().get(0).get("rule"));
    }

    @Test
    @DisplayName("NO_DATA 不参与冲突（「没有」不是矛盾）")
    void noDataNeverConflicts() {
        var r = EvidenceAggregator.aggregate(List.of(
                ev(NO_DATA, "changes", Map.of()),
                ev(SUCCESS, "metrics", Map.of("anomalyCount", 0))));
        assertTrue(r.conflicts().isEmpty());
    }

    @Test
    @DisplayName("UNAVAILABLE 单独成计：不伪装成 FAILED 拉低评级")
    void unavailableCountedSeparately() {
        var r = EvidenceAggregator.aggregate(List.of(
                ev(SUCCESS, "metrics", Map.of("anomalyCount", 1)),
                ev(SUCCESS, "logs", Map.of("patternCount", 1, "level", "ERROR")),
                ev(UNAVAILABLE, "changes", Map.of())));
        assertEquals(1, r.unavailableCount());
        assertSame(EvidenceAggregator.Sufficiency.SUFFICIENT, r.sufficiency(),
                "UNAVAILABLE（未启用=部署形态）不转成 FAILED 计数");
    }
}
