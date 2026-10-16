package com.devops.agent.domain.diagnosis;

import com.devops.agent.domain.evidence.Evidence;
import com.devops.agent.domain.evidence.EvidenceAggregator;

import java.util.List;

/**
 * 置信度引擎（S2-2，路线图 §6.2 务实版公式，原文逐项落地）：
 * <pre>
 * base = 0.5
 * + 0.15 × (证据方向数 / 总方向数)
 * + 0.15 × (高相关性证据占比)
 * - 0.10 × 冲突证据数
 * - 0.20 × (证据不足)
 * clamp 到 [0.1, 0.95]
 * </pre>
 * <p>
 * 附加钳制（路线图 2-2.4 原文）：证据不足时（WEAK）置信度上限 0.6；
 * INSUFFICIENT 不出现假设（由编排器在聚合阶段就终止推理）。
 * </p>
 */
public final class ConfidenceEngine {

    private static final double BASE = 0.5;
    private static final double DIR_WEIGHT = 0.15;
    private static final double REL_WEIGHT = 0.15;
    private static final double CONFLICT_PENALTY = 0.10;
    private static final double INSUFF_PENALTY = 0.20;
    private static final double FLOOR = 0.1;
    private static final double CEIL = 0.95;
    private static final double HIGH_REL_THRESHOLD = 0.8;
    private static final double INSUFFICIENT_HARD_CAP = 0.6;

    private ConfidenceEngine() {}

    /**
     * 按聚合结果计算本条假设的置信度。
     *
     * @param aggregated 聚合结果（方向成功计数 + 冲突数 + 充分性）
     * @param evidenceIds 本假设引用的证据（用于高相关占比计算）
     * @return 钳制后的置信度
     */
    public static double compute(EvidenceAggregator.AggregateResult aggregated,
                                 List<Evidence> referenced) {
        int successDirs = aggregated.directionSuccessCount().values().stream()
                .mapToInt(Integer::intValue).sum();
        int totalDirs = EvidenceAggregator.KEY_DIRECTIONS.size();

        double highRelRatio = highRelRatio(referenced);
        int conflictCount = aggregated.conflicts().size();
        boolean weak = aggregated.sufficiency() == EvidenceAggregator.Sufficiency.WEAK;

        double value = BASE
                + DIR_WEIGHT * ((double) successDirs / totalDirs)
                + REL_WEIGHT * highRelRatio
                - CONFLICT_PENALTY * conflictCount
                - (weak ? INSUFF_PENALTY : 0.0);

        value = clamp(value, FLOOR, CEIL);
        if (weak) value = Math.min(value, INSUFFICIENT_HARD_CAP);
        return value;
    }

    /** WEAK 态的置信度硬上限（2-2.5：证据不足时 ≤0.6）。 */
    public static double weakCeiling() {
        return INSUFFICIENT_HARD_CAP;
    }

    private static double highRelRatio(List<Evidence> referenced) {
        if (referenced == null || referenced.isEmpty()) return 0.0;
        long withScore = referenced.stream().filter(e -> e.relevanceScore() != null).count();
        if (withScore == 0) {
            // 没有相关度信号的方向（metrics/logs）不计入分母，避免拉低公式分位
            return 0.0;
        }
        long high = referenced.stream()
                .filter(e -> e.relevanceScore() != null && e.relevanceScore() >= HIGH_REL_THRESHOLD)
                .count();
        return (double) high / withScore;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
