package com.devops.agent.domain.alert.service;

import com.devops.agent.domain.alert.AlertmanagerSourceAdapter;
import com.devops.agent.domain.alert.AlertSignal;
import com.devops.agent.domain.alert.DTO.AlertmanagerWebhook;
import com.devops.agent.domain.alert.ReservedAlertNames;
import com.devops.agent.domain.alert.entity.Alert;
import com.devops.agent.domain.alert.repository.AlertRepository;
import com.devops.agent.domain.biz.entity.TicketEnums;
import com.devops.agent.domain.biz.entity.DevOpsTicket;
import com.devops.agent.domain.biz.service.TicketService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import com.devops.agent.domain.notify.NotifyMessage;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.*;

/**
 * 告警处理服务（L2 实时监测）
 * <p>
 * 职责：接收 Prometheus Alertmanager 告警推送，完成去重、持久化、自动建单。
 * </p>
 *
 * <h3>核心流程</h3>
 * <ol>
 *   <li>计算去重键 {@code SHA-256(alertName + service + 排序后的标签)}</li>
 *   <li>按去重键查询活跃告警（FIRING / ACKNOWLEDGED）</li>
 *   <li>已存在 → 递增 {@code occurrence_count}、刷新 {@code last_occurred_at}</li>
 *   <li>不存在 → 创建新告警 + 自动建单（通过 TicketService, Single Writer 契约 6.10）</li>
 *   <li>收到 resolved → 标记对应告警为 RESOLVED</li>
 * </ol>
 *
 * <h3>映射规则</h3>
 * <ul>
 *   <li>Level → Priority：P0/P1→HIGH, P2/P3→MEDIUM, P4→LOW</li>
 *   <li>Module → Category：DB→数据库, POD/K8S→容器/K8s, NETWORK→网络, 其余→其他</li>
 *   <li>Severity → Level：CRITICAL→P0, WARNING→P2, INFO→P4, 默认→P3</li>
 *   <li>SLA：HIGH→4h/8h, MEDIUM→8h/24h, LOW→24h</li>
 * </ul>
 *
 * <h3>契约</h3>
 * <ul>
 *   <li>自动建单失败不阻塞告警入库——告警本体有效，工单是附属增值</li>
 *   <li>去重键排除 alertname/service/severity——这些字段已单独处理</li>
 *   <li>标签排序用 TreeMap 保证确定性——HashMap 迭代顺序不可靠</li>
 * </ul>
 *
 * @author OpsBrain AI
 * @since 2026-08-14
 */
@Slf4j
@Service
public class AlertService {

    private final AlertRepository alertRepository;
    private final TicketService ticketService;
    private final AlertWebSocketNotifier alertNotifier;
    /** 通知渠道：依赖接口而非具体厂商实现（可插拔） */
    private final com.devops.agent.domain.notify.Notifier notifier;

    /** 告警源适配器（L2 跨源接缝）：把具体源负载归一化为 AlertSignal */
    private final AlertmanagerSourceAdapter alertSourceAdapter;

    /** 诊断编排器（S2-1）：新告警建单后异步触发诊断。required=false 的旧构造点
     *  （三个手工构造测试）不挂它的场景沿用原语义——诊断是附属增值，缺装不阻断。 */
    private final com.devops.agent.application.diagnosis.DiagnosisOrchestrator diagnosisOrchestrator;

    public AlertService(AlertRepository alertRepository, TicketService ticketService,
                        AlertWebSocketNotifier alertNotifier,
                        com.devops.agent.domain.notify.Notifier notifier,
                        AlertmanagerSourceAdapter alertSourceAdapter,
                        com.devops.agent.application.diagnosis.DiagnosisOrchestrator diagnosisOrchestrator) {
        this.alertRepository = alertRepository;
        this.ticketService = ticketService;
        this.alertNotifier = alertNotifier;
        this.notifier = notifier;
        this.alertSourceAdapter = alertSourceAdapter;
        this.diagnosisOrchestrator = diagnosisOrchestrator;
    }

    /**
     * S4-1：告警 → 治理策略 → 自愈动作 的触发引擎（可选装配）。
     * <p>字段注入 + required=false：既有直连 5 参构造的测试与最小上下文装配不受影响；
     * 引擎缺席时告警链一切照旧（自动诊断那一族护身的同款降级）。</p>
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.devops.agent.domain.healing.HealingAutoTrigger healingAutoTrigger;

    /**
     * 服务 → 值班负责人路由（2026-09-25，待分配积压治理）。
     * <p>
     * 真实库 27/28 张工单停在「待分配」：自动建单恒传 assignee=null，
     * 单子建出来就没有到任何人的路径。这里在告警建单前按服务名查路由，
     * 命中则直接指派；未配置的服务保持「待分配」原行为。
     * 与自愈引擎同款可选装配——路由表缺席时告警链一切照旧。
     * </p>
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.devops.agent.domain.biz.repository.ServiceOwnerRepository serviceOwnerRepository;

    // ==================== 配置注入（application.yml devops.alert.*） ====================
    // 6.20 契约：配置项必须有代码读取它——存在但无人读的配置比没有更糟。

    /** 告警接收总开关。关闭后 Webhook 端点仍返回 200 但跳过全部处理（Prometheus 对非 200 会重试） */
    @Value("${devops.alert.enabled:true}")
    private boolean alertEnabled;

    /** 自动建单开关。关闭后告警仍入库去重，但不触发自动建单（Single Writer 契约 6.10） */
    @Value("${devops.alert.auto-ticket-enabled:true}")
    private boolean autoTicketEnabled;

    /** 自动建单创建人标识（默认值为 ALERT_CREATOR "alert-bot"） */
    @Value("${devops.alert.ticket-creator:alert-bot}")
    private String alertCreator;

    /** 告警聚合降噪开关（方向 E）。关闭后回退为每条告警各建单（原行为） */
    @Value("${devops.alert.aggregate-enabled:true}")
    private boolean aggregateEnabled;

    /** 自动诊断开关（S2-1）。关闭后告警仍入库建单，但不触发诊断编排 */
    @Value("${devops.alert.auto-diagnose-enabled:true}")
    private boolean autoDiagnoseEnabled;

