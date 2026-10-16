package com.devops.agent.application.runtime;

import com.devops.agent.domain.biz.repository.DevOpsTicketRepository;
import com.devops.agent.domain.biz.repository.TicketPostmortemRepository;
import com.devops.agent.domain.notify.Notifier;
import com.devops.agent.domain.notify.NotifyMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 复盘缺失提醒的守护点：
 *   有完结单但零复盘 → 必须提醒（飞轮入口没人走是要被看见的事）；
 *   没有完结单 → 静默（系统还新，不是缺失）；
 *   已有人在复盘 → 不打扰。
 */
@DisplayName("复盘缺失提醒（飞轮治理）")
class PostmortemReminderSchedulerTest {

    private DevOpsTicketRepository ticketRepository;
    private TicketPostmortemRepository postmortemRepository;
    private Notifier notifier;
    private PostmortemReminderScheduler scheduler;

    @BeforeEach
    void setUp() {
        ticketRepository = mock(DevOpsTicketRepository.class);
        postmortemRepository = mock(TicketPostmortemRepository.class);
        notifier = mock(Notifier.class);
        scheduler = new PostmortemReminderScheduler(ticketRepository, postmortemRepository, notifier);
        ReflectionTestUtils.setField(scheduler, "enabled", true);
        ReflectionTestUtils.setField(scheduler, "belowPercent", 0.0);
    }

    @Test
    @DisplayName("有完结单且零复盘：发出提醒，带上欠账数字")
    void remindsWhenFinishedButNoneReviewed() {
        when(ticketRepository.countFinished()).thenReturn(20L);
        when(postmortemRepository.countAll()).thenReturn(0L);

        scheduler.remind();

        ArgumentCaptor<NotifyMessage> captor = ArgumentCaptor.forClass(NotifyMessage.class);
        verify(notifier).send(captor.capture());
        assertThat(captor.getValue().title()).contains("复盘缺失");
    }

    @Test
    @DisplayName("没有完结单：静默——系统还新不等于复盘缺失")
    void silentWhenNoFinishedTickets() {
        when(ticketRepository.countFinished()).thenReturn(0L);

        scheduler.remind();

        verify(notifier, never()).send(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("已有复盘：不打扰")
    void silentWhenReviewed() {
        when(ticketRepository.countFinished()).thenReturn(20L);
        when(postmortemRepository.countAll()).thenReturn(3L);

        scheduler.remind();

        verify(notifier, never()).send(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("开关关闭：整轮跳过")
    void disabledSkipsEntirely() {
        ReflectionTestUtils.setField(scheduler, "enabled", false);

        scheduler.remind();

        verify(ticketRepository, never()).countFinished();
    }
}
