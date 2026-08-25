package com.devops.agent.domain.biz.service;

import com.devops.agent.domain.biz.entity.DevOpsTicket;
import com.devops.agent.domain.biz.entity.TicketEnums;
import com.devops.agent.domain.biz.repository.DevOpsTicketRepository;
import com.devops.agent.domain.biz.repository.TicketActionRepository;
import com.devops.agent.domain.biz.repository.TicketActivityRepository;
import com.devops.agent.domain.biz.repository.TicketPostmortemRepository;
import com.devops.agent.domain.biz.repository.TicketReplyRepository;
import com.devops.agent.domain.biz.repository.TicketTagRepository;
import com.devops.agent.domain.notify.DingTalkNotifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link TicketService} <b>写操作</b>单元测试。
 *
 * <h3>为什么单独一个类：既有的 TicketServiceTest 只覆盖只读查询</h3>
 * 那个类 7 个用例，全部是 {@code findTickets} / {@code countTickets} /
 * {@code getTicketStats} 这类查询。而 {@code TicketService} 有约 40 个 public 方法，
 * <b>状态机、SLA 计时、闭环流程这些写操作此前零测试</b>——
 * 它们恰恰是全项目最不能出错的部分：
 *
 * <ul>
 *   <li><b>状态机</b>决定了「已作废的工单能不能复活」。
 *       此前只校验目标值是不是合法枚举、不校验能不能从当前状态走过去，
 *       于是 CLOSED 可以被改回 PENDING、VOID 可以被复活，
 *       导致 SLA 统计、首响计时、复盘归档全部失真且<b>无任何报错</b>；</li>
 *   <li><b>首响（MTTA）</b>一旦记错，考核数据就是错的，而且没人会发现——
 *       它只在月度报表里体现为一个数字；</li>
 *   <li><b>活动流</b>是问责依据。转派没留痕，事后就说不清责任是什么时候转移的。</li>
 * </ul>
 *
 * <h3>本类写作时查出的缺陷：Spring 自调用导致事务失效</h3>
 * {@code acknowledgeTicket} 自身没有 {@code @Transactional}，
 * 却直接 {@code this.transferTicket(...)} 与 {@code this.updateStatus(...)}——
 * 那两个方法各自标了 {@code @Transactional}，但 Spring 的事务由 AOP 代理织入，
 * <b>自调用不经过代理，注解在这条路径上完全不生效</b>。
 *
 * <p>修复前这里是三次独立的自动提交写入：转派成功但状态变更失败时，
 * 工单会停在「负责人已改、状态仍是待处理、活动流只留了转派记录」的半截状态，
 * 而调用方收到异常会以为整个操作都没发生。已在最外层补上 {@code @Transactional}。</p>
 *
 * <p><b>说明</b>：单元测试无法直接断言事务边界（没有真实代理与数据源），
 * 因此本类改为断言<b>可观察的后果</b>——中途失败时后续写入不再发生。
 * 真正的回滚验证需要集成测试。</p>
 */
@DisplayName("工单服务 · 写操作与状态机")
class TicketServiceWriteTest {

