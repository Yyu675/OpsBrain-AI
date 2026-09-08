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
}
