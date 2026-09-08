package com.devops.agent.domain.biz.service;

import com.devops.agent.domain.biz.entity.DevOpsTicket;
import com.devops.agent.domain.biz.repository.DevOpsTicketRepository;
import com.devops.agent.domain.biz.repository.TicketActionRepository;
import com.devops.agent.domain.biz.repository.TicketActivityRepository;
import com.devops.agent.domain.biz.repository.TicketPostmortemRepository;
import com.devops.agent.domain.biz.repository.TicketReplyRepository;
import com.devops.agent.domain.biz.repository.TicketTagRepository;
import com.devops.agent.domain.notify.NotifyMessage;
import com.devops.agent.domain.notify.Notifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 工单升级通知的发送时机测试。
 *
 * <h3>修复的缺陷：事务未提交就把通知发出去了</h3>
 * {@code escalateTicket} 带 {@code @Transactional(rollbackFor = Exception.class)}，
 * 而 {@link Notifier#send} 是<b>立即异步投递</b>、不等事务结束。
 * 原实现在事务内直接调用它，于是：
 *
 * <p>方法末尾的 {@code findById} 或提交阶段任何一步失败 → 事务整体回滚 →
 * <b>库里查无此次升级，钉钉群里却已经 @所有人 通报「工单升级 P0」</b>。</p>
 *
 * <p>被 @ 到的人赶来处理，打开工单发现状态根本没变。
 * 而这种不一致<b>没有任何报错</b>——通知发送成功、事务回滚也"正常"，
 * 两边各自都对，只有合起来看才是错的。事后也无从复现。</p>
 *
 * <h3>为什么必须手工激活事务同步器</h3>
 * 单元测试直接调 service 方法<b>不经过 Spring 的事务代理</b>，
 * {@code TransactionSynchronizationManager} 处于未激活状态，
 * {@code sendAfterCommit} 会走「无事务 → 立即发送」那条兜底分支——
 * 那样这组用例测的就不是要验证的行为了。
 * 故用 {@code initSynchronization()} 模拟「处于事务中」。
 *
 * <p>范式与 {@code TicketAttachmentServiceTest.RemoveAfterCommit} 一致——
 * 删对象存储与发通知是同一类问题：<b>不可撤销的副作用必须排在事务之后</b>。</p>
 *
 * @author OpsBrain AI
 * @since 2026-08-27
 */
@DisplayName("工单升级通知的发送时机")
class TicketEscalateNotifyTest {

    private DevOpsTicketRepository ticketRepository;
    private TicketActivityRepository activityRepository;
    private Notifier notifier;
    private TicketService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ticketRepository = mock(DevOpsTicketRepository.class);
        activityRepository = mock(TicketActivityRepository.class);
        notifier = mock(Notifier.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        service = new TicketService(ticketRepository,
                mock(TicketReplyRepository.class), activityRepository,
                mock(TicketTagRepository.class), mock(TicketActionRepository.class),
                mock(TicketPostmortemRepository.class), notifier, redisTemplate);
    }

    @AfterEach
    void unbind() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private DevOpsTicket ticket() {
        DevOpsTicket t = new DevOpsTicket();
        t.setId("TK-1");
        t.setTitle("核心库主从延迟");
        t.setPriority("P0");
        t.setStatus("PROCESSING");
        t.setAssignee("张三");
        return t;
    }

    // ==================================================================
    // 事务上下文内
    // ==================================================================

    @Nested
    @DisplayName("处于事务中")
    class InTransaction {

        @BeforeEach
        void bindTransaction() {
            // 模拟 @Transactional 已激活同步器。不做这一步，
            // sendAfterCommit 会走「无事务→立即发送」分支，
            // 本组用例就测不到真正要验证的行为
            TransactionSynchronizationManager.initSynchronization();
        }

        @Test
        @DisplayName("事务提交前不发通知——回滚时群里不该出现「已升级」")
        void doesNotSendBeforeCommit() {
            // ── 本类最重要的一条 ──────────────────────────────
            // 方法返回时事务尚未提交。此刻若通知已经发出去，
            // 后续任何一步失败导致回滚，就会出现
            // 「群里通报已升级、库里查无此事」的矛盾，且无任何报错
            when(ticketRepository.findById("TK-1")).thenReturn(ticket());

            service.escalateTicket("TK-1", "影响面扩大", "李四");

            verify(notifier, never()).send(any());
            assertThat(TransactionSynchronizationManager.getSynchronizations())
                    .as("应注册一个 afterCommit 回调，而不是直接发送")
                    .hasSize(1);
        }

        @Test
        @DisplayName("事务提交后才真正发出通知")
        void sendsAfterCommit() {
            when(ticketRepository.findById("TK-1")).thenReturn(ticket());

            service.escalateTicket("TK-1", "影响面扩大", "李四");
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(s -> s.afterCommit());

            verify(notifier).send(any(NotifyMessage.class));
        }

        @Test
        @DisplayName("通知内容含工单号、优先级、升级原因与升级人——缺任一项收到的人都得再去查")
        void messageCarriesActionableContext() {
            // @所有人 的通知如果只说「有工单升级了」，每个被 @ 的人
            // 都要自己去系统里翻是哪一张。这类通知的价值全在于
            // 让人不用打开系统就能判断「要不要我处理」
            when(ticketRepository.findById("TK-1")).thenReturn(ticket());

            service.escalateTicket("TK-1", "影响面扩大", "李四");
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(s -> s.afterCommit());

            ArgumentCaptor<NotifyMessage> cap = ArgumentCaptor.forClass(NotifyMessage.class);
            verify(notifier).send(cap.capture());
            NotifyMessage msg = cap.getValue();

            assertThat(msg.markdown())
                    .contains("TK-1")
                    .contains("P0")
                    .contains("影响面扩大")
                    .contains("李四")
                    .contains("张三");
        }

        @Test
        @DisplayName("升级通知标记为紧急——它要触发 @所有人")
        void escalationIsUrgent() {
            when(ticketRepository.findById("TK-1")).thenReturn(ticket());

            service.escalateTicket("TK-1", "影响面扩大", "李四");
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(s -> s.afterCommit());

            ArgumentCaptor<NotifyMessage> cap = ArgumentCaptor.forClass(NotifyMessage.class);
            verify(notifier).send(cap.capture());
            assertThat(cap.getValue().urgent())
                    .as("升级是「需要更多人关注」的强信号，降级为普通通知会被淹没")
                    .isTrue();
        }

        @Test
        @DisplayName("通知发送抛异常不影响已提交的升级结果")
        void notifierFailureDoesNotBreakEscalation() {
            // 通知是旁路。工单已经升级并提交了，不能因为钉钉限流
            // 而让调用方以为升级失败——那会导致有人重复操作
            when(ticketRepository.findById("TK-1")).thenReturn(ticket());
            doThrow(new RuntimeException("钉钉限流")).when(notifier).send(any());

            service.escalateTicket("TK-1", "影响面扩大", "李四");

            org.assertj.core.api.Assertions.assertThatCode(() ->
                    TransactionSynchronizationManager.getSynchronizations()
                            .forEach(s -> s.afterCommit()))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("参数校验失败时既不写库也不注册通知回调")
        void invalidInputRegistersNothing() {
            assertThatThrownBy(() -> service.escalateTicket("TK-1", "  ", "李四"))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
            verify(ticketRepository, never()).markEscalated(anyString(), anyString(), any());
        }

        @Test
        @DisplayName("工单不存在时不注册通知回调")
        void missingTicketRegistersNothing() {
            when(ticketRepository.findById("NOPE")).thenReturn(null);

            assertThatThrownBy(() -> service.escalateTicket("NOPE", "原因", "李四"))
                    .isInstanceOf(IllegalStateException.class);

            assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
        }
    }

    // ==================================================================
    // 无事务上下文
    // ==================================================================

    @Nested
    @DisplayName("无事务上下文（被非事务方法直接调用）")
    class WithoutTransaction {

        @Test
        @DisplayName("退化为立即发送，而不是静默丢弃")
        void sendsImmediately() {
            // 若这里什么都不做，通知会在某些调用路径上「无声消失」——
            // 比发早了更难查：发早了至少还能看到内容对不上，
            // 而静默丢失是完全没有痕迹的
            when(ticketRepository.findById("TK-1")).thenReturn(ticket());

            service.escalateTicket("TK-1", "影响面扩大", "李四");

            verify(notifier).send(any(NotifyMessage.class));
        }
    }
}
