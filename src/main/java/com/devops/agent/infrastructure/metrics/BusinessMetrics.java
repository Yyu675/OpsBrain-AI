package com.devops.agent.infrastructure.metrics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.devops.agent.domain.alert.repository.AlertRepository;
import com.devops.agent.domain.approval.ApprovalRequestRepository;
import com.devops.agent.domain.approval.ApprovalStatus;
import com.devops.agent.domain.biz.repository.DevOpsTicketRepository;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

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

    public BusinessMetrics(MeterRegistry registry,
                           DevOpsTicketRepository tickets,
                           AlertRepository alerts,
                           ApprovalRequestRepository approvals) {
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
        log.info("📏 [Metrics] 业务水位计已登记（4 枚存量 Gauge）");
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