    /**
     * 自动建单的最低告警级别（分级建单，PRD FR-3.1）。
     * 默认 P3：P4 信息类告警只入库统计，不建工单——真实库里 28 张工单
     * 大半无人认领，低级别噪声是主因之一。设为 P4 则恢复全量建单。
     */
    @Value("${devops.alert.auto-ticket-min-level:P3}")
    private String autoTicketMinLevel;

    /** 聚合时间窗口（分钟）：窗口内同 service+module 的不同告警聚合到同一工单 */
    @Value("${devops.alert.aggregate-window-minutes:5}")
    private int aggregateWindowMinutes;

    /**
     * 自愈观察窗开关（PRD FR-3.1）。开启后观察级告警（见 observation-levels）
     * 不立即建单：窗口内 resolved 自愈则只留统计，到期未愈由
     * {@code AlertObservationScheduler} 补建工单并补触发诊断。
     */
    @Value("${devops.alert.observation-enabled:true}")
    private boolean observationEnabled;

    /**
     * 观察级集合（逗号分隔，默认 P2,P3）。P0/P1 高危永远立即建单——
     * 观察窗是给「可能自己好的抖动」用的，高危等不起一个窗口。
     * 注意与 auto-ticket-min-level 的交集才有意义：低于门槛的级别本来就不建单。
     */
    @Value("${devops.alert.observation-levels:P2,P3}")
    private String observationLevels;

    /** 观察窗口（分钟）：告警首次发生后等这么久，未自愈才建单 */
    @Value("${devops.alert.observation-window-minutes:10}")
    private int observationWindowMinutes;

    /** 观察补建的回看上限（小时）：更老的未建单活跃告警不再补建，防配置错配时反复捞同一批 */
    private static final int OBSERVATION_LOOKBACK_HOURS = 24;

    /** 单批补建上限：风暴后大批观察窗同时到期时逐批消化，保护建单链 */
    private static final int OBSERVATION_BATCH_LIMIT = 200;

    // ==================== 全局风暴模式（FR-2.5） ====================

    /** 风暴模式开关。开启后速率超阈值只放行高危建单，其余聚合为风暴摘要事件 */
    @Value("${devops.alert.storm.enabled:true}")
    private boolean stormEnabled;

    /** 风暴进入阈值：近 60 秒 firing 告警数达到该值即进入风暴模式 */
    @Value("${devops.alert.storm.enter-rate-per-min:100}")
    private int stormEnterRatePerMin;

    /**
     * 风暴退出阈值（迟滞带）：速率回落到该值以下才退出。
     * 进入/退出不设差值会在边界上反复横跳，风暴摘要事件会跟着抖动建单。
     */
    @Value("${devops.alert.storm.exit-rate-per-min:20}")
    private int stormExitRatePerMin;

    /**
     * 近 60 秒 firing 信号到达时间戳（滑动窗口）。
     * webhook 由 HTTP 线程池并发调用，所有读写必须在本对象的监视器里。
     */
    private final ArrayDeque<Long> stormArrivals = new ArrayDeque<>();

    /** 风暴模式状态。volatile：读在告警处理链、写在 updateStormState 的同步块外可见 */
    private volatile boolean stormActive = false;

    /** 风暴摘要事件的固定去重键：整场风暴只活跃一条、只建一单 */
    private static final String STORM_SUMMARY_DEDUP_KEY = "storm-summary";

    // ==================== Level → Priority 映射 ====================

    /**
     * 告警级别 → 工单优先级
     * <p>
     * <b>B0 改造</b>：工单优先级改四档 P0~P3 后，告警的 P0~P4 可以几乎一一对应，
     * 不再塌缩。此前 P0/P1 都映射为 HIGH、P2/P3 都映射为 MEDIUM——
     * 一条 P0 生产宕机告警与一条 P1 告警建出的工单优先级完全相同，
     * 分级响应无从谈起。
     * </p>
     * <p>
     * 仅 P4（信息类）与 P3 合并为工单 P3——工单侧无第五档，
     * 而 P4 本就不要求响应时限。
     * </p>
     */
    private static final Map<String, String> LEVEL_TO_PRIORITY = Map.of(
            "P0", TicketEnums.Priority.P0,
            "P1", TicketEnums.Priority.P1,
            "P2", TicketEnums.Priority.P2,
            "P3", TicketEnums.Priority.P3,
            "P4", TicketEnums.Priority.P3
    );

    // ==================== Module → Category 映射 ====================

    private static final Map<String, String> MODULE_TO_CATEGORY = Map.of(
            "DB", "数据库",
            "POD", "容器/K8s",
            "K8S", "容器/K8s",
            "NETWORK", "网络",
            "HOST", "其他",
            "CACHE", "其他"
    );

    private static final String DEFAULT_CATEGORY = "其他";
    private static final String ALERT_CREATOR = "alert-bot";

    // ==================== 分级建单门槛 ====================

    /**
     * 该级别告警是否够格建单。级别序数 P0=0 … P4=4，序数越小越紧急。
     * 门槛配置非法时回退 P3 并告警——配错配置不能变成「从此一张单都不建」。
     */
    private boolean isTicketLevel(String level) {
        int alertOrd = levelOrdinal(level);
        int minOrd = levelOrdinal(autoTicketMinLevel);
        if (minOrd < 0) {
            log.warn("⚠️ [AlertService] 建单门槛配置非法（{}），按默认 P3 处理", autoTicketMinLevel);
            minOrd = 3;
        }
        // 级别无法识别时不建单：normalizeLevel 兜底为 P3，走到这里的非法值只会来自未来新级别
        return alertOrd >= 0 && alertOrd <= minOrd;
    }

    /** 级别 → 序数（P0=0 最紧急，P4=4 最轻）；无法识别返回 -1 */
    private static int levelOrdinal(String level) {
        if (level == null) return -1;
        return switch (level.trim().toUpperCase()) {
            case "P0" -> 0;
            case "P1" -> 1;
            case "P2" -> 2;
            case "P3" -> 3;
            case "P4" -> 4;
            default -> -1;
        };
    }

    /** 观察级集合（配置串解析为 Set；非法项丢弃，全非法时为空集 = 观察窗不生效） */
    private Set<String> observationLevelSet() {
        if (observationLevels == null || observationLevels.isBlank()) {
            return Set.of();
        }
        Set<String> set = new HashSet<>();
        for (String s : observationLevels.split(",")) {
            String v = s.trim().toUpperCase();
            if (levelOrdinal(v) >= 0) {
                set.add(v);
            }
        }
        return set;
    }

