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
import com.devops.agent.domain.evidence.KnowledgeEvidenceCollector;
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
    /** P0 2026-09-24：诊断接入知识库——检索命中作为 KNOWLEDGE 证据进入推理与回流 */
    private final KnowledgeEvidenceCollector knowledgeCollector;
    private final MetricsQueryCatalog catalog;
    private final DiagnosisEvidenceRepository evidenceRepository;
    private final DiagnosisSessionRepository sessionRepository;
    private final AgentStateManager stateManager;
    /** 2-1.5：诊断结果回填工单 AI 分析区。 */
    private final com.devops.agent.domain.biz.service.TicketAiAnalysisService aiAnalysisService;

    /**
     * 工单活动流写入（2026-09-30 方案 B）：每次诊断完成在工单时间线留一条
     * 取证记录——分析区可能被「近期已有版本」跳过或归档，活动流是
     * 「AI 查了什么、查到什么」在工单侧的唯一常驻入口。
     * 字段注入 required=false：既有测试用固定构造直配，缺装时留痕跳过，
     * 诊断主流程与回填不受影响（附属增值一族护身）。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.devops.agent.domain.biz.service.TicketService ticketService;
    /** S2-2：假设生成器（规则基线永远可用；LLM 版可插拔后补，此接口不变）。 */
    private final com.devops.agent.domain.diagnosis.HypothesisGenerator hypothesisGenerator;
    /** S2-2：假设落库（点开假设看证据的关联侧）。 */
    private final com.devops.agent.domain.biz.repository.DiagnosisHypothesisRepository hypothesisRepository;
    /** 2-1.6：诊断完成 WebSocket 推送（前端诊断页实时刷新）。 */
    private final com.devops.agent.domain.alert.service.AlertWebSocketNotifier wsNotifier;
    /** 2-1.6：钉钉通知（INSUFFICIENT 时 urgent——人工介入是必须被看到的事）。 */
    private final com.devops.agent.domain.notify.Notifier notifier;
    /** 知识检索的查询锚点：按 alertId 回查告警名，让知识检索带上症状信号而不只是服务名 */
    private final com.devops.agent.domain.alert.repository.AlertRepository alertRepository;

    /** labels_json 解析（V11 锚点增强）。字段注入：缺装时锚点标签退化为空 Map。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    /**
     * 跨服务依赖拓扑（轻量版）：{@code devops.diagnosis.service-dependencies}。
     * 格式 {@code order-service:redis-server,mysql-server;payment-service:mysql-server}。
     * 默认空串 = 不配依赖、不收集拓扑证据，诊断行为与此前完全一致（零风险默认关）。
     */
    @org.springframework.beans.factory.annotation.Value("${devops.diagnosis.service-dependencies:}")
    private String serviceDependenciesConfig;

    public DiagnosisOrchestrator(MetricsEvidenceCollector metricsCollector,
                                 ChangesEvidenceCollector changesCollector,
                                 LogsEvidenceCollector logsCollector,
                                 KnowledgeEvidenceCollector knowledgeCollector,
                                 MetricsQueryCatalog catalog,
                                 DiagnosisEvidenceRepository evidenceRepository,
                                 DiagnosisSessionRepository sessionRepository,
                                 AgentStateManager stateManager,
                                 com.devops.agent.domain.biz.service.TicketAiAnalysisService aiAnalysisService,
                                 com.devops.agent.domain.diagnosis.HypothesisGenerator hypothesisGenerator,
                                 com.devops.agent.domain.biz.repository.DiagnosisHypothesisRepository hypothesisRepository,
                                 com.devops.agent.domain.alert.service.AlertWebSocketNotifier wsNotifier,
                                 com.devops.agent.domain.notify.Notifier notifier,
                                 com.devops.agent.domain.alert.repository.AlertRepository alertRepository) {
        this.metricsCollector = metricsCollector;
        this.changesCollector = changesCollector;
        this.logsCollector = logsCollector;
        this.knowledgeCollector = knowledgeCollector;
        this.catalog = catalog;
        this.evidenceRepository = evidenceRepository;
        this.sessionRepository = sessionRepository;
        this.stateManager = stateManager;
        this.aiAnalysisService = aiAnalysisService;
        this.hypothesisGenerator = hypothesisGenerator;
        this.hypothesisRepository = hypothesisRepository;
        this.wsNotifier = wsNotifier;
        this.notifier = notifier;
        this.alertRepository = alertRepository;
        this.pool = new ThreadPoolExecutor(
                CORE, MAX, 30, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(QUEUE),
                new NamedFactory("diagnosis-worker"),
                // 批 76 / P2-3（报告 174 审计件）：池满不再「丢弃仅 WARN」——
                // 诊断证据静默丢失是最阴险的降级。改为落库排队（QUEUED 会话行），
                // 由 DiagnosisQueueScheduler 周期捞起重跑。排队本身失败（库也挂了）
                // 才退化为仅 WARN 丢弃——那是全库故障场景，排队救不了。
                (r, executor) -> {
                    log.warn("⚠️ [Diagnosis] 诊断池满负荷，任务转入 QUEUED 排队 | poolSize={}/{} queue={}/{}",
                            executor.getPoolSize(), MAX, executor.getQueue().size(), QUEUE);
                    if (r instanceof QueuedDiagnosisTask task) {
                        try {
                            sessionRepository.enqueueIfAbsent(task.traceId(), task.alertId(),
                                    task.ticketId(), task.service());
                        } catch (Exception dbEx) {
                            log.error("❌ [Diagnosis] 排队落库失败，诊断任务丢弃（库故障场景）| alertId={} | {}",
                                    task.alertId(), dbEx.getMessage());
                        }
                    }
                });
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

        pool.execute(new QueuedDiagnosisTask(traceId, alertId, ticketId, service,
                () -> runDiagnosis(traceId, snapshot, alertId, ticketId, service)));
        return traceId;
    }

    /**
     * 池满时可被拒绝策略识别并转排队的任务载体（批 76 / P2-3）。
     * <p>拒绝策略需要 traceId/alertId/ticketId/service 四元组来落 QUEUED 会话行，
     * 裸 Runnable 拿不到字段——用 record 同时承载元数据与实际工作。</p>
     */
    record QueuedDiagnosisTask(String traceId, Long alertId, String ticketId,
                               String service, Runnable work) implements Runnable {
        @Override
        public void run() {
            work.run();
        }
    }

    /** 排队扫描器限量捞起用：当前池队列剩余容量（批 76 / P2-3）。 */
    public int remainingQueueCapacity() {
        return pool.getQueue().remainingCapacity();
    }

    /**
     * 排队会话的恢复执行（批 76 / P2-3）：扫描器已把会话行 CAS 置为 RUNNING
     * （占位语义已由 QUEUED→RUNNING 延续，无需 createIfAbsent 再占位），
     * 此处直接提交任务体。池再次满时该任务会再走拒绝策略——但占位谓词
     * 已含 QUEUED，重新排队幂等（ON CONFLICT DO NOTHING）。
     */
    public void resumeQueued(String traceId, Long alertId, String ticketId, String service) {
        Map<String, String> snapshot = TraceContext.capture();
        pool.execute(new QueuedDiagnosisTask(traceId, alertId, ticketId, service,
                () -> runDiagnosis(traceId, snapshot, alertId, ticketId, service)));
    }

    private void runDiagnosis(String traceId, Map<String, String> snapshot,
                              Long alertId, String ticketId, String service) {
        try {
            TraceContext.restore(snapshot);
            stateManager.transition(AgentState.CONTEXT_PREPARED, TriggerType.SECURITY_PASSED,
                    "诊断编排开始（异步线程接管）");

            // ── ① 取证（确定性三方向，不依赖模型规划）
            List<Evidence> evidences = collect(service, resolveAlertAnchor(alertId));
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
            // ── ③.1 取证明细（2026-09-30 方案 B）：逐方向状态 + 日志 Top 模式/样本
            // 拼进摘要——摘要流向分析区回填/会话落库/钉钉 WS 通知，一处拼全部可见。
            // 放在假设之后：假设的「；Top-1 假设…」续在结论句尾，明细独立成段收尾。
            summary = summary + "\n\n" + buildEvidenceDigest(aggregated);

            // ── ④ 判定 + 会话收尾（证据不足硬终止转人工）
            completeSession(traceId, alertId, ticketId, service, aggregated, summary);
        } catch (Exception ex) {
            log.error("❌ [Diagnosis] 诊断异常 | traceId={} alertId={} | {}",
                    traceId, alertId, ex.getMessage(), ex);
            safeFailSession(traceId, alertId, service, ex.getMessage());
            // 方案 B 补齐（2026-09-30）：取证链崩了工单侧也不能静默——
            // 否则 collectorThrows 这类异常在工单时间线上零痕迹，
            // 值班人只能看到「没有取证明细」而不知道 AI 是没查还是挂了。
            recordDiagnosisFailureActivity(traceId, ticketId, ex);
            safeTransition(AgentState.FAILED, TriggerType.SYSTEM_ERROR,
                    "诊断异常：" + ex.getMessage());
            // 单独兜底，不向上抛。
        } finally {
            TraceContext.clear();
        }
    }

    /**
     * 告警锚点：名称（检索锚点）+ 描述（检索语义）+ 级别（日志取证窗口/级别的调节依据）
     * + 原始标签（V11：instance/pod 等下钻维度，日志取证的 keyword 来源）。
     */
    private record AlertAnchor(String name, String description, String level,
                               java.util.Map<String, String> labels) {

        static AlertAnchor empty() {
            return new AlertAnchor(null, null, null, java.util.Map.of());
        }

        /**
         * 日志取证的内容关键词：恒为 null（不做正文过滤）。
         *
         * <p>2026-09-29 实证修正：此前用 instance/pod/container/namespace 标签值做
         * 正文关键词（{@code |= "<instance>"}），意图是「同服务多实例精确到实体」。
         * 但实例标识（K8s pod 名 / host:port）从不出现在应用日志正文里——它们是
         * 调度层元数据，不是日志内容。结果是正文过滤把日志滤光：opsbrain-ai 明明
         * 有 16 行 WARN，加 {@code |= "evid-..."} 后恒为 0 行（NO_DATA）。</p>
         *
         * <p>日志取证的正确过滤维度是 Loki <b>标签</b>（app/service）+ 级别，
         * 不是正文关键词。实例级过滤若未来需要，应给 promtail 加 instance/pod
         * <b>标签</b>后用标签选择器（{@code {app=..., instance=...}}），而非正文匹配。</p>
         */
        String logKeyword() {
            return null;
        }
    }

    /**
     * 按 alertId 回查告警锚点：知识检索的查询锚点 + 日志取证的级别依据。拿不到不阻塞。
     * 服务名单独撑起的查询（「xxx 故障排查」）语义太弱，实测 0.73 阈值下恒被熔断；
     * 告警名（如 OpsBrainMemoryHigh）携带症状信号，检索才有机会命中手册。
     */
    private AlertAnchor resolveAlertAnchor(Long alertId) {
        if (alertId == null) return AlertAnchor.empty();
        try {
            return alertRepository.findById(alertId)
                    .map(a -> new AlertAnchor(a.getAlertName(), a.getDescription(), a.getLevel(),
                            parseLabels(a.getLabelsJson())))
                    .orElseGet(AlertAnchor::empty);
        } catch (Exception e) {
            log.warn("⚠️ [Diagnosis] 回查告警锚点失败（取证继续，知识检索退化为仅服务名） | alertId={} | {}",
                    alertId, e.getMessage());
            return AlertAnchor.empty();
        }
    }

    /** labels_json（V11）→ Map；空/坏值降级为空 Map——标签是增强不是依赖。 */
    private java.util.Map<String, String> parseLabels(String labelsJson) {
        if (labelsJson == null || labelsJson.isBlank() || "{}".equals(labelsJson)) {
            return java.util.Map.of();
        }
        try {
            return objectMapper.readValue(labelsJson,
                    objectMapper.getTypeFactory().constructMapType(
                            java.util.LinkedHashMap.class, String.class, String.class));
        } catch (Exception e) {
            log.debug("[Diagnosis] labels_json 解析失败（忽略原始标签）| {}", e.getMessage());
            return java.util.Map.of();
        }
    }

    /**
     * 解析服务依赖配置 → 服务 → 上游依赖列表。
     * 格式 {@code order-service:redis,mysql;payment-svc:mysql}；空/坏值降级为空 Map。
     */
    private java.util.Map<String, List<String>> parseServiceDependencies() {
        if (serviceDependenciesConfig == null || serviceDependenciesConfig.isBlank()) {
            return java.util.Map.of();
        }
        java.util.Map<String, List<String>> map = new java.util.LinkedHashMap<>();
        for (String pair : serviceDependenciesConfig.split(";")) {
            String[] kv = pair.split(":");
            if (kv.length != 2) continue;
            String svc = kv[0].trim();
            List<String> deps = new ArrayList<>();
            for (String d : kv[1].split(",")) {
                if (!d.trim().isEmpty()) deps.add(d.trim());
            }
            if (!svc.isEmpty() && !deps.isEmpty()) map.put(svc, deps);
        }
        return map;
    }

    /**
     * 跨服务依赖拓扑取证（轻量版）：对本服务的每个上游依赖，收集其指标 + 日志
     * 关键证据，汇总为一个 TOPOLOGY 证据。让「Redis 雪崩 → 后端报错」这类跨服务
     * 因果能进诊断视野，而不是单服务孤立诊断。
     *
     * <p>设计要点：</p>
     * <ul>
     *   <li>复用现有 metrics/logs 收集器，仅把目标服务换成依赖服务，零新依赖；</li>
     *   <li>类型 TOPOLOGY——EvidenceAggregator 将其设为非关键增强项，
     *       不计入充分性统计，不稀释本服务的证据判定；</li>
     *   <li>依赖收集失败只降级为该依赖缺席，不阻断主诊断；</li>
     *   <li>未配置依赖时返回 null（调用方不追加证据），行为与此前完全一致。</li>
     * </ul>
     */
    private Evidence collectTopologyEvidence(String service) {
        java.util.Map<String, List<String>> deps = parseServiceDependencies();
        List<String> upstreams = deps.get(service);
        if (upstreams == null || upstreams.isEmpty()) {
            return null;
        }
        String metricCsv = String.join(",", catalog.availableMetrics());
        List<java.util.Map<String, Object>> depDetails = new ArrayList<>();
        int anomalyServices = 0;
        for (String up : upstreams) {
            try {
                Evidence m = metricsCollector.collect(up, COLLECT_RANGE, metricCsv);
                Evidence l = logsCollector.collect(up, COLLECT_RANGE, "ERROR", null);
                java.util.Map<String, Object> d = new java.util.LinkedHashMap<>();
                d.put("service", up);
                d.put("metricsStatus", m.status().name());
                d.put("metricsTitle", m.title());
                d.put("logsStatus", l.status().name());
                d.put("logsTitle", l.title());
                // 依赖服务指标异常数（content.anomalyCount）是因果判断的关键信号
                Object ac = m.content() == null ? null : m.content().get("anomalyCount");
                d.put("metricsAnomalyCount", ac instanceof Number ? ((Number) ac).intValue() : 0);
                if (ac instanceof Number && ((Number) ac).intValue() > 0) anomalyServices++;
                depDetails.add(d);
            } catch (Exception ex) {
                log.warn("⚠️ [Diagnosis] 依赖服务取证失败（降级为该依赖缺席） | service={} upstream={} | {}",
                        service, up, ex.getMessage());
            }
        }
        if (depDetails.isEmpty()) {
            return null;
        }
        java.util.Map<String, Object> content = new java.util.LinkedHashMap<>();
        content.put("service", service);
        content.put("dependencyCount", depDetails.size());
        content.put("anomalousDependencyCount", anomalyServices);
        content.put("dependencies", depDetails);
        String title = "上游依赖拓扑：" + depDetails.size() + " 个依赖服务"
                + (anomalyServices > 0 ? "，其中 " + anomalyServices + " 个指标异常" : "，指标均未见异常");
        return new Evidence(Evidence.EvidenceStatus.SUCCESS, Evidence.Type.TOPOLOGY,
                title, content, "service-dependencies-config", null, java.time.Instant.now());
    }

    // ───────────── helpers ─────────────

    private List<Evidence> collect(String service, AlertAnchor anchor) {
        List<Evidence> evidences = new ArrayList<>(4);
        String metricCsv = String.join(",", catalog.availableMetrics());
        evidences.add(metricsCollector.collect(service, COLLECT_RANGE, metricCsv));
        evidences.add(changesCollector.collect(service, COLLECT_RANGE));

        // 日志取证按告警级别调窗口与级别：越紧急越往宽往深看
        //   P0/P1 → 60 分钟 + WARN 级（把故障前的警告信号也捞出来）
        //   P2    → 30 分钟 + ERROR（默认，日常故障面）
        //   P3/P4 → 15 分钟 + ERROR（信息级只看紧邻窗口，省取数）
        String logRange = switch (anchor.level() == null ? "" : anchor.level()) {
            case "P0", "P1" -> "60m";
            case "P3", "P4" -> "15m";
            default -> COLLECT_RANGE;
        };
        String logLevel = "P0".equals(anchor.level()) || "P1".equals(anchor.level()) ? "WARN" : "ERROR";
        // V11：keyword 用告警原始标签的 instance/pod——同服务多实例时精确到实体，
        // 不滤会把别的实例的堆栈当证据。无标签（老数据）维持 null 全量捞。
        evidences.add(logsCollector.collect(service, logRange, logLevel, anchor.logKeyword()));

        // P0 2026-09-24：诊断接入知识库——检索命中作为 KNOWLEDGE 证据进推理与回流。
        // 查询锚点=告警名+描述（2026-09-25 实测：服务名是语义噪声，会稀释相似度）
        evidences.add(knowledgeCollector.collect(service, COLLECT_RANGE, anchor.name(), anchor.description()));

        // 跨服务依赖拓扑（轻量版）：配置了依赖才收集；TOPOLOGY 证据不计入充分性
        // 统计（EvidenceAggregator 非关键增强项），不稀释本服务的证据判定。
        Evidence topology = collectTopologyEvidence(service);
        if (topology != null) {
            evidences.add(topology);
        }

        // 告警分类（业务/技术/安全/因果）：业务性告警注入业务上下文证据，
        // 防止 AI 把「工单积压」这类业务流程问题硬读成技术故障（实测曾误诊为 SQL 注入）。
        Evidence kindContext = collectKindContext(service, anchor);
        if (kindContext != null) {
            evidences.add(kindContext);
        }
        return evidences;
    }

    /**
     * 业务性告警的业务上下文证据（AIOps 业界共识：业务/技术告警的诊断策略本质不同）。
     * <p>仅当告警分类为 BUSINESS 时注入——告诉 AI「这是业务流程问题（积压/SLA/转化），
     * 请从业务流程角度分析（工单分布/负责人排班/SLA 状态），不要硬找技术指标」。
     * 其他类型返回 null，不改变现有行为。</p>
     */
    private Evidence collectKindContext(String service, AlertAnchor anchor) {
        com.devops.agent.domain.alert.AlertKind kind =
                com.devops.agent.domain.alert.AlertKindClassifier.classify(
                        anchor.name(), anchor.labels(), anchor.description());
        if (kind != com.devops.agent.domain.alert.AlertKind.BUSINESS) {
            return null;
        }
        java.util.Map<String, Object> content = new java.util.LinkedHashMap<>();
        content.put("alertKind", "BUSINESS");
        content.put("guidance", "这是业务性告警（工单积压/SLA 预警/转化异常等业务流程指标），"
                + "不是技术故障。请从业务流程角度分析：工单优先级分布、负责人排班、SLA 时限、"
                + "业务量级变化。不要硬找 CPU/内存/日志等技术指标——它们对本类告警无意义。");
        content.put("service", service);
        return new Evidence(Evidence.EvidenceStatus.SUCCESS, "business-context",
                "业务性告警上下文（请按业务流程分析，勿找技术指标）", content,
                "alert-kind-classifier", null, java.time.Instant.now());
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

    /**
     * 会话行 service 落值 = 被诊断服务真实名；空值回退执行器自述。
     * 2026-10-01 修复：completeSession/safeFailSession 曾硬编码第三参
     * "diagnosis-engine"——正常路径的会话/回放页 service 与真实服务失联，
     * 而 QUEUED 路径（enqueueIfAbsent）传的又是真名，两条路径自相矛盾。
     */
    private static String sessionServiceOf(String service) {
        return (service == null || service.isBlank()) ? "diagnosis-engine" : service;
    }

    private void completeSession(String traceId, Long alertId, String ticketId, String service,
                                 EvidenceAggregator.AggregateResult aggregated,
                                 String summary) {
        try {
            Long sessionId = sessionRepository.createIfAbsent(traceId, alertId, sessionServiceOf(service));
            if (sessionId != null) {
                int rows = sessionRepository.complete(sessionId, ticketId,
                        String.valueOf(aggregated.sufficiency()), summary);
                if (rows == 0) {
                    // 守卫拦下：会话已被并发收尾（或进了 ERROR）。不重复消费，
                    // 但必须留痕——否则「结论没落库」这种事只能翻 DB 发现
                    log.warn("⚠️ [Diagnosis] 完成态守卫拦截：会话已是终态，"
                            + "本次 complete 未生效 | sessionId={} traceId={}", sessionId, traceId);
                }
            }
            // 2-1.5 工单 AI 分析区回填（§6.1 验收第 1 条的最后一个环）。
            // 置信度为按充分性映射的启发值（S2-2 才做真实校准）：
            //   SUFFICIENT=80 / WEAK=60（INSUFFICIENT 不回填，见下方门槛）
            // 方案 3 批 78：回填前查重——AI 分析有两条独立写库路径（本诊断回填 +
            // 前端详情页生成），2026-09-13 BUG.md 现场同一工单两路径各写一版，
            // 用户看到重复分析且成本翻倍。近期（10 分钟）已有任何版本即跳过自动
            // 回填：自动回填是「附属增值」不是「权威结论」，人已产出过分析就不必
            // 再插一版；用户想要新结论走前端「重新分析」（手动路径不受此限）。
            // 方案 C 批 79：INSUFFICIENT（证据不足，转人工信号）不再写入分析表——
            // 此前 conf=20 的低质量结论也会占据「版本 1」，让空态生成按钮消失，
            // 用户可能误以为 AI 已尽力而不再点「生成」；且证据回放 traceId 已由
            // 通知（publishCompletion→钉钉/WS）与告警详情页承载，分析表只收
            // 有实质推理价值的结论。INSUFFICIENT 的人工介入信号走通知通道。
            if (ticketId != null && !ticketId.isBlank()
                    && aggregated.sufficiency() != EvidenceAggregator.Sufficiency.INSUFFICIENT) {
                try {
                    if (aiAnalysisService.hasRecentAnalysis(ticketId, 10)) {
                        log.info("⏭️ [Diagnosis] 工单近期已有 AI 分析，跳过自动回填防重复 | ticketId={}", ticketId);
                    } else {
                        int conf = switch (aggregated.sufficiency()) {
                            case SUFFICIENT -> 80;
                            case WEAK -> 60;
                            default -> 0;   // 不可达：INSUFFICIENT 已被上方门槛挡下
                        };
                        aiAnalysisService.save(ticketId,
                                summary + "\n\n（证据回放:traceId=" + traceId + "）",
                                null, null, null, conf, null);
                        log.info("🩺 [Diagnosis] 诊断结论已回填工单 | ticketId={} | sufficiency={} | conf={}",
                                ticketId, aggregated.sufficiency(), conf);
                    }
                } catch (Exception ex) {
                    // 回填失败不反噬诊断主流程（同样是附属增值一族）
                    log.warn("⚠️ [Diagnosis] 工单 AI 分析回填失败 | ticketId={} | why={}",
                            ticketId, ex.getMessage());
                }
            } else if (ticketId != null && !ticketId.isBlank()) {
                log.info("⏭️ [Diagnosis] 证据不足（INSUFFICIENT），结论不进 AI 分析表，走人工介入通知 | ticketId={}",
                        ticketId);
            }
            // 活动流留痕（2026-09-30 方案 B）：无论结论是否回填分析表，工单时间线
            // 都要有一条可见的取证记录。批79 只禁 INSUFFICIENT 占分析表版本号，
            // 不禁活动流——且分析区可能被「近期已有版本」跳过，活动流是取证内容
            // 在工单侧的常驻入口。旁路：留痕失败仅 WARN。
            recordDiagnosisActivity(traceId, ticketId, aggregated, summary);
            AgentState finalState = aggregated.sufficiency() == EvidenceAggregator.Sufficiency.INSUFFICIENT
                    ? AgentState.FAILED : AgentState.DRAFT_READY;
            safeTransition(finalState,
                    aggregated.sufficiency() == EvidenceAggregator.Sufficiency.INSUFFICIENT
                            ? TriggerType.MANUAL_TAKEOVER : TriggerType.DRAFT_GENERATED,
                    summary);
            publishCompletion(traceId, alertId, ticketId, aggregated, summary);
        } catch (Exception ex) {
            log.warn("⚠️ [Diagnosis] 会话收尾失败但证据不丢 traceId={} why={}", traceId, ex.getMessage());
        }
    }

    /** 2-1.6：诊断完成推送（WS + 钉钉）。推送失败仅 WARN——附属增值一族。 */
    private void publishCompletion(String traceId, Long alertId, String ticketId,
                                   EvidenceAggregator.AggregateResult aggregated, String summary) {
        try {
            java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put("traceId", traceId);
            payload.put("alertId", alertId);
            payload.put("ticketId", ticketId);
            payload.put("sufficiency", String.valueOf(aggregated.sufficiency()));
            payload.put("conflicts", aggregated.conflicts().size());
            payload.put("summary", summary);
            wsNotifier.broadcastDiagnosis(payload);
        } catch (Exception ex) {
            log.warn("⚠️ [Diagnosis] WebSocket 推送失败 | traceId={} why={}", traceId, ex.getMessage());
        }
        try {
            String title = aggregated.sufficiency() == EvidenceAggregator.Sufficiency.INSUFFICIENT
                    ? "⚠️ 诊断证据不足，需人工介入" : "🩺 诊断完成";
            var msg = aggregated.sufficiency() == EvidenceAggregator.Sufficiency.INSUFFICIENT
                    ? com.devops.agent.domain.notify.NotifyMessage.urgent(title, summary)
                    : com.devops.agent.domain.notify.NotifyMessage.normal(title, summary);
            notifier.send(msg);
        } catch (Exception ex) {
            log.warn("⚠️ [Diagnosis] 钉钉通知失败 | traceId={} why={}", traceId, ex.getMessage());
        }
    }

    private void safeFailSession(String traceId, Long alertId, String service, String errorMessage) {
        try {
            Long sessionId = sessionRepository.createIfAbsent(traceId, alertId, sessionServiceOf(service));
            if (sessionId != null) {
                int rows = sessionRepository.fail(sessionId,
                        errorMessage == null ? "<unknown>" :
                                errorMessage.substring(0, Math.min(255, errorMessage.length())));
                if (rows == 0) {
                    // 通常是「COMPLETED 后又有尾部异常」——成功结论优先，记录警示即可
                    log.warn("⚠️ [Diagnosis] 失败态守卫拦截：会话已是终态，"
                            + "本次 fail 未覆盖既有结论 | sessionId={} traceId={}", sessionId, traceId);
                }
            }
        } catch (Exception ex) {
            log.warn("⚠️ [Diagnosis] 会话失败态落库失败 traceId={} why={}", traceId, ex.getMessage());
        }
    }

    private void safeTransition(AgentState toState, TriggerType trigger, String detail) {
        try {
            stateManager.transition(toState, trigger, detail);
        } catch (Exception ignore) {
            // 状态机不兜底——排障依据是会话行，不是状态机单点；
            // 但契约要求留痕：debug 级别既不进告警噪声，又能在排障现场指到它
            log.debug("[Diagnosis] 状态机转移跳过 | to={} trigger={} 原因={}",
                    toState, trigger, ignore.getMessage());
        }
    }

    /**
     * 取证明细段（2026-09-30 方案 B）：逐方向状态 + 日志 Top 模式/样本行。
     *
     * <p>拼进摘要后流向四处：AI 分析区回填、诊断会话落库、钉钉/WS 通知、
     * 工单活动流——「查了什么、查到什么、哪个源没接」在任何入口都可见。
     * 此前证据只有诊断页/证据回放看得到，值班人在工单里只看到一句结论，
     * 证据不足时连 buildFinalSummary 承诺的「附已获得的取证记录」都没真附。</p>
     *
     * <p>日志模式带样本首行：那行往往就是报错堆栈（类/方法/行号）——
     * 「像查 bug 一样先看报错日志」落到工单上的就是这一段。</p>
     */
    private String buildEvidenceDigest(EvidenceAggregator.AggregateResult agg) {
        StringBuilder sb = new StringBuilder("【取证明细】");
        for (Evidence e : agg.evidences()) {
            if (e == null) {
                continue;
            }
            sb.append("\n- [").append(e.evidenceType()).append("] ")
              .append(e.status().name()).append("：").append(clip(e.title(), 160));
            if (Evidence.Type.LOGS.equals(e.evidenceType())
                    && e.status() == Evidence.EvidenceStatus.SUCCESS) {
                appendLogPatterns(sb, e);
            }
        }
        return sb.toString();
    }

    /** 日志证据的 Top 模式展开：模板（报错形状）+ 次数 + 首个样本行（真实堆栈片段）。 */
    private void appendLogPatterns(StringBuilder sb, Evidence e) {
        Object raw = e.content().get("patterns");
        if (!(raw instanceof List<?> patterns) || patterns.isEmpty()) {
            return;
        }
        if (Boolean.TRUE.equals(e.content().get("summarized"))) {
            sb.append("（内容超预算已截断，全量见证据回放）");
        }
        int shown = 0;
        for (Object p : patterns) {
            if (shown >= 5) {
                break;
            }
            if (!(p instanceof Map<?, ?> m)) {
                continue;
            }
            shown++;
            // Map<?,?> 的 getOrDefault 会撞泛型捕获（默认值无法收窄到 capture），
            // 用 get + 空值兜底取原始值
            Object level = m.get("worstLevel");
            Object template = m.get("template");
            sb.append("\n  ").append(shown).append(". [")
              .append(level == null ? "?" : level).append("] ")
              .append(clip(oneLine(template == null ? "" : String.valueOf(template)), 140))
              .append(" ×").append(m.get("count"));
            Object samplesRaw = m.get("samples");
            if (samplesRaw instanceof List<?> samples && !samples.isEmpty()) {
                String sample = oneLine(String.valueOf(samples.get(0))
                        .replace("<untrusted_log>", "").replace("</untrusted_log>", ""));
                sb.append("\n     样本: ").append(clip(sample, 160));
            }
        }
    }

    /** 工单活动流留痕（失败不反噬诊断主流程）。 */
    private void recordDiagnosisActivity(String traceId, String ticketId,
                                         EvidenceAggregator.AggregateResult aggregated,
                                         String summary) {        if (ticketId == null || ticketId.isBlank() || ticketService == null) {
            return;
        }
        try {
            String text = switch (aggregated.sufficiency()) {
                case INSUFFICIENT -> "AI 取证结果（证据不足，待人工）";
                case WEAK -> "AI 诊断完成（证据薄弱）";
                case SUFFICIENT -> "AI 诊断完成，取证明细如下";
            };
            ticketService.recordActivity(ticketId, "warning", text,
                    summary + "\n\n（证据回放:traceId=" + traceId + "）", "AI 诊断", false);
        } catch (Exception ex) {
            log.warn("⚠️ [Diagnosis] 工单活动流留痕失败（不影响诊断主流程）| ticketId={} why={}",
                    ticketId, ex.getMessage());
        }
    }

    /**
     * 诊断异常的活动流留痕（2026-09-30 方案 B 补齐）。
     * <p>取证链中途崩掉时 {@link #completeSession} 根本走不到（无 aggregated/summary），
     * 若不在 catch 里留痕，工单时间线上「AI 没查」和「AI 查挂了」外观完全一样——
     * 这是诊断链最后一条静默路径。错误消息裁剪进 detail，现场按 traceId 回放。</p>
     */
    private void recordDiagnosisFailureActivity(String traceId, String ticketId, Exception ex) {
        if (ticketId == null || ticketId.isBlank() || ticketService == null) {
            return;
        }
        try {
            String msg = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            ticketService.recordActivity(ticketId, "warning", "AI 诊断执行失败（不影响工单处置）",
                    "取证链路异常终止：" + clip(msg, 200)
                            + "\n\n（错误现场:traceId=" + traceId + "，详见后端日志与诊断会话）",
                    "AI 诊断", false);
        } catch (Exception ignore) {
            // 留痕失败同样不反噬——与诊断主链同族护身
            log.warn("⚠️ [Diagnosis] 诊断失败留痕失败（已忽略）| ticketId={} why={}",
                    ticketId, ignore.getMessage());
        }
    }

    /** 摘要裁剪：单行化 + 截断（取证明细进通知/活动流，长度必须有界）。 */
    private static String clip(String s, int max) {
        if (s == null || s.isBlank() || "null".equals(s)) {
            return "—";
        }
        String oneLine = s.replace("\r", " ").replace("\n", " ");
        return oneLine.length() <= max ? oneLine : oneLine.substring(0, max) + "…";
    }

    /** 换行压平（样本行常带多行堆栈，进摘要只保留首行形状）。 */
    private static String oneLine(String s) {
        return s == null ? "" : s.replace("\r", " ").replace("\n", " ");
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
