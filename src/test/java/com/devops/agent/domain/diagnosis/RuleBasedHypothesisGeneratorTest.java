package com.devops.agent.domain.diagnosis;

import com.devops.agent.domain.diagnosis.HypothesisGenerator.RankedEvidence;
import com.devops.agent.domain.evidence.Evidence;
import com.devops.agent.domain.evidence.EvidenceAggregator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static com.devops.agent.domain.evidence.Evidence.EvidenceStatus.*;

/** 规则基线假设生成器：因果排序 + 置信度引擎联动 + 冲突关联。 */
class RuleBasedHypothesisGeneratorTest {

    private final RuleBasedHypothesisGenerator gen = new RuleBasedHypothesisGenerator();

    private static Evidence aggEv(Evidence.EvidenceStatus st, String type,
                                  Map<String, Object> content, Double rel) {
        return new Evidence(st, type, "t", content, "ref", rel, Instant.now());
    }

    private static EvidenceAggregator.AggregateResult aggSufficient() {
        return new EvidenceAggregator.AggregateResult(
                EvidenceAggregator.Sufficiency.SUFFICIENT,
                new LinkedHashMap<>(Map.of("metrics", 1, "changes", 1, "logs", 1)),
                Map.of(), Map.of(), 0, List.of(), List.of(), "ok");
    }

    @Test
    @DisplayName("强变更在场（rel≥0.6）→ H1 是变更回归，rank 从 1 起且 conf>0")
    void strongChangeRanksFirst() {
        RankedEvidence changes = new RankedEvidence(10L,
                aggEv(SUCCESS, "changes", Map.of("count", 2), 0.9));
        RankedEvidence metrics = new RankedEvidence(11L,
                aggEv(SUCCESS, "metrics", Map.of("anomalyCount", 2), null));
        var out = gen.generate(aggSufficient(), List.of(changes, metrics));

        assertFalse(out.isEmpty());
        assertEquals(1, out.get(0).rank());
        assertTrue(out.get(0).statement().contains("变更"), out.get(0).statement());
        assertTrue(out.get(0).confidence() > 0.5);
        assertTrue(out.get(0).evidenceIds().contains(10L), "支撑证据 id 必须留链");
    }

    @Test
    @DisplayName("无强变更 → 指标症状排第一；无指标异常则让位日志")
    void orderingWithoutChange() {
        RankedEvidence metrics = new RankedEvidence(20L,
                aggEv(SUCCESS, "metrics", Map.of("anomalyCount", 3), null));
        RankedEvidence logs = new RankedEvidence(21L,
                aggEv(SUCCESS, "logs", Map.of("patternCount", 2, "level", "ERROR", "topPattern", "conn <*> timeout"), null));
        var out = gen.generate(aggSufficient(), List.of(metrics, logs));
        assertTrue(out.get(0).statement().contains("指标"), out.get(0).statement());
        assertTrue(out.get(1).statement().contains("日志"), out.get(1).statement());
    }

    @Test
    @DisplayName("零异常+零模式 → 空列表（无物可说不作恶）")
    void nothingToSayReturnsEmpty() {
        RankedEvidence metrics = new RankedEvidence(30L,
                aggEv(SUCCESS, "metrics", Map.of("anomalyCount", 0), null));
        RankedEvidence changes = new RankedEvidence(31L,
                aggEv(SUCCESS, "changes", Map.of("count", 1), 0.3)); // 弱相关不称强
        RankedEvidence logs = new RankedEvidence(32L,
                aggEv(SUCCESS, "logs", Map.of("patternCount", 0, "level", "ERROR"), null));
        var out = gen.generate(aggSufficient(), List.of(metrics, changes, logs));
        assertTrue(out.isEmpty(), "证据面干净时不该瞎编假设：" + out);
    }

    @Test
    @DisplayName("NO_DATA 方向不产假设（「没有」不是线索）")
    void noDataProducesNothing() {
        RankedEvidence changes = new RankedEvidence(40L,
                aggEv(NO_DATA, "changes", Map.of(), null));
        RankedEvidence metrics = new RankedEvidence(41L,
                aggEv(NO_DATA, "metrics", Map.of(), null));
        var out = gen.generate(aggSufficient(), List.of(changes, metrics));
        assertTrue(out.isEmpty());
    }

    @Test
    @DisplayName("Top-3 硬上限；SUGGESTED action 非空（点开即做事）")
    void boundedAndActionable() {
        RankedEvidence changes = new RankedEvidence(50L,
                aggEv(SUCCESS, "changes", Map.of("count", 2), 0.9));
        RankedEvidence metrics = new RankedEvidence(51L,
                aggEv(SUCCESS, "metrics", Map.of("anomalyCount", 2), null));
        RankedEvidence logs = new RankedEvidence(52L,
                aggEv(SUCCESS, "logs", Map.of("patternCount", 1, "level", "ERROR", "topPattern", "dup <*> on db pool"), null));
        var out = gen.generate(aggSufficient(), List.of(changes, metrics, logs));
        assertTrue(out.size() <= 3);
        assertTrue(out.stream().allMatch(h -> h.suggestedAction() != null && !h.suggestedAction().isBlank()));
    }
}