    /** 来源系统取值：labels 里的 system（路径注入优先，已在入口处覆盖），缺省 'default' */
    private String alertSystemOf(AlertSignal signal) {
        String sys = signal.labels() != null ? signal.labels().get("system") : null;
        return (sys == null || sys.isBlank()) ? "default" : sys.trim();
    }

    /**
     * 该告警当前是否处于自愈观察窗内（读路径派生，供列表/详情页打「观察中」标）。
     * <p>口径与补建调度一致：活跃 + 未建单 + 观察级 + 未超窗。</p>
     */
    public boolean isObserving(Alert alert) {
        if (!observationEnabled || alert == null) {
            return false;
        }
        if (!"FIRING".equals(alert.getStatus()) && !"ACKNOWLEDGED".equals(alert.getStatus())) {
            return false;
        }
        if (alert.getTicketId() != null) {
            return false;
        }
        if (!observationLevelSet().contains(alert.getLevel())) {
            return false;
        }
        LocalDateTime first = alert.getFirstOccurredAt();
        return first != null && first.isAfter(LocalDateTime.now().minusMinutes(observationWindowMinutes));
    }

    /** 观察窗配置快照（供查询侧统计用——单一配置源，口径不分叉） */
    public ObservationPolicy observationPolicy() {
        return new ObservationPolicy(observationEnabled, observationLevelSet(), observationWindowMinutes);
    }

    /** 观察窗配置快照 record（domain 层：controller 不得被 domain 依赖，DTO 放这里） */
    public record ObservationPolicy(boolean enabled, Set<String> levels, int windowMinutes) {}

    /**
     * 该新告警是否应进自愈观察窗（本次不建单）。
     * <p>三个条件同时成立：观察窗开启、级别在观察级集合内、级别够建单门槛
     * （不够门槛的级别走原「只入库统计」分支，不进观察窗语义）。</p>
     */
    private boolean isObservationPending(Alert alert) {
        return autoTicketEnabled && observationEnabled
                && isTicketLevel(alert.getLevel())
                && observationLevelSet().contains(alert.getLevel());
    }

    // ==================== 对外入口 ====================

    /**
     * 处理 Alertmanager Webhook 推送（批量告警）
     * <p>
     * 单条失败不影响其余；失败路径记 ERROR 日志并继续。
     * </p>
     *
     * @param webhook Alertmanager 回调负载
     */
    public void processWebhook(AlertmanagerWebhook webhook) {
        processWebhook(webhook, null);
    }

    /**
     * 处理 Alertmanager Webhook 推送（带来源系统路径注入，FR-1.2）。
     *
     * <p>
     * {@code /webhook/{system}} 端点的路径段是<b>部署侧保证的来源</b>：
     * 各系统的 Alertmanager 各自只配自己的 URL。路径值覆盖 payload 里的
     * {@code system} label——payload 可伪造，路径不能（改路径等于改配置）。
     * </p>
     *
     * @param webhook    Alertmanager 回调负载
     * @param pathSystem 接入路径注入的系统标识（旧端点为 null，回落 payload label）
     */
    public void processWebhook(AlertmanagerWebhook webhook, String pathSystem) {
        // 总开关防护（控制器已按同开关提前拦截，此处为防御性二道校验）
        if (!alertEnabled) {
            log.warn("⏸️ [AlertService] 告警接收已关闭（devops.alert.enabled=false），跳过处理");
            return;
        }

        if (webhook == null || webhook.getAlerts() == null || webhook.getAlerts().isEmpty()) {
            log.warn("⚠️ [AlertService] 收到空告警负载，跳过处理");
            return;
        }

        // 源适配器归一化 → 路径注入 system → 归一化信号流（接第二源时核心链路零改动）
        processSignals(injectSystemLabel(alertSourceAdapter.normalize(webhook), pathSystem));
    }

    /**
     * 把路径注入的 system 覆盖进每条信号的 labels（labels 参与去重键计算，
     * 同名告警从不同系统接入因此不会互相计次——这正是区分来源的意义）。
     */
    private List<AlertSignal> injectSystemLabel(List<AlertSignal> signals, String pathSystem) {
        if (pathSystem == null || pathSystem.isBlank()) {
            return signals;
        }
        return signals.stream()
                .filter(Objects::nonNull)
                .map(s -> {
                    Map<String, String> labels = s.labels() != null
                            ? new LinkedHashMap<>(s.labels()) : new LinkedHashMap<>();
                    labels.put("system", pathSystem);
                    return new AlertSignal(s.alertName(), s.service(), s.severity(), s.module(),
                            s.fingerprint(), s.source(), labels, s.description(), s.startsAt(), s.resolved());
                })
                .toList();
    }

    /**
     * 处理归一化告警信号流（跨源统一入口）。
     * <p>单条失败不影响其余；失败路径记 ERROR 日志并继续。</p>
     */
    public void processSignals(List<AlertSignal> signals) {
        if (signals == null || signals.isEmpty()) {
            log.warn("⚠️ [AlertService] 归一化后无有效告警信号，跳过处理");
            return;
        }
        // FR-2.5：先记账（firing 信号计入滑动窗口速率），再逐条处理。
        // resolved 信号不计入速率——它们是减负而非负载。
        if (stormEnabled) {
            long firingCount = signals.stream().filter(s -> s != null && !s.resolved()).count();
            updateStormState(firingCount);
        }
        for (AlertSignal signal : signals) {
            if (signal == null) {
                continue;
            }
            try {
                processSignal(signal);
            } catch (Exception e) {
                log.error("❌ [AlertService] 单条告警处理失败 | alertName={} | fingerprint={} | error={}",
                        signal.alertName(), signal.fingerprint(), e.getMessage(), e);
            }
        }
    }

    // ==================== 全局风暴模式（FR-2.5） ====================

