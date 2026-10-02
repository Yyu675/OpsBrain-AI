package com.devops.agent.infrastructure.metrics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.devops.agent.domain.alert.repository.AlertRepository;
import com.devops.agent.domain.approval.ApprovalRequestRepository;
import com.devops.agent.domain.approval.ApprovalStatus;
import com.devops.agent.domain.biz.repository.DevOpsTicketRepository;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * 关键业务指标（S5-1.4，批 49 / 报告 152）。
 *
 * <p>形态=「存量水位计」不是「增量计数器」的现状账：给 Service 构造器
 * 加参的直插法，会让 6+ 个手装 {@code new TicketService(...)} 的测试类
 * 集体彩修（盲改大面积低风险但高噪声）；库存水位由既有 repo 计数法
 * 供给、四个量天生自带审计属性——工单/告警/审批三张报表与库表一一对账，
 * 造假空间先验为零。增量计数器（建单速率等）归入 JDK/真窗合并批。
 *
 * <p>安全护盖：任一供给抛异常（如 DB 短暂失联），该量退化为 NaN，
 * 不把整条 /actuator/prometheus 抓取陪葬——抓取面是一份供多供，
 * 一根蜡烛熄了不该全屋黑。Down 指标（批 37 P0 闸）负责塌陷警报，
 * 本件别把供给故障藏成「业务归零」。
 *
 * <p>登记一次终身：Gauge 供应函数被 Micrometer 强引用，组件本身活得
 * 与 registry 一样长，不需要服务告终反登记。
 */
@Component
@ConditionalOnProperty(name = "devops.metrics.business.enabled", havingValue = "true", matchIfMissing = true)
public class BusinessMetrics {

    private static final Logger log = LoggerFactory.getLogger(BusinessMetrics.class);

    // ==================== 增量流速面（方案⑥ RED 指标，2026-10-01）=================
    // 「存量水位」回答现在有多少，这一族回答刚才发生了多少/多快——
    // 告警系统自身的可观测此前是盲的（Prometheus 只抓容器不抓后端业务事件）。

    private final MeterRegistry registry;
    private final Counter alertReceived;
    private final Counter alertDedup;
    private final Timer ticketAutoCreateOk;
    private final Timer ticketAutoCreateFailed;
    private final Timer diagnosisCompleted;
    private final Timer diagnosisFailed;
    private final Counter notifyCounterSuccess;
    private final Counter notifyCounterFailed;
    private final Counter notifyCounterDegraded;

    /** 通知发送原子账（看门狗 delta 判定与效能卡读它——Micrometer Counter 不回读增量窗口） */
    private final java.util.concurrent.atomic.AtomicLong notifyAttempts =
            new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong notifySuccesses =
            new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong notifyFailed =
            new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong notifyDegraded =
            new java.util.concurrent.atomic.AtomicLong();
    /** 最近一次发送成功时刻（epoch ms）；0 = 本次进程尚无成功 */
    private volatile long notifyLastSuccessAt;
    private final java.util.concurrent.atomic.AtomicBoolean queueGaugeRegistered =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    public BusinessMetrics(MeterRegistry registry,
                           DevOpsTicketRepository tickets,
                           AlertRepository alerts,
                           ApprovalRequestRepository approvals) {
        this.registry = registry;
        this.alertReceived = Counter.builder("opsbrain.alert_received_total")
                .description("告警信号到达数（webhook 每条有效信号）").register(registry);
        this.alertDedup = Counter.builder("opsbrain.alert_dedup_total")
                .description("重复告警命中数（去重生效：计次而非新建）").register(registry);
        this.ticketAutoCreateOk = Timer.builder("opsbrain.ticket_autocreate_seconds")
                .tag("result", "ok").description("告警自动建单耗时（成功）").register(registry);
        this.ticketAutoCreateFailed = Timer.builder("opsbrain.ticket_autocreate_seconds")
                .tag("result", "failed").description("告警自动建单耗时（失败）").register(registry);
        this.diagnosisCompleted = Timer.builder("opsbrain.diagnosis_seconds")
                .tag("result", "completed").description("诊断全链耗时（完成）").register(registry);
        this.diagnosisFailed = Timer.builder("opsbrain.diagnosis_seconds")
                .tag("result", "failed").description("诊断全链耗时（异常）").register(registry);
        this.notifyCounterSuccess = Counter.builder("opsbrain.notify_total")
                .tag("result", "success").description("通知发送（至少一渠道受理）").register(registry);
        this.notifyCounterFailed = Counter.builder("opsbrain.notify_total")
                .tag("result", "failed").description("通知发送（有渠道但全部抛异常）").register(registry);
        this.notifyCounterDegraded = Counter.builder("opsbrain.notify_total")
                .tag("result", "degraded").description("通知降级日志（无可用渠道）——"
                        + "本地/未配渠道环境的常态，配合 available() 判配置而非误报").register(registry);
        Gauge.builder("opsbrain.tickets.total", () -> safe(() -> (double) tickets.countAll()))
                .description("工单总量（全部状态）")
                .register(registry);
        Gauge.builder("opsbrain.tickets.urgent_pending",
                        () -> safe(() -> (double) tickets.countUrgentPending()))
                .description("P0/P1 未结工单（口径与看板 KPI 同宗）")
                .register(registry);
        Gauge.builder("opsbrain.alerts.active", () -> safe(() -> (double) alerts.countActive()))
                .description("活跃告警水位（FIRING + ACKNOWLEDGED）")
                .register(registry);
        Gauge.builder("opsbrain.approvals.pending",
                        () -> safe(() -> (double) approvals.countByStatus(ApprovalStatus.PENDING.name())))
                .description("待审批排队数（值与审批页待办清单同口径）")
                .register(registry);
        log.info("📏 [Metrics] 业务水位计已登记（4 枚存量 Gauge + RED 流速面）");
    }

