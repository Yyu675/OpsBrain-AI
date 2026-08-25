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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link TicketService} <b>B2/B3 闭环流程</b>单元测试。
 *
 * <h3>这条链路直接决定考核数据的真假</h3>
 * 处置阶段 → 止损 → 根因确认 → 修复验证，每一步都在往工单上盖时间戳，
 * 而这些时间戳是 MTTA / MTTM / MTTR 三个核心指标的计算依据：
 *
 * <ul>
 *   <li>{@code mitigated_at} —— MTTM（止损耗时）的终点；</li>
 *   <li>{@code verified_at} —— <b>MTTR 的终点</b>；</li>
 *   <li>{@code verify_skipped} —— 决定这张工单<b>算不算进 MTTR</b>。</li>
 * </ul>
 *
 * <h3>最关键的一条：跳过验证必须被排除在 MTTR 之外</h3>
 * D3 决策允许跳过验证（真实运维里确有无法验证的场景），
 * 但强制填理由，并且置 {@code verify_skipped=true}。
 *
 * <p>如果这个标记没打上，「点一下已解决」就能刷低 MTTR——
 * <b>考核指标会被一个按钮直接操纵</b>，而报表上完全看不出异常。
 * 这不是理论风险：MTTR 是运维团队最常被考核的数字。</p>
 *
 * <h3>另一条：止损时刻只记一次</h3>
 * {@code mitigated_at} 仅在尚未记过时写入。反复切换处置阶段
 * （排查 → 止损 → 修复 → 又退回止损）是真实运维的常态，
 * 每次都覆盖时间戳的话，MTTM 会随着操作次数不断变长——
 * 处理得越仔细、来回确认越多，数据反而越难看。
 */
@DisplayName("工单服务 · B2/B3 闭环流程与指标口径")
class TicketClosureFlowTest {

    private DevOpsTicketRepository ticketRepository;
    private TicketActivityRepository activityRepository;
    private TicketService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ticketRepository = mock(DevOpsTicketRepository.class);
        TicketReplyRepository replyRepository = mock(TicketReplyRepository.class);
        activityRepository = mock(TicketActivityRepository.class);
        TicketTagRepository tagRepository = mock(TicketTagRepository.class);
        TicketActionRepository actionRepository = mock(TicketActionRepository.class);
        TicketPostmortemRepository postmortemRepository = mock(TicketPostmortemRepository.class);
        DingTalkNotifier dingTalkNotifier = mock(DingTalkNotifier.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        service = new TicketService(ticketRepository, replyRepository,
                activityRepository, tagRepository, actionRepository, postmortemRepository,
                dingTalkNotifier, redisTemplate);
    }

    private static DevOpsTicket ticket(String id, String status) {
        DevOpsTicket t = new DevOpsTicket();
        t.setId(id);
        t.setTitle("MySQL 连接池耗尽");
        t.setStatus(status);
        t.setPriority(TicketEnums.Priority.P1);
        t.setAssignee("张明");
        t.setCreateTime(LocalDateTime.of(2026, 8, 25, 9, 0));
        return t;
    }

    /** 捕获传给 repository.update 的实体（业务字段都写在它上面） */
    private DevOpsTicket capturedUpdate() {
        ArgumentCaptor<DevOpsTicket> cap = ArgumentCaptor.forClass(DevOpsTicket.class);
        verify(ticketRepository).update(cap.capture());
        return cap.getValue();
    }

    // ==================================================================

    @Nested
    @DisplayName("处置阶段")
    class Stage {

        @Test
        @DisplayName("阶段允许跳跃与回退 —— 强制线性会让用户绕过系统")
        void stageCanJumpAndGoBack() {
            when(ticketRepository.findById("T1"))
                    .thenReturn(ticket("T1", TicketEnums.Status.PROCESSING));

            // 排查中直接跳到已止损（跳过修复中）
            service.updateStage("T1", TicketService.STAGE_MITIGATED, "张明");
            assertThat(capturedUpdate().getHandlingStage())
                    .isEqualTo(TicketService.STAGE_MITIGATED);
        }