    /**
     * 滑动窗口速率记账与风暴状态迁移（带迟滞）。
     * <p>
     * 速率口径：近 60 秒 firing 信号数。进入 {@code >= enterRate}，
     * 退出 {@code <= exitRate}——两阈值之间的迟滞带防止边界抖动
     * 让风暴摘要事件反复建单/恢复。
     * </p>
     */
    private void updateStormState(long arrivals) {
        if (arrivals <= 0 && !stormActive) {
            return;   // 无负载且不在风暴：零成本快路径
        }
        long now = System.currentTimeMillis();
        boolean exitedNow = false;
        synchronized (stormArrivals) {
            for (long i = 0; i < arrivals; i++) {
                stormArrivals.addLast(now);
            }
            long cutoff = now - 60_000;
            while (!stormArrivals.isEmpty() && stormArrivals.peekFirst() < cutoff) {
                stormArrivals.pollFirst();
            }
            int rate = stormArrivals.size();
            if (!stormActive && rate >= stormEnterRatePerMin) {
                stormActive = true;
                log.error("🌪️ [AlertService] 进入告警风暴模式 | 速率={}/min ≥ 进入阈值={} | "
                        + "高危照常建单，其余聚合成风暴摘要事件", rate, stormEnterRatePerMin);
            } else if (stormActive && rate <= stormExitRatePerMin) {
                stormActive = false;
                exitedNow = true;
                log.info("🌤️ [AlertService] 告警风暴模式退出 | 速率={}/min ≤ 退出阈值={}", rate, stormExitRatePerMin);
            }
        }
        if (exitedNow) {
            resolveStormSummary();
        }
    }

    /**
     * 该告警在风暴模式下是否被抑制（转投摘要事件）。
     * <p>
     * 放行：P0/P1 高危（{@link Alert#isHighRisk()}）、平台保留告警
     * （看门狗/管道静默/风暴摘要自身——它们报的是平台健康，风暴里更不能盲）。
     * </p>
     */
    private boolean isStormSuppressed(Alert alert) {
        if (!stormEnabled || !stormActive) {
            return false;
        }
        if (alert.isHighRisk()) {
            return false;
        }
        String name = alert.getAlertName();
        return !ReservedAlertNames.PIPELINE_WATCHDOG.equals(name)
                && !ReservedAlertNames.PIPELINE_SILENT.equals(name)
                && !ReservedAlertNames.STORM_SUMMARY.equals(name);
    }

    /**
     * 风暴摘要事件 upsert：固定去重键 → 整场风暴只活跃一条、只建一单，
     * 被抑制的告警计数体现在它的 occurrence_count 增长上。
     * 旁路：摘要失败不反噬主流程（被抑制的告警本身已入库）。
     */
    private void upsertStormSummary() {
        try {
            Alert summary = new Alert();
            summary.setSource("storm-guard");
            summary.setSystem("platform");
            summary.setAlertName(ReservedAlertNames.STORM_SUMMARY);
            summary.setLevel("P1");
            summary.setTitle("【告警风暴】全局速率超阈值，低级别告警已聚合抑制");
            summary.setDescription("告警速率超过风暴阈值，P2 及以下告警不再单独建单，"
                    + "统一聚合为本摘要事件；各告警本体仍在告警列表可查。速率回落后自动恢复常态建单。");
            summary.setStatus("FIRING");
            summary.setDedupKey(STORM_SUMMARY_DEDUP_KEY);
            summary.setService("opsbrain-platform");
            summary.setModule("OTHER");
            summary.setOccurrenceCount(1);
            summary.setFirstOccurredAt(LocalDateTime.now());
            summary.setLastOccurredAt(LocalDateTime.now());
            boolean isNew = alertRepository.insertOrIncrement(summary);
            if (isNew) {
                log.error("🌪️ [AlertService] 风暴摘要事件已创建 | id={}", summary.getId());
                // P1 高危：走正常建单链（一场风暴一张单），聚合抑制/通知等全链复用
                proceedNewAlert(summary, summary.getAlertName(), summary.getService());
            } else {
                alertRepository.findActiveByDedupKey(STORM_SUMMARY_DEDUP_KEY)
                        .ifPresent(alertNotifier::broadcastUpdate);
            }
        } catch (Exception e) {
            log.warn("⚠️ [AlertService] 风暴摘要 upsert 失败（已忽略）| {}", e.getMessage());
        }
    }

    /** 风暴退出时自动恢复摘要事件——风暴结束这件事本身也该被看见 */
    private void resolveStormSummary() {
        try {
            alertRepository.findActiveByDedupKey(STORM_SUMMARY_DEDUP_KEY).ifPresent(a -> {
                alertRepository.resolve(a.getId());
                a.setStatus("RESOLVED");
                alertNotifier.broadcastResolved(a);
                log.info("✅ [AlertService] 风暴摘要事件已自动恢复 | id={}", a.getId());
            });
        } catch (Exception e) {
            log.warn("⚠️ [AlertService] 风暴摘要恢复失败（已忽略）| {}", e.getMessage());
        }
    }

    /**
     * 风暴模式状态快照（告警列表横幅的数据源）。
     * <p>读取即修剪过期时间戳，保证 ratePerMin 是当前窗口的真实值。</p>
     */
    public com.devops.agent.domain.alert.DTO.StormStatus stormStatus() {
        if (!stormEnabled) {
            return new com.devops.agent.domain.alert.DTO.StormStatus(
                    false, false, 0, stormEnterRatePerMin, stormExitRatePerMin);
        }
        long cutoff = System.currentTimeMillis() - 60_000;
        synchronized (stormArrivals) {
            while (!stormArrivals.isEmpty() && stormArrivals.peekFirst() < cutoff) {
                stormArrivals.pollFirst();
            }
            return new com.devops.agent.domain.alert.DTO.StormStatus(
                    true, stormActive, stormArrivals.size(), stormEnterRatePerMin, stormExitRatePerMin);
        }
    }

    // ==================== 单条处理 ====================

