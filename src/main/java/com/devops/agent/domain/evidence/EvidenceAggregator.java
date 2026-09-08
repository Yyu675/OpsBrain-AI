package com.devops.agent.domain.evidence;

import com.devops.agent.domain.evidence.Evidence.EvidenceStatus;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 证据聚合器（S1-5，路线图 §5.6）：汇总多方向证据 → 冲突识别 → 充分性判定。
 * <p>
 * 充分性判定（路线图原文规则，一字不改落地）：
 * <pre>
 * SUCCESS ≥ 2 且 FAILED == 0  → SUFFICIENT
 * SUCCESS ≥ 1 且 FAILED == 1  → WEAK（可推理，置信度上限 0.6，建议人工复核）
 * FAILED ≥ 2                  → INSUFFICIENT（终止推理，转人工）
 * 全部 NO_DATA                → INSUFFICIENT（无有效证据）
 * </pre>
 * （规则表述来自路线图 §5.6「证据充分性判定规则（建议）」，为诊断闭环的硬边界。）
 * </p>
 * <p>
 * 冲突消解（§5.6 1-5.4）落地为<b>确定性规则</b>而非模型判断：
 * <ul>
 *   <li><b>C1 变更-指标脱节</b>：变更 SUCCESS 且指标 SUCCESS 但 anomalyCount==0
 *       → 近期有发布/配置变更但关键指标零波动——变化未生效 or 指标 label 不匹配，
 *       降置信并明示（冲突例外：变更本身相关度&lt;0.6 的弱信号不触发）；</li>
 *   <li><b>C2 错误日志-指标脱节</b>：日志 SUCCESS 含 ERROR 级模式但指标零异常
 *       → 指标 label 约定可能失配，提示复核 sourceRef；</li>
 *   <li><b>C3 端口证据自洽</b>：同 direction 已按四态语义先行分流，
 *       NO_DATA 与 FAILED 不互相替换（NO_DATA 不参与冲突——「没有」不是矛盾）。</li>
 * </ul>
 * </p>
 */
public final class EvidenceAggregator {

    /** 关键方向（充分性按这三向统计；拓扑是非关键增强项，阶段 2 接入后对计数透明）。 */
    public static final List<String> KEY_DIRECTIONS = List.of(
            Evidence.Type.METRICS, Evidence.Type.CHANGES, Evidence.Type.LOGS);

    public enum Sufficiency { SUFFICIENT, WEAK, INSUFFICIENT }

    /** 聚合结果（record 字段名即 JSON 键名；诊断链路全程可回放）。 */
    public record AggregateResult(
            Sufficiency sufficiency,
            Map<String, Integer> directionSuccessCount,
            Map<String, Integer> directionNoDataCount,
            Map<String, Integer> directionFailedCount,
            int unavailableCount,
            List<Map<String, Object>> conflicts,
            List<Evidence> evidences,
            String summary) {

        /** 推理层的置信度上限（WEAK=0.6 来自路线图原文）。 */
        public double confidenceCeiling() {
            return switch (sufficiency) {
                case SUFFICIENT -> 1.0;
                case WEAK -> 0.6;
                case INSUFFICIENT -> 0.0;
            };
        }
    }

    private EvidenceAggregator() {}

    public static AggregateResult aggregate(List<Evidence> evidences) {
        Map<String, Integer> success = new LinkedHashMap<>();
        Map<String, Integer> noData = new LinkedHashMap<>();
        Map<String, Integer> failed = new LinkedHashMap<>();
        for (String d : KEY_DIRECTIONS) {
            success.put(d, 0); noData.put(d, 0); failed.put(d, 0);
        }
        int unavailable = 0;

        for (Evidence e : evidences) {
            if (!KEY_DIRECTIONS.contains(e.evidenceType())) continue;
            switch (e.status()) {
                case SUCCESS -> success.merge(e.evidenceType(), 1, Integer::sum);
                case NO_DATA -> noData.merge(e.evidenceType(), 1, Integer::sum);
                case FAILED -> failed.merge(e.evidenceType(), 1, Integer::sum);
                case UNAVAILABLE -> unavailable++;
            }
        }

        int successTotal = success.values().stream().mapToInt(Integer::intValue).sum();
        int failedTotal = failed.values().stream().mapToInt(Integer::intValue).sum();
        int noDataTotal = noData.values().stream().mapToInt(Integer::intValue).sum();

        Sufficiency sufficiency = computeSufficiency(successTotal, failedTotal, noDataTotal, unavailable);

        List<Map<String, Object>> conflicts = new ArrayList<>();
        detectC1(evidences, conflicts);
        detectC2(evidences, conflicts);

        String summary = buildSummary(sufficiency, successTotal, noDataTotal, failedTotal,
                unavailable, conflicts.size());
        return new AggregateResult(sufficiency, success, noData, failed, unavailable,
                List.copyOf(conflicts), List.copyOf(evidences), summary);
    }