        @Test
        @DisplayName("非法阶段被拒，且不查库")
        void invalidStageRejected() {
            assertThatThrownBy(() -> service.updateStage("T1", "DONE", "张明"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("非法处置阶段");

            verify(ticketRepository, never()).findById(anyString());
        }

        @Test
        @DisplayName("已作废工单不能切换处置阶段")
        void voidTicketRejectsStageChange() {
            when(ticketRepository.findById("T1"))
                    .thenReturn(ticket("T1", TicketEnums.Status.VOID));

            assertThatThrownBy(() ->
                    service.updateStage("T1", TicketService.STAGE_FIXING, "张明"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("已作废");

            verify(ticketRepository, never()).update(any());
        }

        @Test
        @DisplayName("RESOLVED 工单可退回 FIXING —— 验证失败必须能回到修复中")
        void resolvedCanGoBackToFixing() {
            when(ticketRepository.findById("T1"))
                    .thenReturn(ticket("T1", TicketEnums.Status.RESOLVED));

            service.updateStage("T1", TicketService.STAGE_FIXING, "张明");

            // 挡住这条路径会让「验证没过」的工单无处可去，
            // 用户只能新建一张单，从而丢掉全部处置上下文
            assertThat(capturedUpdate().getHandlingStage())
                    .isEqualTo(TicketService.STAGE_FIXING);
        }

        @Test
        @DisplayName("PENDING 工单切阶段时同步推进为 PROCESSING 并记首响")
        void pendingAdvancesWhenStageChanges() {
            when(ticketRepository.findById("T1"))
                    .thenReturn(ticket("T1", TicketEnums.Status.PENDING));
            when(ticketRepository.markFirstResponse(anyString(), anyString(), any()))
                    .thenReturn(1);

            service.updateStage("T1", TicketService.STAGE_TRIAGE, "张明");

            // 「处置阶段是排查中，但工单状态还是待处理」是自相矛盾的
            verify(ticketRepository).updateStatus("T1", TicketEnums.Status.PROCESSING);
            verify(ticketRepository).markFirstResponse(eq("T1"), eq("张明"), any());
        }

        @Test
        @DisplayName("进入 MITIGATED 记录止损时刻")
        void enteringMitigatedStampsTime() {
            when(ticketRepository.findById("T1"))
                    .thenReturn(ticket("T1", TicketEnums.Status.PROCESSING));

            service.updateStage("T1", TicketService.STAGE_MITIGATED, "张明");

            // mitigated_at 是 MTTM 的终点，不打上这个戳就永远算不出止损耗时
            assertThat(capturedUpdate().getMitigatedAt()).isNotNull();
        }

        @Test
        @DisplayName("止损时刻只记一次 —— 反复切阶段不该让 MTTM 越算越长")
        void mitigatedAtIsStampedOnlyOnce() {
            DevOpsTicket existing = ticket("T1", TicketEnums.Status.PROCESSING);
            LocalDateTime original = LocalDateTime.of(2026, 8, 25, 9, 30);
            existing.setMitigatedAt(original);
            when(ticketRepository.findById("T1")).thenReturn(existing);

            service.updateStage("T1", TicketService.STAGE_MITIGATED, "张明");

            // 「排查→止损→修复→又退回止损」是真实运维的常态。
            // 每次都覆盖的话，处理得越仔细、来回确认越多，MTTM 数据反而越难看
            assertThat(capturedUpdate().getMitigatedAt()).isEqualTo(original);
        }

        @Test
        @DisplayName("markMitigated 等价于切到 MITIGATED 阶段")
        void markMitigatedDelegatesToStage() {
            when(ticketRepository.findById("T1"))
                    .thenReturn(ticket("T1", TicketEnums.Status.PROCESSING));

            service.markMitigated("T1", "张明");

            assertThat(capturedUpdate().getHandlingStage())
                    .isEqualTo(TicketService.STAGE_MITIGATED);
        }

        @Test
        @DisplayName("阶段值大小写与空格归一化")
        void stageIsNormalized() {
            when(ticketRepository.findById("T1"))
                    .thenReturn(ticket("T1", TicketEnums.Status.PROCESSING));

            service.updateStage("T1", "  fixing  ", "张明");

            assertThat(capturedUpdate().getHandlingStage())
                    .isEqualTo(TicketService.STAGE_FIXING);
        }
    }

    @Nested
    @DisplayName("根因确认（人工确认，≠ AI 建议）")
    class RootCause {

        @Test
        @DisplayName("确认根因写入内容、分类、确认人与时间")
        void confirmRootCauseStampsAllFields() {
            when(ticketRepository.findById("T1"))
                    .thenReturn(ticket("T1", TicketEnums.Status.PROCESSING));

            service.confirmRootCause("T1", "连接未归还导致池耗尽", "CODE", "张明");

            DevOpsTicket saved = capturedUpdate();
            assertThat(saved.getRootCause()).isEqualTo("连接未归还导致池耗尽");
            assertThat(saved.getRootCauseCategory()).isEqualTo("CODE");
            assertThat(saved.getRootCauseBy()).isEqualTo("张明");
            // 没有确认时间，就无法区分「一直没定根因」与「早就定了」
            assertThat(saved.getRootCauseAt()).isNotNull();
        }

        @Test
        @DisplayName("分类省略时归为 UNKNOWN，而不是留空")
        void blankCategoryBecomesUnknown() {
            when(ticketRepository.findById("T1"))
                    .thenReturn(ticket("T1", TicketEnums.Status.PROCESSING));

            service.confirmRootCause("T1", "原因待查", "  ", "张明");

            // 留空会让「哪类根因最多」的聚合分析漏掉这些工单，
            // 而 UNKNOWN 本身就是一个有价值的信号：它多说明定位能力不足
            assertThat(capturedUpdate().getRootCauseCategory()).isEqualTo("UNKNOWN");
        }

        @Test
        @DisplayName("非法分类被拒 —— 词表是聚合分析的基础")
        void invalidCategoryRejected() {
            when(ticketRepository.findById("T1"))
                    .thenReturn(ticket("T1", TicketEnums.Status.PROCESSING));

            assertThatThrownBy(() ->
                    service.confirmRootCause("T1", "原因", "BUG", "张明"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("非法根因分类");

            verify(ticketRepository, never()).update(any());
        }

        @Test
        @DisplayName("分类大小写归一化（code → CODE）")
        void categoryIsUpperCased() {
            when(ticketRepository.findById("T1"))
                    .thenReturn(ticket("T1", TicketEnums.Status.PROCESSING));

            service.confirmRootCause("T1", "原因", "code", "张明");

            assertThat(capturedUpdate().getRootCauseCategory()).isEqualTo("CODE");
        }

        @Test
        @DisplayName("空根因被拒")
        void blankRootCauseRejected() {
            assertThatThrownBy(() ->
                    service.confirmRootCause("T1", "  ", "CODE", "张明"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("根因不能为空");
        }

        @Test
        @DisplayName("超长根因被拒（上限 10000）")
        void oversizedRootCauseRejected() {
            assertThatThrownBy(() ->
                    service.confirmRootCause("T1", "x".repeat(10001), "CODE", "张明"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("过长");
        }

        @Test
        @DisplayName("根因确认留高亮痕迹（长文本截断展示）")
        void confirmRootCauseRecordsHighlightedActivity() {
            when(ticketRepository.findById("T1"))
                    .thenReturn(ticket("T1", TicketEnums.Status.PROCESSING));

            service.confirmRootCause("T1", "x".repeat(200), "CODE", "张明");

            ArgumentCaptor<com.devops.agent.domain.biz.entity.TicketActivity> cap =
                    ArgumentCaptor.forClass(com.devops.agent.domain.biz.entity.TicketActivity.class);
            verify(activityRepository).insert(cap.capture());
            // 活动流里塞 200 字正文会把时间线撑爆，截断到 60 字 + 省略号
            assertThat(cap.getValue().getDetail()).contains("…");
        }
    }

    @Nested
    @DisplayName("修复验证：MTTR 口径的守门人")
    class Verification {

        @Test
        @DisplayName("验证通过写入方式、结论、验证人与 verified_at（MTTR 终点）")
        void verificationStampsMttrEnd() {
            when(ticketRepository.findById("T1"))
                    .thenReturn(ticket("T1", TicketEnums.Status.PROCESSING));

            service.submitVerification("T1", "MONITOR", "指标已回落", "李四");

            DevOpsTicket saved = capturedUpdate();
            assertThat(saved.getVerifyMethod()).isEqualTo("MONITOR");
            assertThat(saved.getVerifyConclusion()).isEqualTo("指标已回落");
            assertThat(saved.getVerifier()).isEqualTo("李四");
            assertThat(saved.getVerifiedAt()).isNotNull();
        }

        @Test
        @DisplayName("验证通过后转 RESOLVED —— 验证通过意味着问题已确认解决")
        void verificationAdvancesToResolved() {
            when(ticketRepository.findById("T1"))
                    .thenReturn(ticket("T1", TicketEnums.Status.PROCESSING));

            service.submitVerification("T1", "MONITOR", "ok", "李四");

            verify(ticketRepository).updateStatus("T1", TicketEnums.Status.RESOLVED);
        }

        @Test
        @DisplayName("RESOLVED 工单可以补做验证，且不重复改状态")
        void resolvedTicketCanStillBeVerified() {
            // 这条用例最初写成断言「不重复改状态」，CI 却报
            // 「工单已终结，无法验证」——由此查出 isTerminalStatus() 被误用作
            // 操作门禁，把 RESOLVED 也挡在了门外。
            //
            // 「先标已解决、后补做验证」是真实流程（当时忙着救火，事后补录），
            // 挡住它等于逼用户重开工单，而重开会污染状态流转历史。
            // 修复后这条路径放行，同时下面那句「已是 RESOLVED 就不改状态」
            // 才真正可达——修复前它是永远为真的死代码
            when(ticketRepository.findById("T1"))
                    .thenReturn(ticket("T1", TicketEnums.Status.RESOLVED));

            service.submitVerification("T1", "MONITOR", "ok", "李四");

            verify(ticketRepository).update(any());
            verify(ticketRepository, never()).updateStatus(anyString(), anyString());
        }

        @Test
        @DisplayName("正式验证必须清掉跳过标记 —— 否则这张单会被永久排除在 MTTR 之外")
        void verificationClearsSkipFlag() {
            DevOpsTicket existing = ticket("T1", TicketEnums.Status.PROCESSING);
            existing.setVerifySkipped(true);
            existing.setVerifySkipReason("当时无法验证");
            when(ticketRepository.findById("T1")).thenReturn(existing);

            service.submitVerification("T1", "BUSINESS", "业务已恢复", "李四");

            DevOpsTicket saved = capturedUpdate();
            // 先跳过、后补做验证是常见流程。若不清标记，
            // 这张工单虽然真的验证过了，却永远不计入 MTTR，统计口径就漏了
            assertThat(saved.getVerifySkipped()).isFalse();
            assertThat(saved.getVerifySkipReason()).isNull();
        }

        @Test
        @DisplayName("非法验证方式被拒")
        void invalidMethodRejected() {
            assertThatThrownBy(() ->
                    service.submitVerification("T1", "GUESS", "凭感觉", "李四"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("非法验证方式");
        }

        @Test
        @DisplayName("空验证方式被拒")
        void blankMethodRejected() {
            assertThatThrownBy(() ->
                    service.submitVerification("T1", "  ", "结论", "李四"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("已作废工单不能验证（只有 VOID 才真正不可操作）")
        void voidTicketCannotBeVerified() {
            when(ticketRepository.findById("T1"))
                    .thenReturn(ticket("T1", TicketEnums.Status.VOID));

            assertThatThrownBy(() ->
                    service.submitVerification("T1", "MONITOR", "ok", "李四"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("已作废");
        }
    }

    @Nested
    @DisplayName("跳过验证：一个按钮能不能操纵考核指标")
    class SkipVerification {

        @Test
        @DisplayName("跳过必须置 verify_skipped=true —— 这是 MTTR 不被操纵的唯一保证")
        void skipMarksFlagForMttrExclusion() {
            when(ticketRepository.findById("T1"))
                    .thenReturn(ticket("T1", TicketEnums.Status.PROCESSING));

            service.skipVerification("T1", "灰度环境已下线，无法复现验证", "张明");

            DevOpsTicket saved = capturedUpdate();
            // 标记没打上的话，「点一下已解决」就能刷低 MTTR——
            // 考核指标被一个按钮直接操纵，而报表上完全看不出异常
            assertThat(saved.getVerifySkipped()).isTrue();
            assertThat(saved.getVerifySkipReason()).isEqualTo("灰度环境已下线，无法复现验证");
        }

        @Test
        @DisplayName("跳过理由必填 —— 同 purge 的 complianceReason 做法")
        void skipRequiresReason() {
            assertThatThrownBy(() -> service.skipVerification("T1", null, "张明"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("理由不能为空");
            assertThatThrownBy(() -> service.skipVerification("T1", "   ", "张明"))
                    .isInstanceOf(IllegalArgumentException.class);

            verify(ticketRepository, never()).update(any());
        }

        @Test
        @DisplayName("跳过理由超长被拒（上限 255）")
        void skipReasonLengthCapped() {
            assertThatThrownBy(() ->
                    service.skipVerification("T1", "x".repeat(256), "张明"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("过长");
        }

        @Test
        @DisplayName("跳过后仍转 RESOLVED，但与正式验证在统计上必须可区分")
        void skipStillResolvesButIsDistinguishable() {
            when(ticketRepository.findById("T1"))
                    .thenReturn(ticket("T1", TicketEnums.Status.PROCESSING));

            service.skipVerification("T1", "无法复现", "张明");

            // 状态上等同「已解决」——用户视角问题确实处理完了
            verify(ticketRepository).updateStatus("T1", TicketEnums.Status.RESOLVED);
            // 但统计上必须能区分，靠的就是 verify_skipped 这个布尔
            assertThat(capturedUpdate().getVerifySkipped()).isTrue();
        }

        @Test
        @DisplayName("已作废工单不能跳过验证")
        void voidTicketCannotSkip() {
            when(ticketRepository.findById("T1"))
                    .thenReturn(ticket("T1", TicketEnums.Status.VOID));

            assertThatThrownBy(() -> service.skipVerification("T1", "理由", "张明"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("已作废");
        }

        @Test
        @DisplayName("operator 为空记为「未知」，不留空白验证人")
        void blankOperatorBecomesUnknown() {
            when(ticketRepository.findById("T1"))
                    .thenReturn(ticket("T1", TicketEnums.Status.PROCESSING));

            service.skipVerification("T1", "无法复现", null);

            assertThat(capturedUpdate().getVerifier()).isEqualTo("未知");
        }
    }
}
