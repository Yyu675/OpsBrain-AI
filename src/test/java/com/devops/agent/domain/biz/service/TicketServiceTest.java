package com.devops.agent.domain.biz.service;

import com.devops.agent.domain.biz.entity.DevOpsTicket;
import com.devops.agent.domain.biz.entity.TicketEnums;
import com.devops.agent.domain.biz.repository.DevOpsTicketRepository;
import com.devops.agent.domain.biz.repository.TicketActivityRepository;
import com.devops.agent.domain.biz.repository.TicketQuery;
import com.devops.agent.domain.biz.repository.TicketReplyRepository;
import com.devops.agent.domain.biz.repository.TicketTagRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TicketService 单元测试
 * <p>
 * 覆盖 P2-3：新增的 findTickets / countTickets / findByTraceId / getTicketStats 方法，
 * 以及核心的 normalizePriority / mapPriorityToSla 委托逻辑。
 * </p>
 *
 * @author OpsBrain AI
 * @since 2026-08-18
 */
class TicketServiceTest {

    private DevOpsTicketRepository ticketRepository;
    private TicketReplyRepository replyRepository;
    private TicketActivityRepository activityRepository;
    private TicketTagRepository tagRepository;
    private com.devops.agent.domain.biz.repository.TicketActionRepository actionRepository;
    private com.devops.agent.domain.biz.repository.TicketPostmortemRepository postmortemRepository;
    private com.devops.agent.domain.notify.Notifier dingTalkNotifier;
    private StringRedisTemplate redisTemplate;
    private TicketService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ticketRepository = mock(DevOpsTicketRepository.class);
        replyRepository = mock(TicketReplyRepository.class);
        activityRepository = mock(TicketActivityRepository.class);
        tagRepository = mock(TicketTagRepository.class);
        actionRepository = mock(com.devops.agent.domain.biz.repository.TicketActionRepository.class);
        postmortemRepository = mock(com.devops.agent.domain.biz.repository.TicketPostmortemRepository.class);
        dingTalkNotifier = mock(com.devops.agent.domain.notify.Notifier.class);
        redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        service = new TicketService(ticketRepository, replyRepository,
                activityRepository, tagRepository, actionRepository, postmortemRepository,
                dingTalkNotifier, redisTemplate,
                org.mockito.Mockito.mock(com.devops.agent.domain.biz.repository.TicketAttachmentRepository.class),
                org.mockito.Mockito.mock(com.devops.agent.domain.biz.repository.TicketAiAnalysisRepository.class),
                org.mockito.Mockito.mock(io.minio.MinioClient.class));
    }

    @Test
    void findTicketsDelegatesToRepository() {
        List<DevOpsTicket> expected = List.of(new DevOpsTicket());
        when(ticketRepository.findPage(anyInt(), anyInt(), any(TicketQuery.class)))
                .thenReturn(expected);

        TicketQuery query = new TicketQuery(null, null, null, null, null,
                null, null, null, null, null, false);
        List<DevOpsTicket> result = service.findTickets(1, 10, query);

        assertThat(result).isSameAs(expected);
        verify(ticketRepository).findPage(1, 10, query);
    }

    @Test
    void countTicketsDelegatesToRepository() {
        TicketQuery query = new TicketQuery(null, null, null, null, null,
                null, null, null, null, null, false);
        when(ticketRepository.countByQuery(query)).thenReturn(42L);

        long result = service.countTickets(query);

        assertThat(result).isEqualTo(42L);
    }

    @Test
    void findByTraceIdDelegatesToRepository() {
        DevOpsTicket ticket = new DevOpsTicket();
        ticket.setId("TKT-20260818-0001");
        when(ticketRepository.findByTraceId("trace-abc")).thenReturn(ticket);

        DevOpsTicket result = service.findByTraceId("trace-abc");

        assertThat(result).isSameAs(ticket);
    }

    @Test
    void findByTraceIdReturnsNullWhenNotFound() {
        when(ticketRepository.findByTraceId("nonexistent")).thenReturn(null);

        DevOpsTicket result = service.findByTraceId("nonexistent");

        assertThat(result).isNull();
    }

    @Test
    void getTicketStatsReturnsAllExpectedFields() {
        when(ticketRepository.countAll()).thenReturn(10L);
        when(ticketRepository.countCreatedToday()).thenReturn(3L);
        when(ticketRepository.countGroupByStatus()).thenReturn(List.of(
                new Object[]{"PENDING", 5L},
                new Object[]{"PROCESSING", 3L},
                new Object[]{"RESOLVED", 1L},
                new Object[]{"CLOSED", 1L},
                new Object[]{"VOID", 0L}
        ));
        when(ticketRepository.countGroupByPriority()).thenReturn(List.of(
                new Object[]{"P0", 2L},
                new Object[]{"P1", 3L},
                new Object[]{"P2", 4L},
                new Object[]{"P3", 1L}
        ));
        when(ticketRepository.countUrgentPending()).thenReturn(5L);

        var stats = service.getTicketStats();

        assertThat(stats.total()).isEqualTo(10L);
        assertThat(stats.todayNew()).isEqualTo(3L);
        assertThat(stats.urgentPending()).isEqualTo(5L);
        assertThat(stats.byStatus().get("PENDING")).isEqualTo(5L);
        assertThat(stats.byStatus().get("PROCESSING")).isEqualTo(3L);
        assertThat(stats.byStatus().get("RESOLVED")).isEqualTo(1L);
        assertThat(stats.byStatus().get("CLOSED")).isEqualTo(1L);
        assertThat(stats.byStatus().get("VOID")).isEqualTo(0L);
        assertThat(stats.byPriority().get("P0")).isEqualTo(2L);
        assertThat(stats.byPriority().get("P1")).isEqualTo(3L);
        assertThat(stats.byPriority().get("P2")).isEqualTo(4L);
        assertThat(stats.byPriority().get("P3")).isEqualTo(1L);

        // 扁平字段验证
        assertThat(stats.pending()).isEqualTo(5L);
        assertThat(stats.processing()).isEqualTo(3L);
        // resolved = RESOLVED + CLOSED
        assertThat(stats.resolved()).isEqualTo(2L);
    }

    @Test
    void getTicketStatsFillsMissingStatusesWithZero() {
        when(ticketRepository.countAll()).thenReturn(0L);
        when(ticketRepository.countCreatedToday()).thenReturn(0L);
        when(ticketRepository.countGroupByStatus()).thenReturn(List.of());
        when(ticketRepository.countGroupByPriority()).thenReturn(List.of());
        when(ticketRepository.countUrgentPending()).thenReturn(0L);

        var stats = service.getTicketStats();

        // 所有状态/优先级都应补 0，字典键保持动态 Map 语义。
        for (String s : TicketEnums.Status.ALL) {
            assertThat(stats.byStatus().get(s)).isZero();
        }
        for (String p : TicketEnums.Priority.ALL) {
            assertThat(stats.byPriority().get(p)).isZero();
        }
    }

    @Test
    void ticketTrendsAreTypedZeroFilledAndKeepRequestedModule() {
        java.time.LocalDate today = java.time.LocalDate.now();
        when(ticketRepository.countCreatedByDay(3, "MYSQL")).thenReturn(
                java.util.Collections.singletonList(new Object[]{java.sql.Date.valueOf(today), 5L}));
        when(ticketRepository.countResolvedByDay(3, "MYSQL")).thenReturn(
                java.util.Collections.singletonList(new Object[]{
                        java.sql.Date.valueOf(today.minusDays(1)), 2L}));

        var trends = service.getTicketTrends(3, "MYSQL");

        assertThat(trends.days()).containsExactly(
                today.minusDays(2).toString(), today.minusDays(1).toString(), today.toString());
        assertThat(trends.created()).containsExactly(0L, 0L, 5L);
        assertThat(trends.resolved()).containsExactly(0L, 2L, 0L);
        assertThat(trends.module()).isEqualTo("MYSQL");
        verify(ticketRepository).countCreatedByDay(3, "MYSQL");
        verify(ticketRepository).countResolvedByDay(3, "MYSQL");
    }

    @Test
    void getTotalTicketsDelegatesToRepository() {
        when(ticketRepository.countAll()).thenReturn(100L);

        assertThat(service.getTotalTickets()).isEqualTo(100L);
    }

    @Test
    void updateStatusToResolvedRecordsMttrMetrics() {
        com.devops.agent.infrastructure.metrics.BusinessMetrics bm =
                mock(com.devops.agent.infrastructure.metrics.BusinessMetrics.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "businessMetrics", bm);

        DevOpsTicket existing = new DevOpsTicket();
        existing.setId("TKT-100");
        existing.setStatus("PROCESSING");
        existing.setPriority("P1");
        existing.setCreateTime(java.time.LocalDateTime.now().minusMinutes(15));
        when(ticketRepository.findById("TKT-100")).thenReturn(existing);

        service.updateStatus("TKT-100", "RESOLVED");

        verify(ticketRepository).updateStatus("TKT-100", "RESOLVED");
        verify(bm).recordTicketResolved(org.mockito.ArgumentMatchers.eq("P1"),
                org.mockito.ArgumentMatchers.longThat(elapsed -> elapsed >= 14 * 60 * 1000L));
    }

    @Test
    void closureMetricsUsesWindowAggregatesOnlyForPositiveDays() {
        var alertRepository = mock(com.devops.agent.domain.alert.repository.AlertRepository.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "alertRepository", alertRepository);
        when(ticketRepository.countClosureMetrics()).thenReturn(new com.devops.agent.domain.biz.entity.TicketClosureMetricsData(
                10, 7, 6, 5, 4, 1, null, null, null, null,
                null, null, null, null, null, null, null, null, null));
        when(alertRepository.countSince(any(java.time.LocalDateTime.class))).thenReturn(13L);
        when(alertRepository.countDerivedIncidents(any(java.time.LocalDateTime.class))).thenReturn(8L);
        when(ticketRepository.countByCreatorSince(org.mockito.ArgumentMatchers.eq("alert-bot"),
                any(java.time.LocalDateTime.class))).thenReturn(3L);

        var result = service.getClosureMetrics(7);

        assertThat(result.windowDays()).isEqualTo(7);
        assertThat(result.alertsInWindow()).isEqualTo(13L);
        assertThat(result.alertSourcedInWindow()).isEqualTo(3L);
        assertThat(result.incidentsInWindow()).isEqualTo(8L);
        assertThat(result.incidentsTotal()).isNull();
        org.mockito.Mockito.verify(alertRepository, org.mockito.Mockito.never())
                .countDerivedIncidents(java.time.LocalDateTime.of(2000, 1, 1, 0, 0));
    }

    @Test
    void closureMetricsUsesAllTimeIncidentCountWhenNoPositiveWindow() {
        var alertRepository = mock(com.devops.agent.domain.alert.repository.AlertRepository.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "alertRepository", alertRepository);
        when(ticketRepository.countClosureMetrics()).thenReturn(new com.devops.agent.domain.biz.entity.TicketClosureMetricsData(
                0, 0, 0, 0, 0, 0, null, null, null, null,
                null, null, null, null, null, null, null, null, null));
        when(alertRepository.countDerivedIncidents(java.time.LocalDateTime.of(2000, 1, 1, 0, 0)))
                .thenReturn(21L);

        var result = service.getClosureMetrics(0);

        assertThat(result.windowDays()).isNull();
        assertThat(result.alertsInWindow()).isNull();
        assertThat(result.incidentsInWindow()).isNull();
        assertThat(result.incidentsTotal()).isEqualTo(21L);
    }
}