    /** 路线图规则原文的逐行翻译（参数就是规则变量，不玩花哨封装）。 */
    static Sufficiency computeSufficiency(int successTotal, int failedTotal,
                                          int noDataTotal, int unavailableTotal) {
        if (failedTotal >= 2) return Sufficiency.INSUFFICIENT;
        if (failedTotal == 1 && successTotal >= 1) return Sufficiency.WEAK;
        if (successTotal >= 2) return Sufficiency.SUFFICIENT;
        if (noDataTotal > 0 && successTotal == 0 && failedTotal == 0) {
            return Sufficiency.INSUFFICIENT; // 全部 NO_DATA（UNAVAILABLE 同理：无任何有效证据）
        }
        if (successTotal == 1 && failedTotal == 0 && noDataTotal == 0 && unavailableTotal == 0) {
            return Sufficiency.WEAK; // 单一 SUCCESS：能推理但明显单薄（路线图未覆盖此态，按不利解释）
        }
        return Sufficiency.WEAK; // 剩余组合（单个 SUCCESS+NO_DATA 等）：偏保守判定
    }

    /** C1：有变更但指标零波动（弱相关度变更不触发——低分变更本来就该忽略）。 */
    private static void detectC1(List<Evidence> evidences, List<Map<String, Object>> conflicts) {
        Evidence changes = firstSuccess(evidences, Evidence.Type.CHANGES);
        Evidence metrics = firstSuccess(evidences, Evidence.Type.METRICS);
        if (changes == null || metrics == null) return;
        int anomalyCount = intOf(metrics.content().get("anomalyCount"));
        int changeCount = intOf(changes.content().get("count"));
        Double maxRel = changes.relevanceScore();
        if (anomalyCount == 0 && changeCount > 0 && (maxRel == null || maxRel >= 0.6)) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("rule", "C1");
            c.put("severity", "medium");
            c.put("description", "时间窗内存在变更但关键指标零波动：变更可能未生效，"
                    + "或指标 query-catalog 的 label 约定与现场不匹配——核对该证据的 sourceRef");
            c.put("left", "changes.count=" + changeCount + ", maxRelevance=" + maxRel);
            c.put("right", "metrics.anomalyCount=0");
            conflicts.add(c);
        }
    }

    /** C2：错误日志模式有、指标零异常。 */
    private static void detectC2(List<Evidence> evidences, List<Map<String, Object>> conflicts) {
        Evidence logs = firstSuccess(evidences, Evidence.Type.LOGS);
        Evidence metrics = firstSuccess(evidences, Evidence.Type.METRICS);
        if (logs == null || metrics == null) return;
        int anomalyCount = intOf(metrics.content().get("anomalyCount"));
        int patternCount = intOf(logs.content().get("patternCount"));
        Object lvl = logs.content().get("level");
        if (anomalyCount == 0 && patternCount > 0 && "ERROR".equals(lvl)) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("rule", "C2");
            c.put("severity", "low");
            c.put("description", "ERROR 级日志模式存在但关键指标零异常："
                    + "指标 label 约定可能失配，建议以日志模式为主证据并复核 metrics 的 sourceRef");
            c.put("left", "logs.patternCount=" + patternCount);
            c.put("right", "metrics.anomalyCount=0");
            conflicts.add(c);
        }
    }

    private static Evidence firstSuccess(List<Evidence> evidences, String type) {
        return evidences.stream()
                .filter(e -> type.equals(e.evidenceType()) && e.status() == EvidenceStatus.SUCCESS)
                .findFirst().orElse(null);
    }

    private static int intOf(Object v) {
        return v instanceof Number n ? n.intValue() : 0;
    }

    private static String buildSummary(Sufficiency s, int success, int noData, int failed,
                                       int unavailable, int conflictCount) {
        String base = switch (s) {
            case SUFFICIENT -> "证据充分（关键方向成功 " + success + " 项）";
            case WEAK -> "证据薄弱（成功 " + success + "、失败 " + failed + "）——置信度上限 0.6，建议人工复核";
            case INSUFFICIENT -> "证据不足（失败 " + failed + "、成功 " + success
                    + "、无数据 " + noData + "）——终止推理，转人工并附取证记录";
        };
        String extra = (unavailable > 0 ? "；未启用源 " + unavailable + " 项" : "")
                + (conflictCount > 0 ? "；检出冲突 " + conflictCount + " 项" : "");
        return base + extra;
    }
}