    /**
     * 处理单条归一化告警信号
     * <p>
     * 流程：计算去重键 → 查活跃告警 → 已存在则递增次数 / 不存在则创建 + 建单 / 已恢复则标记解决。
     * </p>
     */
    private void processSignal(AlertSignal signal) {
        String alertName = signal.alertName();
        String service = signal.service();

        if (alertName == null || alertName.isBlank()) {
            log.warn("⚠️ [AlertService] 告警缺少 alertname，跳过 | fingerprint={}", signal.fingerprint());
            return;
        }

        // 计算去重键：排除 alertname/service/severity 避免重复
        String dedupKey = computeDedupKey(alertName, service, signal.labels());

        // 已恢复告警：标记活跃告警为 RESOLVED
        if (signal.resolved()) {
            handleResolvedAlert(dedupKey);
            return;
        }

        // 原子去重（批 76 / P2-1，报告 174 审计件）：单条 upsert 完成
        // 「新告警插入 或 既有活跃告警计次」。原「查后插」在并发同键推送下，
        // 后者撞 uk_alert_active_dedup 部分唯一索引按异常丢弃——告警没丢但
        // 计次丢失、异常路径污染日志。ON CONFLICT 把竞争窗口收进单条语句。
        Alert candidate = buildAlertCandidate(signal, dedupKey);
        boolean isNewAlert = alertRepository.insertOrIncrement(candidate);

        if (isNewAlert) {
            // FR-2.5 风暴模式：低级别告警入库（可见性铁律）但不走建单链，
            // 转投风暴摘要事件（整场风暴一张单）。高危与平台保留告警放行。
            if (isStormSuppressed(candidate)) {
                log.info("🌪️ [AlertService] 风暴抑制 | alertName={} | level={} | 已聚合进摘要事件",
                        alertName, candidate.getLevel());
                upsertStormSummary();
                return;
            }
            // 新告警：自动建单 + 后续广播（沿用原建单链路）
            proceedNewAlert(candidate, alertName, service);
        } else {
            // 重复告警计次完成：查最新态广播更新（非阻塞旁路——推送失败不影响主流程）
            alertRepository.findActiveByDedupKey(dedupKey)
                    .ifPresent(alertNotifier::broadcastUpdate);
        }
    }

    // ==================== 已恢复告警处理 ====================

    /**
     * 处理已恢复告警
     * <p>
     * Alertmanager 会在告警恢复时推送 status=resolved 的 webhook。
     * 将匹配的活跃告警标记为 RESOLVED；无活跃记录时仅 DEBUG 日志不报错
     * （可能已在超时窗口内自动恢复）。
     * </p>
     */
    private void handleResolvedAlert(String dedupKey) {
        Optional<Alert> existing = alertRepository.findActiveByDedupKey(dedupKey);
        if (existing.isPresent()) {
            Alert alert = existing.get();
            alertRepository.resolve(alert.getId());
            log.info("✅ [AlertService] 告警已恢复 | id={} | dedupKey={}", alert.getId(), dedupKey);
            // WebSocket 广播恢复（非阻塞旁路——推送失败不影响主流程）
            alertNotifier.broadcastResolved(alert);
        } else {
            log.debug("ℹ️ [AlertService] 收到已恢复告警，但无活跃记录 | dedupKey={}", dedupKey);
        }
    }

    // ==================== 新告警创建 ====================

    /**
     * 创建新告警记录 + 自动建单
     * <p>
     * 先落告警再建单，建单失败不阻塞告警入库——告警本体有效，
     * 工单是附属增值。建单失败时 ERROR 日志留存，运维可手动补单。
     * </p>
     */
    /**
     * 构建告警实体候选（P2-1：与插入解耦——insertOrIncrement 原子完成插入/计次）。
     * <p>入参已归一化——module/description/source 由源适配器算好，本方法只落库。</p>
     */
    private Alert buildAlertCandidate(AlertSignal signal, String dedupKey) {
        String level = normalizeLevel(signal.severity());

        Alert alert = new Alert();
        alert.setSource(signal.source());
        alert.setSystem(alertSystemOf(signal));
        alert.setAlertName(signal.alertName());
        alert.setLevel(level);
        alert.setTitle(buildAlertTitle(signal.alertName(), signal.service()));
        alert.setDescription(signal.description());
        alert.setStatus("FIRING");
        alert.setDedupKey(dedupKey);
        alert.setService(signal.service());
        alert.setModule(signal.module());
        alert.setOccurrenceCount(1);
        alert.setFirstOccurredAt(toLocalDateTime(signal.startsAt()));
        alert.setLastOccurredAt(LocalDateTime.now());
        alert.setCreateTime(LocalDateTime.now());
        alert.setUpdateTime(LocalDateTime.now());
        return alert;
    }

    /**
     * 新告警后续链路（P2-1 从原 createNewAlert 拆出）：upsert 已插入，
     * 此处只做广播 + 聚合抑制判断 + 建单 + 诊断 + 治理触发。
     */
    private void proceedNewAlert(Alert saved, String alertName, String service) {
        log.info("🚨 [AlertService] 新告警已入库 | id={} | alertName={} | level={} | service={} | module={}",
                saved.getId(), alertName, saved.getLevel(), service, saved.getModule());

        // WebSocket 广播新告警（非阻塞旁路——推送失败不影响主流程）
        alertNotifier.broadcastNew(saved);

        // 方向 E：告警风暴聚合抑制。窗口内同 service+module 已有已建单的活跃告警时，
        // 新告警关联其工单而不新建单——避免一个故障源（如节点宕机）引发的多条不同告警
        // 各建一张工单刷屏。被抑制的告警仍已入库（上方 upsert），列表可见（告警可见性铁律），
        // 只是不重复建单、不重复强提醒。
        if (autoTicketEnabled && aggregateEnabled) {
            Optional<Alert> group = alertRepository.findActiveGroupTicket(service, saved.getModule(), aggregateWindowMinutes);
            if (group.isPresent() && group.get().getTicketId() != null) {
                String groupTicketId = group.get().getTicketId();
                alertRepository.updateTicketId(saved.getId(), groupTicketId);
                saved.setTicketId(groupTicketId);
                log.info("🧲 [AlertService] 告警聚合抑制 | id={} | alertName={} | service={} | module={} | 关联工单={} | 窗口={}min",
                        saved.getId(), alertName, service, saved.getModule(), groupTicketId, aggregateWindowMinutes);
                // 关联到组工单：追加活动流 + 聚合通知（不重复强提醒）
                appendAggregatedAlert(groupTicketId, saved, alertName);
                return;
            }
        }

        // 自愈观察窗（PRD FR-3.1）：观察级告警（默认 P2/P3）不立即建单。
        // 窗口内 resolved 自愈的只留统计（RESOLVED + ticket_id 空即可口径化）；
        // 到期未愈由 AlertObservationScheduler 补建单、补诊断。
        // 诊断刻意不在此处触发——给可能三分钟就自愈的瞬时抖动跑 LLM 诊断
        // 是纯成本浪费（降本契约），到期建单后再诊断不迟。
        if (isObservationPending(saved)) {
            log.info("⏳ [AlertService] 进入自愈观察窗 | id={} | alertName={} | level={} | 窗口={}min",
                    saved.getId(), alertName, saved.getLevel(), observationWindowMinutes);
            // 自愈引擎照常求值：它在窗口内把告警治好，正是观察窗想要的结局
            triggerHealingPolicy(saved);
            return;
        }

        // 自动建单（Single Writer 契约 6.10：通过 TicketService 写入，不直写 Repository）
        createAutoTicket(saved, alertName, service, saved.getModule());

        // S2-1：新告警 → 自动诊断（异步、不阻塞；工单号可能为空由会话表回填设计承载）。
        // 去重与聚合抑制分支在上方已 return——两条旁路天然不重复触发诊断。
        triggerAutoDiagnosis(saved, service);

        // S4-1：新告警 → 治理策略求值（演练留痕或构造 HealingAction 递交治理门）。
        // 与自动诊断同族：异步、失败只 WARN、绝不反噬告警入库/建单主流程。
        triggerHealingPolicy(saved);
    }