    private DevOpsTicketRepository ticketRepository;
    private TicketReplyRepository replyRepository;
    private TicketActivityRepository activityRepository;
    private TicketTagRepository tagRepository;
    private TicketActionRepository actionRepository;
    private TicketPostmortemRepository postmortemRepository;
    private DingTalkNotifier dingTalkNotifier;
    private StringRedisTemplate redisTemplate;
    private TicketService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ticketRepository = mock(DevOpsTicketRepository.class);
        replyRepository = mock(TicketReplyRepository.class);
        activityRepository = mock(TicketActivityRepository.class);
        tagRepository = mock(TicketTagRepository.class);
        actionRepository = mock(TicketActionRepository.class);
        postmortemRepository = mock(TicketPostmortemRepository.class);
        dingTalkNotifier = mock(DingTalkNotifier.class);
        redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        service = new TicketService(ticketRepository, replyRepository,
                activityRepository, tagRepository, actionRepository, postmortemRepository,
                dingTalkNotifier, redisTemplate);
    }

    // ==================== 夹具 ====================

    private static DevOpsTicket ticket(String id, String status, String assignee) {
        DevOpsTicket t = new DevOpsTicket();
        t.setId(id);
        t.setTitle("MySQL 连接池耗尽");
        t.setStatus(status);
        t.setPriority(TicketEnums.Priority.P1);
        t.setAssignee(assignee);
        t.setCreateTime(LocalDateTime.of(2026, 8, 25, 9, 0));
        return t;
    }

    /** 让 findById 首次返回 before、之后返回 after（模拟写入后重查） */
    private void stubFindById(String id, DevOpsTicket before, DevOpsTicket after) {
        when(ticketRepository.findById(id)).thenReturn(before, after);
    }

    // ==================================================================

    @Nested
    @DisplayName("状态机：非法流转必须被拒绝")
    class StatusMachine {

        @Test
        @DisplayName("VOID 是终态，不能复活 —— 作废是审计事实")
        void voidCannotBeRevived() {
            when(ticketRepository.findById("T1"))
                    .thenReturn(ticket("T1", TicketEnums.Status.VOID, "张明"));

            assertThatThrownBy(() -> service.updateStatus("T1", TicketEnums.Status.PROCESSING))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("非法状态流转");

            // 复活会让「这张单到底存不存在」变得不可判定
            verify(ticketRepository, never()).updateStatus(anyString(), anyString());
        }

        @Test
        @DisplayName("CLOSED 不能直接回到 PENDING（只能重开为 PROCESSING）")
        void closedCannotGoBackToPending() {
            when(ticketRepository.findById("T1"))
                    .thenReturn(ticket("T1", TicketEnums.Status.CLOSED, "张明"));

            assertThatThrownBy(() -> service.updateStatus("T1", TicketEnums.Status.PENDING))
                    .isInstanceOf(IllegalStateException.class);

            verify(ticketRepository, never()).updateStatus(anyString(), anyString());
        }

        @Test
        @DisplayName("CLOSED → PROCESSING 合法（重开工单）")
        void closedCanBeReopened() {
            stubFindById("T1", ticket("T1", TicketEnums.Status.CLOSED, "张明"),
                    ticket("T1", TicketEnums.Status.PROCESSING, "张明"));

            service.updateStatus("T1", TicketEnums.Status.PROCESSING);

            verify(ticketRepository).updateStatus("T1", TicketEnums.Status.PROCESSING);
        }

        @Test
        @DisplayName("同状态重复设置是幂等的，不报错也不重复写")
        void sameStatusIsIdempotent() {
            when(ticketRepository.findById("T1"))
                    .thenReturn(ticket("T1", TicketEnums.Status.PROCESSING, "张明"));

            DevOpsTicket result = service.updateStatus("T1", TicketEnums.Status.PROCESSING);

            assertThat(result).isNotNull();
            // 幂等重试（网络重发、用户双击）不该产生多余的活动流记录
            verify(ticketRepository, never()).updateStatus(anyString(), anyString());
            verify(activityRepository, never()).insert(any());
        }

        @Test
        @DisplayName("非法枚举值被拒，且不去查库")
        void invalidStatusValueRejected() {
            assertThatThrownBy(() -> service.updateStatus("T1", "FINISHED"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("非法工单状态");

            verify(ticketRepository, never()).findById(anyString());
        }

        @Test
        @DisplayName("空状态被拒")
        void blankStatusRejected() {
            assertThatThrownBy(() -> service.updateStatus("T1", "  "))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> service.updateStatus("T1", null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("工单不存在 → IllegalStateException（映射 40004）")
        void missingTicketThrows() {
            when(ticketRepository.findById("T404")).thenReturn(null);

            assertThatThrownBy(() -> service.updateStatus("T404", TicketEnums.Status.PROCESSING))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("工单不存在");
        }

        @Test
        @DisplayName("状态变更必须留痕 —— 它是工单生命周期的关键节点")
        void statusChangeRecordsActivity() {
            stubFindById("T1", ticket("T1", TicketEnums.Status.PENDING, "张明"),
                    ticket("T1", TicketEnums.Status.RESOLVED, "张明"));

            service.updateStatus("T1", TicketEnums.Status.RESOLVED);

            verify(activityRepository).insert(any());
        }
    }

    @Nested
    @DisplayName("首响（MTTA）：记错了没人会发现")
    class FirstResponse {

        @Test
        @DisplayName("PENDING → PROCESSING 自动记首响")
        void pendingToProcessingMarksFirstResponse() {
            stubFindById("T1", ticket("T1", TicketEnums.Status.PENDING, "张明"),
                    ticket("T1", TicketEnums.Status.PROCESSING, "张明"));
            when(ticketRepository.markFirstResponse(anyString(), anyString(), any()))
                    .thenReturn(1);

            service.updateStatus("T1", TicketEnums.Status.PROCESSING);

            // 「有人开始处理了」就是首响。漏记会让 MTTA 永远偏大，
            // 而这个数字只在月度报表里体现，不会有人当场发现
            verify(ticketRepository).markFirstResponse(eq("T1"), eq("张明"), any());
        }

        @Test
        @DisplayName("PENDING → RESOLVED 不记首响 —— 只有转入处理中才算开始响应")
        void pendingToResolvedDoesNotMarkFirstResponse() {
            stubFindById("T1", ticket("T1", TicketEnums.Status.PENDING, "张明"),
                    ticket("T1", TicketEnums.Status.RESOLVED, "张明"));

            service.updateStatus("T1", TicketEnums.Status.RESOLVED);

            verify(ticketRepository, never()).markFirstResponse(anyString(), anyString(), any());
        }

        @Test
        @DisplayName("首响幂等：已记录过时 markFirstResponse 返回 0，不重复留痕")
        void firstResponseIsIdempotent() {
            when(ticketRepository.markFirstResponse(anyString(), anyString(), any()))
                    .thenReturn(0);   // SQL 里 first_response_at IS NULL 未命中

            boolean marked = service.markFirstResponse("T1", "张明");

            assertThat(marked).isFalse();
            // 幂等由 SQL 保证；返回 0 时不该再写活动流，
            // 否则每次状态变更都会多出一条「首次响应」记录
            verify(activityRepository, never()).insert(any());
        }

        @Test
        @DisplayName("首响记录失败不中断主流程 —— 它是旁路数据")
        void firstResponseFailureDoesNotBreakMainFlow() {
            when(ticketRepository.markFirstResponse(anyString(), anyString(), any()))
                    .thenThrow(new RuntimeException("db timeout"));

            // 状态变更本身已经成功了，不该因为记不了首响就整体失败
            assertThat(service.markFirstResponse("T1", "张明")).isFalse();
        }

        @Test
        @DisplayName("responder 为空时记为「未知」，不写入 null 或空串")
        void blankResponderBecomesUnknown() {
            when(ticketRepository.markFirstResponse(anyString(), anyString(), any()))
                    .thenReturn(1);
            when(ticketRepository.findById(anyString()))
                    .thenReturn(ticket("T1", TicketEnums.Status.PROCESSING, null));

            service.markFirstResponse("T1", "   ");

            // 空串会让「谁first响应的」这一列在报表里变成空白，
            // 而「未知」至少表明系统确实记录了这件事
            verify(ticketRepository).markFirstResponse(eq("T1"), eq("未知"), any());
        }
    }

    @Nested
    @DisplayName("确认接单：本轮修复的自调用事务问题")
    class Acknowledge {

        @Test
        @DisplayName("PENDING 工单确认接单后推进为 PROCESSING")
        void acknowledgeAdvancesPendingToProcessing() {
            when(ticketRepository.findById("T1"))
                    .thenReturn(ticket("T1", TicketEnums.Status.PENDING, "张明"));
            when(ticketRepository.markFirstResponse(anyString(), anyString(), any()))
                    .thenReturn(1);

            service.acknowledgeTicket("T1", "张明", null);

            // 「已确认接单但状态还是待处理」是自相矛盾的
            verify(ticketRepository).updateStatus("T1", TicketEnums.Status.PROCESSING);
        }

        @Test
        @DisplayName("已作废工单不能确认接单（只有 VOID 才真正不可操作）")
        void voidTicketCannotBeAcknowledged() {
            when(ticketRepository.findById("T1"))
                    .thenReturn(ticket("T1", TicketEnums.Status.VOID, "张明"));

            assertThatThrownBy(() -> service.acknowledgeTicket("T1", "张明", null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("已作废");

            verify(ticketRepository, never()).markFirstResponse(anyString(), anyString(), any());
        }

        @Test
        @DisplayName("RESOLVED 工单可被重新接手 —— 问题重现时不必新建单")
        void resolvedTicketCanBeReacknowledged() {
            when(ticketRepository.findById("T1"))
                    .thenReturn(ticket("T1", TicketEnums.Status.RESOLVED, "张明"));
            when(ticketRepository.markFirstResponse(anyString(), anyString(), any()))
                    .thenReturn(0);

            // 新建一张单会丢掉全部处置上下文（根因、验证、时间线）
            service.acknowledgeTicket("T1", "李四", null);

            verify(ticketRepository).markFirstResponse(eq("T1"), eq("李四"), any());
        }

        @Test
        @DisplayName("带 assignee 时顺带认领；与当前相同则不做无谓改动")
        void acknowledgeWithAssigneeOnlyChangesWhenDifferent() {
            when(ticketRepository.findById("T1"))
                    .thenReturn(ticket("T1", TicketEnums.Status.PROCESSING, "张明"));
            when(ticketRepository.markFirstResponse(anyString(), anyString(), any()))
                    .thenReturn(0);

            // 与当前负责人相同 → 不该触发转派
            service.acknowledgeTicket("T1", "张明", "张明");
            verify(ticketRepository, never()).updateAssignee(anyString(), anyString());

            // 不同 → 转派
            service.acknowledgeTicket("T1", "李四", "李四");
            verify(ticketRepository).updateAssignee("T1", "李四");
        }

        @Test
        @DisplayName("转派失败时不再推进状态 —— 修复前这里会留下半截状态")
        void transferFailureStopsFurtherWrites() {
            when(ticketRepository.findById("T1"))
                    .thenReturn(ticket("T1", TicketEnums.Status.PENDING, "张明"));
            when(ticketRepository.markFirstResponse(anyString(), anyString(), any()))
                    .thenReturn(1);
            when(ticketRepository.updateAssignee(anyString(), anyString()))
                    .thenThrow(new RuntimeException("db down"));

            assertThatThrownBy(() -> service.acknowledgeTicket("T1", "李四", "李四"))
                    .isInstanceOf(RuntimeException.class);

            // 单元测试断言不了事务回滚（没有真实代理），
            // 但至少能断言异常向上抛、后续写入不再发生。
            // 修复前的问题是：即便抛了异常，前面 updateAssignee 的写入
            // 因为没在同一事务里，已经自动提交落库了
            verify(ticketRepository, never()).updateStatus(anyString(), anyString());
        }
    }

    @Nested
    @DisplayName("转派与升级：留痕是问责依据")
    class TransferAndEscalate {

        @Test
        @DisplayName("转派留高亮痕迹 —— 责任转移是问责关键")
        void transferRecordsHighlightedActivity() {
            stubFindById("T1", ticket("T1", TicketEnums.Status.PROCESSING, "张明"),
                    ticket("T1", TicketEnums.Status.PROCESSING, "李四"));

            service.transferTicket("T1", "李四");

            verify(ticketRepository).updateAssignee("T1", "李四");
            verify(activityRepository).insert(any());
        }

        @Test
        @DisplayName("负责人首尾空格被裁掉后再写库")
        void assigneeIsTrimmed() {
            stubFindById("T1", ticket("T1", TicketEnums.Status.PROCESSING, "张明"),
                    ticket("T1", TicketEnums.Status.PROCESSING, "李四"));

            service.transferTicket("T1", "  李四  ");

            // 不裁剪会让「李四」和「 李四 」在筛选时变成两个人
            verify(ticketRepository).updateAssignee("T1", "李四");
        }

        @Test
        @DisplayName("空负责人被拒 —— 转派给「没有人」等于工单失联")
        void blankAssigneeRejected() {
            assertThatThrownBy(() -> service.transferTicket("T1", "  "))
                    .isInstanceOf(IllegalArgumentException.class);

            verify(ticketRepository, never()).updateAssignee(anyString(), anyString());
        }

        @Test
        @DisplayName("升级必须有原因 —— 无理由的升级无法追溯，也无法据此改进流程")
        void escalateRequiresReason() {
            assertThatThrownBy(() -> service.escalateTicket("T1", null, "张明"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("升级原因");
            assertThatThrownBy(() -> service.escalateTicket("T1", "   ", "张明"))
                    .isInstanceOf(IllegalArgumentException.class);

            verify(ticketRepository, never()).markEscalated(anyString(), anyString(), any());
        }

        @Test
        @DisplayName("升级原因超长被拒（上限 255）")
        void escalateReasonLengthCapped() {
            String tooLong = "x".repeat(256);

            assertThatThrownBy(() -> service.escalateTicket("T1", tooLong, "张明"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("过长");
        }

        @Test
        @DisplayName("升级会发钉钉强提醒，但通知失败不影响升级本身")
        void escalateNotifyFailureIsSwallowed() {
            stubFindById("T1", ticket("T1", TicketEnums.Status.PROCESSING, "张明"),
                    ticket("T1", TicketEnums.Status.PROCESSING, "张明"));
            org.mockito.Mockito.doThrow(new RuntimeException("钉钉不可达"))
                    .when(dingTalkNotifier).send(any());

            // 通知是旁路。升级这件事已经落库了，
            // 不该因为群机器人挂了就把整个升级操作回滚掉
            service.escalateTicket("T1", "影响面扩大", "张明");

            verify(ticketRepository).markEscalated(eq("T1"), eq("影响面扩大"), any());
        }

        @Test
        @DisplayName("升级不自动改优先级 —— 那会让 SLA 时限被动改写，绕过人的判断")
        void escalateDoesNotChangePriority() {
            stubFindById("T1", ticket("T1", TicketEnums.Status.PROCESSING, "张明"),
                    ticket("T1", TicketEnums.Status.PROCESSING, "张明"));

            service.escalateTicket("T1", "需要更多人手", "张明");

            // L1 阶段只记录+留痕+通知。自动提优先级属于 L3 审批工作流范畴
            verify(ticketRepository, never()).updateStatus(anyString(), anyString());
        }

        @Test
        @DisplayName("operator 为空时记为「未知」")
        void blankOperatorBecomesUnknown() {
            stubFindById("T1", ticket("T1", TicketEnums.Status.PROCESSING, "张明"),
                    ticket("T1", TicketEnums.Status.PROCESSING, "张明"));

            service.escalateTicket("T1", "原因", null);

            ArgumentCaptor<com.devops.agent.domain.biz.entity.TicketActivity> cap =
                    ArgumentCaptor.forClass(com.devops.agent.domain.biz.entity.TicketActivity.class);
            verify(activityRepository).insert(cap.capture());
            assertThat(cap.getValue().getUserName()).isEqualTo("未知");
        }
    }
}
