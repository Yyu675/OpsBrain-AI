package com.devops.agent.application.diagnosis;

import com.devops.agent.application.runtime.AgentState;
import com.devops.agent.application.runtime.AgentStateManager;
import com.devops.agent.application.runtime.AgentStateTransition.TriggerType;
import com.devops.agent.common.context.TraceContext;
import com.devops.agent.domain.biz.repository.DiagnosisEvidenceRepository;
import com.devops.agent.domain.biz.repository.DiagnosisSessionRepository;
import com.devops.agent.domain.evidence.ChangesEvidenceCollector;
import com.devops.agent.domain.evidence.Evidence;
import com.devops.agent.domain.evidence.EvidenceAggregator;
import com.devops.agent.domain.evidence.LogsEvidenceCollector;
import com.devops.agent.domain.evidence.MetricsEvidenceCollector;
import com.devops.agent.domain.evidence.MetricsQueryCatalog;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 诊断编排器（S2-1，路线图 §6.1）：告警 → 取证 → 聚合 → 判定 → 落库。
 * <p>
 * 设计要点（路线图约束逐条落地）：
 * <ul>
 *   <li><b>异步有界线程池</b>：核心 4 / 最大 8 / 队列 100（§6.1 并发控制建议）。
 *       拒绝策略弃用 CallerRunsPolicy——webhook 线程绝不能背诊断（风暴期同步链路
 *       会拖垮服务）。降级语义按路线图原文执行：「仅建单不诊断，记 WARN」；</li>
 *   <li><b>traceId 串联全链</b>：提交时捕获调用方（告警链路）traceId 快照并搬运
 *       到诊断线程；告警 → 工单 → 诊断 → 证据共享同一条 trace（§6.1 验收第 1 条）；</li>
 *   <li><b>取证确定性编排</b>：收集动作不交给模型自主规划（演示路径才允许规划）。
 *       编排器直接按三方向各采一次——告警场景要求「每次告警的取证路径稳定可回放」，
 *       随机性只会稀释可比性；</li>
 *   <li><b>证据不足不推理</b>：聚合判定 INSUFFICIENT 即终止推理并转人工
 *       （§5.6 判定规则的硬执行点）；LLM 推理留待 S2-2；</li>
 *   <li><b>诊断失败不反噬</b>：池内一切异常只记 ERROR 会话 + 状态机 FAILED——
 *       告警入库与自动建单早已脱离风险范围（§6.1 验收第 3 条）。</li>
 * </ul>
 * </p>
 */