    /**
     * 观察窗到期补建工单（由 {@code AlertObservationScheduler} 周期驱动）。
     * <p>
     * 只处理观察级 + 活跃 + 未建单 + 已过窗口的告警；窗口内自愈（RESOLVED）
     * 的告警不会出现在结果里，天然实现「自愈留统计不留单」。
     * 到期补建时重走聚合抑制——窗口内同 service+module 可能已有组工单，
     * 此时关联进组而非新建，保持降噪语义一致。
     * </p>
     */
    public void createDelayedTickets() {
        if (!autoTicketEnabled || !observationEnabled) {
            return;
        }
        // 风暴期不补建：低级别告警已被风暴摘要聚合，此时补建等于绕过风暴熔断
        // （9-27 风暴压测实测：观察窗补建在风暴期捞了 183 条到期告警，
        //  靠聚合抑制才收敛到 1 张工单——补上这道闸门后连那次收敛都不需要）
        if (stormActive) {
            log.info("🌊 [AlertService] 风暴模式进行中，观察窗补建暂停一轮");
            return;
        }
        Set<String> levels = observationLevelSet();
        List<Alert> due = alertRepository.findObservationDue(
                List.copyOf(levels), observationWindowMinutes, OBSERVATION_LOOKBACK_HOURS, OBSERVATION_BATCH_LIMIT);
        if (due.isEmpty()) {
            return;
        }
        log.info("⏰ [AlertService] 自愈观察窗到期补建 | 本批={} | 级别={} | 窗口={}min",
                due.size(), levels, observationWindowMinutes);
        for (Alert alert : due) {
            try {
                if (aggregateEnabled) {
                    Optional<Alert> group = alertRepository.findActiveGroupTicket(
                            alert.getService(), alert.getModule(), aggregateWindowMinutes);
                    if (group.isPresent() && group.get().getTicketId() != null) {
                        String groupTicketId = group.get().getTicketId();
                        alertRepository.updateTicketId(alert.getId(), groupTicketId);
                        alert.setTicketId(groupTicketId);
                        appendAggregatedAlert(groupTicketId, alert, alert.getAlertName());
                        continue;
                    }
                }
                createAutoTicket(alert, alert.getAlertName(), alert.getService(), alert.getModule());
                // 建单成功后才补诊断（ticketId 已由 createAutoTicket 回填到实体）
                if (alert.getTicketId() != null) {
                    recordObservationEscalation(alert);
                    triggerAutoDiagnosis(alert, alert.getService());
                }
            } catch (Exception e) {
                // 单条失败不拖垮整批——下轮扫描还会捞到它（ticket_id 仍为空）
                log.error("❌ [AlertService] 观察窗补建失败 | alertId={} | alertName={} | error={}",
                        alert.getId(), alert.getAlertName(), e.getMessage(), e);
            }
        }
    }

    /** S4-1 策略引擎触发点：引擎缺席（测试最小装配）时静默跳过。 */
    private void triggerHealingPolicy(Alert alert) {
        if (healingAutoTrigger == null) {
            return;
        }
        try {
            healingAutoTrigger.onAlertFired(alert);
        } catch (Exception e) {
            log.warn("⚠️ [AlertService] 策略触发调用失败（不影响告警/工单） | alertId={} | error={}",
                    alert.getId(), e.getMessage());
        }
    }

    /**
     * 自动诊断触发点（S2-1，路线图 §6.1 2-1.1）。
     * <p>
     * 诊断是附属增值：任何触发失败只记 WARN，不反噬告警入库/建单主流程
     * （与上方「建单失败不阻塞告警入库」同一族护身原则）。
     * </p>
     */
    private void triggerAutoDiagnosis(Alert alert, String service) {
        if (!autoDiagnoseEnabled) {
            log.info("⏸️ [AlertService] 自动诊断已关闭，跳过 | alertId={}", alert.getId());
            return;
        }
        if (service == null || service.isBlank()) {
            log.info("ℹ️ [AlertService] 告警无服务名，跳过诊断 | alertId={} | alertName={}",
                    alert.getId(), alert.getAlertName());
            return;
        }
        try {
            String traceId = diagnosisOrchestrator.submit(alert.getId(), alert.getTicketId(), service);
            log.info("🩺 [AlertService] 自动诊断已提交 | alertId={} | ticketId={} | service={} | traceId={}",
                    alert.getId(), alert.getTicketId(), service, traceId);
        } catch (Exception e) {
            log.warn("⚠️ [AlertService] 自动诊断提交失败（不影响告警/工单） | alertId={} | error={}",
                    alert.getId(), e.getMessage());
        }
    }

