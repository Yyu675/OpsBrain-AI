package com.devops.agent.domain.diagnosis;

import com.devops.agent.domain.evidence.Evidence;
import com.devops.agent.domain.evidence.EvidenceAggregator;
import com.devops.agent.domain.diagnosis.HypothesisGenerator.RankedEvidence;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 规则基线假设生成器（S2-2 双轨的永远可用一轨，路线图 §6.2 2-2.3/2-2.5）。
 * <p>
 * 推演规则（显式可解释——推理链正是「规则命中路径」的记录）：
 * <ol>
 *   <li><b>变更相关性</b>：changes 方向 SUCCESS 且存在高相关变更（rel ≥ 0.6）
 *       → 「最近一次变更发布引入回归」；</li>
 *   <li><b>指标异常聚集</b>：metrics 方向 anomalyCount > 0
 *       → 「被检出异常面板上的指标族是故障特征」；</li>
 *   <li><b>错误日志主导</b>：logs 方向 ERROR 模式存在
 *       → 「错误日志模式 <em>模板</em> 是直接症状源」；</li>
 *   <li><b>冲突降级</b>：冲突数量会进入置信度惩罚（ConfidenceEngine 统一钳制）；
 *       生成器把冲突证据 id 记入 contradictIds 供点开核对。</li>
 * </ol>
 * </p>
 */
@Component
public class RuleBasedHypothesisGenerator implements HypothesisGenerator {

    private static final double STRONG_CHANGE_THRESHOLD = 0.6;

    @Override
    public List<Hypothesis> generate(EvidenceAggregator.AggregateResult aggregated,
                                     List<RankedEvidence> evidenceWithIds) {
        List<Hypothesis> out = new ArrayList<>(3);

        RankedEvidence changes = firstSuccessOf(evidenceWithIds, Evidence.Type.CHANGES);
        RankedEvidence metrics = firstSuccessOf(evidenceWithIds, Evidence.Type.METRICS);
        RankedEvidence logs = firstSuccessOf(evidenceWithIds, Evidence.Type.LOGS);

        // H1：变更回归（最强优先——变更与故障的因果链证据在运维实践中命中最高）
        if (changes != null && strongChangePresent(changes.evidence())) {
            List<Long> evIds = List.of(changes.id());
            out.add(new Hypothesis(
                    out.size() + 1,
                    "最近一次变更发布（时间窗内）可能引入了回归",
                    "变更方向 SUCCESS 且载体上的最高相关度不言自明（rel="
                            + changes.evidence().relevanceScore() + "）——"
                            + "故障时间窗与变更时间窗高度重叠，是强因果候选。",
                    ConfidenceEngine.compute(aggregated, List.of(changes.evidence())),
                    evIds,
                    contradictIdsOf(aggregated, evidenceWithIds),
                    "核对变更详情（变更人/提交哈希/回滚可执行性），并抓紧时间回滚"
                            + "以缩小影响面——回滚是比深度排查更快的证伪法。",
                    Instant.now()));
            // H2：指标异常
            addMetricHypothesis(out, aggregated, metrics);
            // H3：日志症状
            addLogHypothesis(out, aggregated, logs);
            return bounded(out);
        }

        // 无强变更时的排序：指标症状第一，日志次之
        addMetricHypothesis(out, aggregated, metrics);
        addLogHypothesis(out, aggregated, logs);
        return bounded(out);
    }

    private void addMetricHypothesis(List<Hypothesis> out,
                                     EvidenceAggregator.AggregateResult agg,
                                     RankedEvidence metrics) {
        if (metrics == null || out.size() >= 3) return;
        int anomalyCount = intOf(metrics.evidence().content().get("anomalyCount"));
        if (anomalyCount <= 0) return;
        Object anomalies = metrics.evidence().content().get("anomalies");
        String focus = anomalies == null ? "异常面板" : String.valueOf(anomalies);
        if (focus.length() > 160) focus = focus.substring(0, 160);
        out.add(new Hypothesis(
                out.size() + 1,
                "指标异常面板指向的资源瓶颈是主特征",
                "metrics 方向 SUCCESS 且 anomalyCount=" + anomalyCount
                        + "——异常面板是直接证据；主动核对异常指标的臀腺标签胜过凭感觉猜。",
                ConfidenceEngine.compute(agg, List.of(metrics.evidence())),
                List.of(metrics.id()),
                List.of(),
                "按受累指标的产物下钻：先核查其依赖链（数据库/队列/缓存），再搜条幅样本。",
                Instant.now()));
    }

    private void addLogHypothesis(List<Hypothesis> out,
                                  EvidenceAggregator.AggregateResult agg,
                                  RankedEvidence logs) {
        if (logs == null || out.size() >= 3) return;
        int patternCount = intOf(logs.evidence().content().get("patternCount"));
        Object level = logs.evidence().content().get("level");
        Object top = logs.evidence().content().get("topPattern");
        if (patternCount <= 0 || top == null) return;
        out.add(new Hypothesis(
                out.size() + 1,
                "错误日志模式是直连症状：" + String.valueOf(top).substring(0,
                        Math.min(80, String.valueOf(top).length())),
                "logs 方向 SUCCESS 且已成 ERROR 模式（patternCount=" + patternCount
                        + ", level=" + level + "）——日志症状的因果地位低于变更与指标，"
                        + "但它能马上指引排查的直接对子。",
                ConfidenceEngine.compute(agg, List.of(logs.evidence())),
                List.of(logs.id()),
                List.of(),
                "按日志模式向被影响服务进程索取完整堆栈（相同模式在连接池/SOA 概率最高）。",
                Instant.now()));
    }

    private boolean strongChangePresent(Evidence changes) {
        Double rel = changes.relevanceScore();
        int count = intOf(changes.content().get("count"));
        return count > 0 && rel != null && rel >= STRONG_CHANGE_THRESHOLD;
    }

    private List<Long> contradictIdsOf(EvidenceAggregator.AggregateResult agg,
                                       List<RankedEvidence> all) {
        // 简化规则：被检出冲突的方向的证据 id 记入矛盾名单（点开核对的关联键）。
        // roadmap 2-2.4「有冲突证据时每条冲突 -0.1」由引擎统一钳制，此处只留关联。
        List<Long> ids = new ArrayList<>();
        for (Map<String, Object> c : agg.conflicts()) {
            String left = String.valueOf(c.get("left"));
            for (RankedEvidence re : all) {
                if (left != null && left.startsWith(re.evidence().evidenceType())) {
                    ids.add(re.id());
                }
            }
        }
        return ids;
    }

    private RankedEvidence firstSuccessOf(List<RankedEvidence> all, String type) {
        return all.stream()
                .filter(re -> type.equals(re.evidence().evidenceType())
                        && re.evidence().status() == Evidence.EvidenceStatus.SUCCESS)
                .findFirst().orElse(null);
    }

    private static int intOf(Object v) {
        return v instanceof Number n ? n.intValue() : 0;
    }

    private List<Hypothesis> bounded(List<Hypothesis> out) {
        return List.copyOf(out.subList(0, Math.min(3, out.size())));
    }
}