@Service
public class DiagnosisOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(DiagnosisOrchestrator.class);

    /** 诊断池参数（路线图 §6.1 原文三个数字只看这里）。
     * 为什么不在 yml：这是「不动时主动守护」的类型参数，不是部署切换面；
     * 且不接入 ManagedExecutors——那边的职责是「存活自律」通用池，诊断池的
     * 降级语义（记 WARN + 不反噬）和它不同族。 */
    private static final int CORE = 4;
    private static final int MAX = 8;
    private static final int QUEUE = 100;
    private static final String COLLECT_RANGE = "30m";

    private final ThreadPoolExecutor pool;
    private final MetricsEvidenceCollector metricsCollector;
    private final ChangesEvidenceCollector changesCollector;
    private final LogsEvidenceCollector logsCollector;
    private final MetricsQueryCatalog catalog;
    private final DiagnosisEvidenceRepository evidenceRepository;
    private final DiagnosisSessionRepository sessionRepository;
    private final AgentStateManager stateManager;
    /** 2-1.5：诊断结果回填工单 AI 分析区。 */
    private final com.devops.agent.domain.biz.service.TicketAiAnalysisService aiAnalysisService;
    /** S2-2：假设生成器（规则基线永远可用；LLM 版可插拔后补，此接口不变）。 */
    private final com.devops.agent.domain.diagnosis.HypothesisGenerator hypothesisGenerator;
    /** S2-2：假设落库（点开假设看证据的关联侧）。 */
    private final com.devops.agent.domain.biz.repository.DiagnosisHypothesisRepository hypothesisRepository;

    public DiagnosisOrchestrator(MetricsEvidenceCollector metricsCollector,
                                 ChangesEvidenceCollector changesCollector,
                                 LogsEvidenceCollector logsCollector,
                                 MetricsQueryCatalog catalog,
                                 DiagnosisEvidenceRepository evidenceRepository,
                                 DiagnosisSessionRepository sessionRepository,
                                 AgentStateManager stateManager,
                                 com.devops.agent.domain.biz.service.TicketAiAnalysisService aiAnalysisService,
                                 com.devops.agent.domain.diagnosis.HypothesisGenerator hypothesisGenerator,
                                 com.devops.agent.domain.biz.repository.DiagnosisHypothesisRepository hypothesisRepository) {
        this.metricsCollector = metricsCollector;
        this.changesCollector = changesCollector;
        this.logsCollector = logsCollector;
        this.catalog = catalog;
        this.evidenceRepository = evidenceRepository;
        this.sessionRepository = sessionRepository;
        this.stateManager = stateManager;
        this.aiAnalysisService = aiAnalysisService;
        this.hypothesisGenerator = hypothesisGenerator;
        this.hypothesisRepository = hypothesisRepository;
        this.pool = new ThreadPoolExecutor(
                CORE, MAX, 30, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(QUEUE),
                new NamedFactory("diagnosis-worker"),
                (r, executor) -> log.warn(
                        "⚠️ [Diagnosis] 诊断池满负荷，降级为仅建单不诊断 | poolSize={}/{} queue={}/{}",
                        executor.getPoolSize(), MAX, executor.getQueue().size(), QUEUE));
    }

    /**
     * 告警触发的诊断提交（2-1.1）。立即返回，不阻塞告警链路。
     *
     * @param alertId  告警 id（去重唯一键；重复触发由唯一索引幂等拒绝）
     * @param ticketId 已建单号（可能为空——诊断视在建单前/后都合法）
     * @param service  服务名
     * @return 诊断 traceId（与告警链路同一 trace，供证据回放）
     */
    public String submit(Long alertId, String ticketId, String service) {
        String traceId = TraceContext.getOrCreate();
        Map<String, String> snapshot = TraceContext.capture();

        stateManager.transition(AgentState.NEW, TriggerType.USER_REQUEST,
                "S2-1 诊断提交 alertId=" + alertId + " service=" + service);

        pool.execute(() -> runDiagnosis(traceId, snapshot, alertId, ticketId, service));
        return traceId;
    }

    private void runDiagnosis(String traceId, Map<String, String> snapshot,
                              Long alertId, String ticketId, String service) {
        try {
            TraceContext.restore(snapshot);
            stateManager.transition(AgentState.CONTEXT_PREPARED, TriggerType.SECURITY_PASSED,
                    "诊断编排开始（异步线程接管）");

            // ── ① 取证（确定性三方向，不依赖模型规划）
            List<Evidence> evidences = collect(service);
            EvidenceAggregator.AggregateResult aggregated = EvidenceAggregator.aggregate(evidences);

            // ── ② 证据落库（traceId 与本会话同一，供回放；同时拿到持久化 id 桥）
            var ranked = persistEvidence(traceId, evidences);
            stateManager.transition(AgentState.EVIDENCE_READY, TriggerType.TOOL_COMPLETED,
                    "证据就绪：" + aggregated.summary());

            // ── ③ 假设生成与落库（S2-2：INSUFFICIENT 已在聚合层硬终止，不产假设）
            String summary = buildFinalSummary(aggregated);
            if (aggregated.sufficiency() != EvidenceAggregator.Sufficiency.INSUFFICIENT) {
                summary = generateAndPersistHypotheses(traceId, aggregated, ranked, summary);
            }

            // ── ④ 判定 + 会话收尾（证据不足硬终止转人工）
            completeSession(traceId, alertId, ticketId, aggregated, summary);
        } catch (Exception ex) {
            log.error("❌ [Diagnosis] 诊断异常 | traceId={} alertId={} | {}",
                    traceId, alertId, ex.getMessage(), ex);
            safeFailSession(traceId, alertId, ex.getMessage());
            safeTransition(AgentState.FAILED, TriggerType.SYSTEM_ERROR,
                    "诊断异常：" + ex.getMessage());
            // 单独兜底，不向上抛。
        } finally {
            TraceContext.clear();
        }
    }

    // ───────────── helpers ─────────────

    private List<Evidence> collect(String service) {
        List<Evidence> evidences = new ArrayList<>(3);
        String metricCsv = String.join(",", catalog.availableMetrics());
        evidences.add(metricsCollector.collect(service, COLLECT_RANGE, metricCsv));
        evidences.add(changesCollector.collect(service, COLLECT_RANGE));
        evidences.add(logsCollector.collect(service, COLLECT_RANGE, "ERROR", null));
        return evidences;
    }

    private List<com.devops.agent.domain.diagnosis.HypothesisGenerator.RankedEvidence> persistEvidence(
            String traceId, List<Evidence> evidences) {
        List<com.devops.agent.domain.diagnosis.HypothesisGenerator.RankedEvidence> ranked = new ArrayList<>();
        for (Evidence e : evidences) {
            try {
                long id = evidenceRepository.save(traceId, "diagnosis-engine", e.evidenceType(),
                        e.status().name(), e.title(),
                        e.toToolPayload(), e.sourceRef(), e.relevanceScore(),
                        e.collectedAt() == null ? null : Timestamp.from(e.collectedAt()));
                ranked.add(new com.devops.agent.domain.diagnosis.HypothesisGenerator.RankedEvidence(id, e));
            } catch (Exception ex) {
                log.warn("⚠️ [Diagnosis] 证据落库失败 | type={} why={}", e.evidenceType(), ex.getMessage());
            }
        }
        return ranked;
    }

    private void completeSession(String traceId, Long alertId, String ticketId,
                                 EvidenceAggregator.AggregateResult aggregated,
                                 String summary) {
        try {
            Long sessionId = sessionRepository.createIfAbsent(traceId, alertId, "diagnosis-engine");
            if (sessionId != null) {
                sessionRepository.complete(sessionId, ticketId,
                        String.valueOf(aggregated.sufficiency()), summary);
            }
            // 2-1.5 工单 AI 分析区回填（§6.1 验收第 1 条的最后一个环）。
            // 置信度为按充分性映射的启发值（S2-2 才做真实校准）：
            //   SUFFICIENT=80 / WEAK=60 / INSUFFICIENT=20（转人工信号而不是自信度）
            if (ticketId != null && !ticketId.isBlank()) {
                int conf = switch (aggregated.sufficiency()) {
                    case SUFFICIENT -> 80;
                    case WEAK -> 60;
                    case INSUFFICIENT -> 20;
                };
                try {
                    aiAnalysisService.save(ticketId,
                            summary + "\n\n（证据回放:traceId=" + traceId + "）",
                            null, null, null, conf, null);
                    log.info("🩺 [Diagnosis] 诊断结论已回填工单 | ticketId={} | sufficiency={} | conf={}",
                            ticketId, aggregated.sufficiency(), conf);
                } catch (Exception ex) {
                    // 回填失败不反噬诊断主流程（同样是附属增值一族）
                    log.warn("⚠️ [Diagnosis] 工单 AI 分析回填失败 | ticketId={} | why={}",
                            ticketId, ex.getMessage());
                }
            }
            AgentState finalState = aggregated.sufficiency() == EvidenceAggregator.Sufficiency.INSUFFICIENT
                    ? AgentState.FAILED : AgentState.DRAFT_READY;
            safeTransition(finalState,
                    aggregated.sufficiency() == EvidenceAggregator.Sufficiency.INSUFFICIENT
                            ? TriggerType.MANUAL_TAKEOVER : TriggerType.DRAFT_GENERATED,
                    summary);
        } catch (Exception ex) {
            log.warn("⚠️ [Diagnosis] 会话收尾失败但证据不丢 traceId={} why={}", traceId, ex.getMessage());
        }
    }

    private void safeFailSession(String traceId, Long alertId, String errorMessage) {
        try {
            Long sessionId = sessionRepository.createIfAbsent(traceId, alertId, "diagnosis-engine");
            if (sessionId != null) {
                sessionRepository.fail(sessionId,
                        errorMessage == null ? "<unknown>" :
                                errorMessage.substring(0, Math.min(255, errorMessage.length())));
            }
        } catch (Exception ex) {
            log.warn("⚠️ [Diagnosis] 会话失败态落库失败 traceId={} why={}", traceId, ex.getMessage());
        }
    }

    private void safeTransition(AgentState toState, TriggerType trigger, String detail) {
        try {
            stateManager.transition(toState, trigger, detail);
        } catch (Exception ignore) {
            // 状态机不兜底——排障依据是会话行，不是状态机单点
        }
    }

    /** 结论计算（SUFFICIENT 走 placeholder 文案，S2-2 才接管真实推理正文）。 */
    private String buildFinalSummary(EvidenceAggregator.AggregateResult agg) {
        return switch (agg.sufficiency()) {
            case SUFFICIENT -> String.format(
                    "证据充分：关键方向成功 %d 项（冲突 %d 项）。"
                            + "本阶段聚焦证据确定性聚合，完整根因推理由 S2-2 接管。",
                    agg.directionSuccessCount().values().stream()
                            .mapToInt(Integer::intValue).sum(),
                    agg.conflicts().size());
            case WEAK -> "证据薄弱：置信度上限 0.6，建议人工复核并补证后推理。";
            case INSUFFICIENT -> "证据不足：关键方向证据缺失，终止推理。建议人工介入并附已获得的取证记录。";
        };
    }

    /** S2-2：调用生成器（任一实现）出 Top-3，落库并把 Top-1 并入摘要。
     *  生成/落库失败不反噬——摘要只退化为「无数假设版」，诊断主流程照走。 */
    private String generateAndPersistHypotheses(
            String traceId,
            EvidenceAggregator.AggregateResult aggregated,
            List<com.devops.agent.domain.diagnosis.HypothesisGenerator.RankedEvidence> ranked,
            String summary) {
        try {
            var hypotheses = hypothesisGenerator.generate(aggregated, ranked);
            if (hypotheses == null || hypotheses.isEmpty()) {
                return summary + "；本证据面无可落地假设（规则基线判为无物可说）";
            }
            for (var h : hypotheses) {
                hypothesisRepository.save(traceId, h);
            }
            return summary + "；Top-1 假设：" + hypotheses.get(0).statement()
                    + "（置信度 " + String.format("%.2f", hypotheses.get(0).confidence()) + "）";
        } catch (Exception ex) {
            log.warn("⚠️ [Diagnosis] 假设生成/落库失败（摘要退化为默认版） | traceId={} why={}",
                    traceId, ex.getMessage());
            return summary;
        }
    }

    /** 供告警控制器在决策时感知诊断池压力（「需要人工吗」另一个维度的告警）。 */
    public int pendingCount() {
        return pool.getQueue().size();
    }

    @PreDestroy
    public void shutdown() {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(10, TimeUnit.SECONDS)) {
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /** 诊断池线程使用命名前缀（便于排障日志识别）。 */
    static class NamedFactory implements java.util.concurrent.ThreadFactory {
        private final String prefix;
        private final AtomicInteger counter = new AtomicInteger();

        NamedFactory(String prefix) { this.prefix = prefix; }

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, prefix + "-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    }
}