    /**
     * 为告警自动创建工单
     * <p>
     * 建单失败的告警仍可在告警列表查看，运维可手动触发建单。
     * 使用 9 参重载（含 sourceTraceId）。
     * </p>
     */
    private void createAutoTicket(Alert alert, String alertName, String service, String module) {
        // 自动建单开关关闭时跳过（devops.alert.auto-ticket-enabled），告警仍入库供列表查看
        if (!autoTicketEnabled) {
            log.info("⏸️ [AlertService] 自动建单已关闭，跳过 | alertId={} | alertName={}", alert.getId(), alertName);
            return;
        }

        // 分级建单：低于门槛级别的告警只入库统计——工单是「要人处理」的信号，
        // info 级噪声建单只会淹没真正要处理的单子（真实库 27/28 张无人认领的教训）
        if (!isTicketLevel(alert.getLevel())) {
            log.info("📉 [AlertService] 低于建单门槛，仅入库统计 | alertId={} | level={} | 门槛={}",
                    alert.getId(), alert.getLevel(), autoTicketMinLevel);
            return;
        }

        try {
            String priority = mapLevelToPriority(alert.getLevel());
            String category = MODULE_TO_CATEGORY.getOrDefault(module, DEFAULT_CATEGORY);
            String sla = mapPriorityToSla(priority);
            String title = "【告警】" + alertName + (service != null && !service.isBlank() ? " - " + service : "");
            String description = alert.getDescription() != null ? alert.getDescription() : title;

            // 服务路由：命中的服务直接把工单派给值班负责人，不再一律「待分配」
            String assignee = null;
            if (serviceOwnerRepository != null) {
                assignee = serviceOwnerRepository.findOwnerByService(service).orElse(null);
            }

            // 9 参重载：末参传 dedup_key 建立工单→告警反向溯源链
            //（告警侧 ticket_id 回填是正向链，此前工单侧恒空、无法反向跳转）
            DevOpsTicket ticket = ticketService.createTicket(title, priority, module, description,
                    assignee, category, sla, alertCreator, alert.getDedupKey());

            if (assignee != null && ticket != null && ticket.getId() != null) {
                log.info("👤 [AlertService] 按服务路由指派 | service={} | assignee={} | ticketId={}",
                        service, assignee, ticket.getId());
            }

            // 回填工单号：不回填则告警与工单彻底失联——列表页与详情页的「关联工单」
            // 永远显示「—」，运维看到告警却找不到对应工单，自动建单等于白做。
            if (ticket != null && ticket.getId() != null) {
                alertRepository.updateTicketId(alert.getId(), ticket.getId());
                alert.setTicketId(ticket.getId());
            } else {
                log.warn("⚠️ [AlertService] 建单返回空工单号，无法回填关联 | alertId={} | alertName={}",
                        alert.getId(), alertName);
            }

            log.info("🎫 [AlertService] 告警自动建单成功 | alertId={} | alertName={} | ticketId={} | priority={} | category={}",
                    alert.getId(), alertName, ticket != null ? ticket.getId() : null, priority, category);

            // L2 通知（方向二）：高危告警强提醒值班 SRE（蓝图 §二 P0/P1 一键弹窗强提醒）。
            // 旁路——DingTalkNotifier 内部异步 + 失败仅 WARN，不影响建单主流程。
            String ticketId = ticket != null ? ticket.getId() : null;
            notifyAlert(alert, alertName, service, priority, description, ticketId);
        } catch (Exception e) {
            log.error("❌ [AlertService] 告警自动建单失败 | alertId={} | alertName={} | error={}",
                    alert.getId(), alertName, e.getMessage(), e);
        }
    }

    /**
     * 观察窗到期补建的工单留痕。
     * <p>复盘时工单活动流要能回答「这单是哪来的」：立即建单（高危）还是
     * 观察窗到期补建（观察级未自愈）。不留痕的话，补建单与即时单外观相同，
     * 观察窗机制的效果就无从审计。</p>
     */
    private void recordObservationEscalation(Alert alert) {
        try {
            String detail = "告警在自愈观察窗（" + observationWindowMinutes + " 分钟）内未恢复，到期自动补建工单";
            ticketService.recordActivity(alert.getTicketId(), "primary", "观察窗到期补建", detail, ALERT_CREATOR, false);
        } catch (Exception e) {
            // 留痕失败不影响建单主流程（与聚合关联留痕同族降级）
            log.warn("⚠️ [AlertService] 观察窗补建留痕失败（已忽略）| alertId={} | {}", alert.getId(), e.getMessage());
        }
    }

    /**
     * 推送告警通知到钉钉
     * <p>P0/P1 高危 → @所有人强提醒；P2~P4 → 普通通知。旁路，不阻塞建单。</p>
     */
    private void notifyAlert(Alert alert, String alertName, String service,
                             String priority, String description, String ticketId) {
        try {
            String title = (alert.isHighRisk() ? "🚨 高危告警 " : "⚠️ 告警 ") + priority + " · " + alertName;
            StringBuilder md = new StringBuilder();
            md.append("### ").append(title).append("\n\n")
              .append("- **级别**：").append(priority).append(alert.isHighRisk() ? "（需人工介入）" : "").append("\n")
              .append("- **服务**：").append(service != null && !service.isBlank() ? service : "—").append("\n")
              .append("- **模块**：").append(alert.getModule() != null ? alert.getModule() : "—").append("\n")
              .append("- **详情**：").append(description != null ? description : "—").append("\n");
            if (ticketId != null) {
                md.append("- **关联工单**：").append(ticketId).append("\n");
            }
            NotifyMessage msg = alert.isHighRisk()
                    ? NotifyMessage.urgent(title, md.toString())
                    : NotifyMessage.normal(title, md.toString());
            notifier.send(msg);
        } catch (Exception e) {
            // 通知构造异常也不影响建单主流程
            log.warn("⚠️ [AlertService] 告警通知构造失败（已忽略）| alertId={} | {}", alert.getId(), e.getMessage());
        }
    }

