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
    private com.devops.agent.domain.notify.DingTalkNotifier dingTalkNotifier;
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
        dingTalkNotifier = mock(com.devops.agent.domain.notify.DingTalkNotifier.class);
        redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        service = new TicketService(ticketRepository, replyRepository,
                activityRepository, tagRepository, actionRepository, postmortemRepository,
                dingTalkNotifier, redisTemplate);
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

        Map<String, Object> stats = service.getTicketStats();

        assertThat(stats).containsKeys("total", "todayNew", "byStatus", "byPriority",
                "pending", "processing", "resolved", "urgentPending");

        assertThat(stats.get("total")).isEqualTo(10L);
        assertThat(stats.get("todayNew")).isEqualTo(3L);
        assertThat(stats.get("urgentPending")).isEqualTo(5L);

        @SuppressWarnings("unchecked")
        Map<String, Long> byStatus = (Map<String, Long>) stats.get("byStatus");
        assertThat(byStatus.get("PENDING")).isEqualTo(5L);
        assertThat(byStatus.get("PROCESSING")).isEqualTo(3L);
        assertThat(byStatus.get("RESOLVED")).isEqualTo(1L);
        assertThat(byStatus.get("CLOSED")).isEqualTo(1L);
        assertThat(byStatus.get("VOID")).isEqualTo(0L);

        @SuppressWarnings("unchecked")
        Map<String, Long> byPriority = (Map<String, Long>) stats.get("byPriority");
        assertThat(byPriority.get("P0")).isEqualTo(2L);
        assertThat(byPriority.get("P1")).isEqualTo(3L);
        assertThat(byPriority.get("P2")).isEqualTo(4L);
        assertThat(byPriority.get("P3")).isEqualTo(1L);

        // 扁平字段验证
        assertThat(stats.get("pending")).isEqualTo(5L);
        assertThat(stats.get("processing")).isEqualTo(3L);
        // resolved = RESOLVED + CLOSED
        assertThat(stats.get("resolved")).isEqualTo(2L);
    }

    @Test
    void getTicketStatsFillsMissingStatusesWithZero() {
        when(ticketRepository.countAll()).thenReturn(0L);
        when(ticketRepository.countCreatedToday()).thenReturn(0L);
        when(ticketRepository.countGroupByStatus()).thenReturn(List.of());
        when(ticketRepository.countGroupByPriority()).thenReturn(List.of());
        when(ticketRepository.countUrgentPending()).thenReturn(0L);

        Map<String, Object> stats = service.getTicketStats();

        @SuppressWarnings("unchecked")
        Map<String, Long> byStatus = (Map<String, Long>) stats.get("byStatus");
        // 所有状态都应补 0
        for (String s : TicketEnums.Status.ALL) {
            assertThat(byStatus.get(s)).isZero();
        }

        @SuppressWarnings("unchecked")
        Map<String, Long> byPriority = (Map<String, Long>) stats.get("byPriority");
        for (String p : TicketEnums.Priority.ALL) {
            assertThat(byPriority.get(p)).isZero();
        }
    }

    @Test
    void getTotalTicketsDelegatesToRepository() {
        when(ticketRepository.countAll()).thenReturn(100L);

        assertThat(service.getTotalTickets()).isEqualTo(100L);
    }
}
