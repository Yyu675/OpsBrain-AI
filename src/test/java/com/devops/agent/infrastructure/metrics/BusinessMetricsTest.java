package com.devops.agent.infrastructure.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.devops.agent.domain.alert.repository.AlertRepository;
import com.devops.agent.domain.approval.ApprovalRequestRepository;
import com.devops.agent.domain.approval.ApprovalStatus;
import com.devops.agent.domain.biz.repository.DevOpsTicketRepository;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * 业务水位计单元测试（批 49，S5-1.4）。
 * SimpleMeterRegistry 直上零容器：登记面与供体面各自验。
 */
class BusinessMetricsTest {

    @Test
    @DisplayName("四枚 Gauge 登记成功且读数与供体对账")
    void gaugesRegisterAndReadFromSuppliers() {
        DevOpsTicketRepository tickets = mock(DevOpsTicketRepository.class);
        AlertRepository alerts = mock(AlertRepository.class);
        ApprovalRequestRepository approvals = mock(ApprovalRequestRepository.class);
        when(tickets.countAll()).thenReturn(42L);
        when(tickets.countUrgentPending()).thenReturn(3L);
        when(alerts.countActive()).thenReturn(7);
        when(approvals.countByStatus(ApprovalStatus.PENDING.name())).thenReturn(5);

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new BusinessMetrics(registry, tickets, alerts, approvals);

        assertThat(registry.get("opsbrain.tickets.total").gauge().value()).isEqualTo(42.0);
        assertThat(registry.get("opsbrain.tickets.urgent_pending").gauge().value()).isEqualTo(3.0);
        assertThat(registry.get("opsbrain.alerts.active").gauge().value()).isEqualTo(7.0);
        assertThat(registry.get("opsbrain.approvals.pending").gauge().value()).isEqualTo(5.0);
    }

    @Test
    @DisplayName("供体异常退化为 NaN，不拖垮整条抓取面")
    void supplierFailureDegradesToNaN() {
        DevOpsTicketRepository tickets = mock(DevOpsTicketRepository.class);
        AlertRepository alerts = mock(AlertRepository.class);
        ApprovalRequestRepository approvals = mock(ApprovalRequestRepository.class);
        when(tickets.countAll()).thenThrow(new IllegalStateException("simulated db hiccup"));
        when(tickets.countUrgentPending()).thenReturn(2L);
        when(alerts.countActive()).thenReturn(1);

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new BusinessMetrics(registry, tickets, alerts, approvals);

        assertThat(registry.get("opsbrain.tickets.total").gauge().value()).isNaN();
        assertThat(registry.get("opsbrain.tickets.urgent_pending").gauge().value()).isEqualTo(2.0);
    }

    @Test
    @DisplayName("送达回执与时间回退计数 + notify 快照补字段（ROI#1/#2）")
    void deliveryAndFallbackCounters() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        BusinessMetrics m = new BusinessMetrics(
                registry,
                mock(DevOpsTicketRepository.class),
                mock(AlertRepository.class),
                mock(ApprovalRequestRepository.class));

        m.notifyDelivery(true);
        m.notifyDelivery(true);
        m.notifyDelivery(false);
        m.incAlertTimeFallback("startsAt");
        m.incAlertTimeFallback("endsAt");
        m.incAlertTimeFallback("endsAt");

        assertThat(registry.get("opsbrain.notify_delivery_total")
                .tags("result", "ok").counter().count()).isEqualTo(2.0);
        assertThat(registry.get("opsbrain.notify_delivery_total")
                .tags("result", "error").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("opsbrain.alert_time_fallback_total")
                .tags("field", "startsAt").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("opsbrain.alert_time_fallback_total")
                .tags("field", "endsAt").counter().count()).isEqualTo(2.0);