    /**
     * 聚合关联：把被抑制的告警关联到组工单（方向 E）
     * <p>
     * <b>只记活动流留痕，不推钉钉</b>——这是降噪核心：组内后续告警不再骚扰值班 SRE
     * （首告警建单时已推过组通知）。被抑制的告警本身已入库、列表可见（告警可见性铁律），
     * 工单活动流也留有「关联告警」记录，信息不丢失，只是不重复建单、不重复强提醒。
     * </p>
     * <p>旁路：留痕失败仅 WARN，不影响告警入库与聚合抑制主流程。</p>
     */
    private void appendAggregatedAlert(String ticketId, Alert alert, String alertName) {
        try {
            String detail = "聚合关联告警：" + alertName
                    + (alert.getDescription() != null && !alert.getDescription().isBlank()
                        ? " — " + alert.getDescription() : "");
            ticketService.recordActivity(ticketId, "warning", "关联告警", detail, ALERT_CREATOR, false);
        } catch (Exception e) {
            log.warn("⚠️ [AlertService] 聚合关联留痕失败（已忽略）| ticketId={} | alertId={} | {}",
                    ticketId, alert.getId(), e.getMessage());
        }
    }

    // ==================== 去重键计算 ====================

    /**
     * 计算 SHA-256 去重键
     * <p>
     * 组成：{@code alertName | service | key1=value1 | key2=value2 | ...}
     * </p>
     * <ul>
     *   <li>标签用 {@link TreeMap} 排序保证确定性——{@link HashMap} 迭代顺序不可靠</li>
     *   <li>排除 {@code alertname}、{@code service}、{@code severity}——它们已单独出现在键中或无需参与去重</li>
     *   <li>null 安全：任一字段为 null 时以空字符串替代</li>
     * </ul>
     */
    private String computeDedupKey(String alertName, String service, Map<String, String> labels) {
        StringBuilder sb = new StringBuilder();
        sb.append(alertName != null ? alertName : "");
        sb.append("|").append(service != null ? service : "");

        if (labels != null && !labels.isEmpty()) {
            // TreeMap 保证确定性排序
            Map<String, String> sorted = new TreeMap<>(labels);
            // 排除已在键中单独出现的字段
            sorted.remove("alertname");
            sorted.remove("service");
            sorted.remove("severity");
            for (Map.Entry<String, String> e : sorted.entrySet()) {
                sb.append("|").append(e.getKey()).append("=").append(e.getValue());
            }
        }

        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 强制支持的算法，正常不可达
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    // ==================== 映射与工具方法 ====================

    /**
     * 归一化告警级别
     * <p>
     * Alertmanager 常用 {@code severity} 为 {@code critical/warning/info}，
     * 需要映射为项目的 P0-P4 分级体系。
     * </p>
     */
    private String normalizeLevel(String severity) {
        if (severity == null) return "P3";
        String s = severity.trim().toUpperCase();
        // 已经是 P0-P4 格式则直接使用
        if (s.matches("P[0-4]")) return s;
        return switch (s) {
            case "CRITICAL" -> "P0";
            case "WARNING" -> "P2";
            case "INFO" -> "P4";
            default -> "P3";
        };
    }

    /**
     * Level → Priority 映射
     * <p>P0→P0, P1→P1, P2→P2, P3/P4→P3, 未知→P2</p>
     */
    private String mapLevelToPriority(String level) {
        return LEVEL_TO_PRIORITY.getOrDefault(level, TicketEnums.Priority.P2);
    }

    /**
     * Priority → SLA 映射
     * <p>
     * 委托 {@link TicketEnums.Sla#describe} 单一来源。此前本方法自带一套
     * 硬编码字符串，与 {@link TicketService} 里的另一套重复——两处都要改，
     * 漏一处就会出现「告警建单的 SLA 与手动建单不同」的诡异现象。
     * </p>
     */
    private String mapPriorityToSla(String priority) {
        return TicketEnums.Sla.describe(priority);
    }

    /**
     * 构建告警标题
     * <p>格式：{@code 【告警】alertName - service}</p>
     */
    private String buildAlertTitle(String alertName, String service) {
        if (service != null && !service.isBlank()) {
            return "【告警】" + alertName + " - " + service;
        }
        return "【告警】" + alertName;
    }

    /**
     * OffsetDateTime → LocalDateTime（<b>系统默认时区</b>）
     *
     * <h3>为什么不是 UTC</h3>
     * <p>
     * 此前这里写的是 {@code atZoneSameInstant(ZoneOffset.UTC)}，把 Alertmanager
     * 下发的 RFC3339 时间转成 UTC 墙钟再存。但库里 {@code sys_alert.first_occurred_at}
     * 是无时区的 {@code TIMESTAMP}，而<b>同一张表的其它时间列全部是本地时间</b>——
     * {@code last_occurred_at}/{@code create_time} 由 {@code LocalDateTime.now()} 写入，
     * 数据库默认值是 {@code CURRENT_TIMESTAMP}，容器 TZ 固定 {@code Asia/Shanghai}
     * （见 Dockerfile 与 docker-compose）。
     * </p>
     * <p>
     * 一列存 UTC、邻列存 +08:00，两者在同一行里相差 8 小时，而<b>没有任何字段
     * 记录这个差异</b>。所有下游都无从分辨，只能一律按本地时间解释。
     * </p>
     *
     * <h3>用户可见后果</h3>
     * <ul>
     *   <li>告警详情页的「持续时长」把 {@code firstOccurredAt} 与
     *       {@code resolvedAt}/当前时间相减，前者晚 8 小时 →
     *       <b>刚触发的告警显示已持续 8 小时</b>；</li>
     *   <li>处置时间线上「首次发生」排在「已恢复」之后，顺序倒置；</li>
     *   <li>前端 {@code parseDate} 把无时区字符串统一按 {@code +08:00} 解释
     *       （{@code utils/time.ts} 已明确注释「服务器固定 Asia/Shanghai」），
     *       所以列表里的相对时间会显示成「8 小时前」而非「刚刚」。</li>
     * </ul>
     *
     * <h3>为什么用系统默认时区而非硬编码 +08:00</h3>
     * <p>
     * 目标是「与同表其它列口径一致」，而那些列用的是
     * {@code LocalDateTime.now()} 与数据库 {@code CURRENT_TIMESTAMP}——
     * 两者都跟随部署环境的时区。硬编码 +08:00 会在部署到其它时区时
     * 重新制造同一个偏差，而且更隐蔽（本地跑测试正常、线上错 N 小时）。
     * </p>
     *
     * <p>null 安全：入参为 null 时返回当前时间——同样是本地时区，口径一致。</p>
     */
    private LocalDateTime toLocalDateTime(OffsetDateTime odt) {
        if (odt == null) return LocalDateTime.now();
        return odt.atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
    }
}