    // ==================== 流速面 API（调用方负责判空——本类 @ConditionalOnProperty 可缺席）=================

    public void incAlertReceived() {
        alertReceived.increment();
    }

    public void incAlertDedup() {
        alertDedup.increment();
    }

    public void recordTicketAutoCreate(long millis, boolean ok) {
        (ok ? ticketAutoCreateOk : ticketAutoCreateFailed)
                .record(millis, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    public void recordDiagnosis(long millis, boolean ok) {
        (ok ? diagnosisCompleted : diagnosisFailed)
                .record(millis, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    /**
     * 通知一次发送尝试的结果（CompositeNotifier 每次 send 收尾调一次）。
     * {@code result}：success（≥1 渠道受理）/ failed（有渠道但全抛）/ degraded（无可用渠道）。
     */
    public void notifyOutcome(String result) {
        notifyAttempts.incrementAndGet();
        if ("success".equals(result)) {
            notifySuccesses.incrementAndGet();
            notifyLastSuccessAt = System.currentTimeMillis();
            notifyCounterSuccess.increment();
        } else if ("failed".equals(result)) {
            notifyFailed.incrementAndGet();
            notifyCounterFailed.increment();
        } else {
            notifyDegraded.incrementAndGet();
            notifyCounterDegraded.increment();
        }
    }

    public long notifyAttempts() {
        return notifyAttempts.get();
    }

    public long notifySuccesses() {
        return notifySuccesses.get();
    }

    /**
     * 通知健康快照（看门狗 delta 源 + 效能卡读它）。
     * {@code lastSuccessAt} 为 ISO 时间或 null（尚无成功）。
     */
    public java.util.Map<String, Object> notifySnapshot() {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("attempts", notifyAttempts.get());
        m.put("successes", notifySuccesses.get());
        m.put("failed", notifyFailed.get());
        m.put("degraded", notifyDegraded.get());
        long last = notifyLastSuccessAt;
        m.put("lastSuccessAt", last > 0
                ? java.time.Instant.ofEpochMilli(last).toString() : null);
        return m;
    }

    /**
     * 诊断队列深度 gauge 登记（幂等）：编排器构造后经字段注入拿到本类，
     * 首次调用注册一次，后续调用直接返回——Gauge 重复注册同名会叠影。
     */
    public void registerDiagnosisQueueGauge(java.util.function.Supplier<Number> queueSize) {
        if (queueGaugeRegistered.compareAndSet(false, true)) {
            Gauge.builder("opsbrain.diagnosis_queue_depth", queueSize, q -> q.get().doubleValue())
                    .description("诊断队列待处理任务数（批 76 排队深度）")
                    .register(registry);
        }
    }

    /**
     * 供体异常→NaN 退化，单独量灭灯不限于抓面。
     * <p>NaN 语义正确（Prometheus 侧「抓不到」），但退化本身要留线索——
     * Down 指标负责塌陷警报，这条 warn 负责指认是哪个量、因何退化的，
     * 二者互补不重复（AGENTS 静默 catch 契约：吞可以，留线索是底线）。
     */
    private static double safe(java.util.function.DoubleSupplier supplier) {
        try {
            return supplier.getAsDouble();
        } catch (Exception e) {
            log.warn("📏 [Metrics] Gauge 供体异常，本量退化为 NaN | cause={}", e.getMessage());
            return Double.NaN;
        }
    }
}