        var snap = m.notifySnapshot();
        assertThat(snap.delivered()).isEqualTo(2L);
        assertThat(snap.deliverFailed()).isEqualTo(1L);
        assertThat(snap.lastDeliveredAt()).isNotNull();
        assertThat(snap.lastSuccessAt()).isNull();
    }

    @Test
    @DisplayName("告警到达、去重多维细分与降噪反指标（观察窗与风暴聚合）计数")
    void multiDimensionalAlertAndSuppressionCounters() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        BusinessMetrics m = new BusinessMetrics(
                registry,
                mock(DevOpsTicketRepository.class),
                mock(AlertRepository.class),
                mock(ApprovalRequestRepository.class));

        m.incAlertReceived("critical", "zabbix");
        m.incAlertReceived("warning", "prometheus");
        m.incAlertDedup("P1", "prometheus");
        m.incObservationSuppressed("P2");
        m.incStormAggregated();

        assertThat(registry.get("opsbrain.alert_received_total")
                .tags("severity", "critical", "source", "zabbix").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("opsbrain.alert_received_total")
                .tags("severity", "warning", "source", "prometheus").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("opsbrain.alert_dedup_total")
                .tags("severity", "P1", "source", "prometheus").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("opsbrain.observation_suppressed_total")
                .tags("level", "P2").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("opsbrain.storm_aggregated_total").counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("诊断各路证据收集状态打点（kind/status细分计数）")
    void diagnosisEvidenceStatusCounters() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        BusinessMetrics m = new BusinessMetrics(
                registry,
                mock(DevOpsTicketRepository.class),
                mock(AlertRepository.class),
                mock(ApprovalRequestRepository.class));

        m.recordDiagnosisEvidence("metrics", "SUCCESS");
        m.recordDiagnosisEvidence("logs", "FAILED");
        m.recordDiagnosisEvidence("knowledge", "NO_DATA");
        m.recordDiagnosisEvidence(null, null);

        assertThat(registry.get("opsbrain.diagnosis_evidence_status_total")
                .tags("kind", "metrics", "status", "SUCCESS").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("opsbrain.diagnosis_evidence_status_total")
                .tags("kind", "logs", "status", "FAILED").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("opsbrain.diagnosis_evidence_status_total")
                .tags("kind", "knowledge", "status", "NO_DATA").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("opsbrain.diagnosis_evidence_status_total")
                .tags("kind", "unknown", "status", "UNKNOWN").counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("送达静默秒数 Gauge 正常反映最近一次送达时间差")
    void notifySilentSecondsGauge() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        BusinessMetrics m = new BusinessMetrics(
                registry,
                mock(DevOpsTicketRepository.class),
                mock(AlertRepository.class),
                mock(ApprovalRequestRepository.class));

        // 初始无送达，返回 0.0
        assertThat(registry.get("opsbrain.notify_silent_seconds").gauge().value()).isEqualTo(0.0);

        m.notifyDelivery(true);
        // 送达后读数大于等于 0
        assertThat(registry.get("opsbrain.notify_silent_seconds").gauge().value()).isGreaterThanOrEqualTo(0.0);
    }

    @Test
    @DisplayName("在办工单按优先级拆分 Gauge 与工单解决耗时 Timer")
    void ticketOpenAndResolvedMetrics() {
        DevOpsTicketRepository tickets = mock(DevOpsTicketRepository.class);
        when(tickets.countOpenByPriority("P0")).thenReturn(2L);
        when(tickets.countOpenByPriority("P1")).thenReturn(5L);

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        BusinessMetrics m = new BusinessMetrics(
                registry,
                tickets,
                mock(AlertRepository.class),
                mock(ApprovalRequestRepository.class));

        assertThat(registry.get("opsbrain.tickets.open")
                .tag("priority", "P0").gauge().value()).isEqualTo(2.0);
        assertThat(registry.get("opsbrain.tickets.open")
                .tag("priority", "P1").gauge().value()).isEqualTo(5.0);

        m.recordTicketResolved("P0", 120_000);
        assertThat(registry.get("opsbrain.ticket_resolved_seconds")
                .tag("priority", "P0").timer().count()).isEqualTo(1L);
    }
}
