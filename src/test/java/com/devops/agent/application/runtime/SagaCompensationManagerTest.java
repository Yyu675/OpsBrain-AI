package com.devops.agent.application.runtime;

import com.devops.agent.domain.tools.DevOpsTools;
import com.devops.agent.domain.tools.ToolExecutionRecord;
import com.devops.agent.domain.tools.ToolExecutionState;
import com.devops.agent.infrastructure.persistence.repo.ToolExecutionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.context.ApplicationContext;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SagaCompensationManager} 单元测试。
 *
 * <h3>为什么这个类值得单独测</h3>
 * 补偿是「部分成功」这一最危险状态的唯一收敛手段。它出问题时的共同特征是
 * <b>没有人会立刻发现</b>：主流程早就返回了，补偿在后台默默失败，
 * 脏数据（如一张本该作废的工单）留在库里，直到有人对账才暴露。
 *
 * <p>因此这里重点不在「happy path 能跑通」，而在三类容易被写错、
 * 且写错后完全无声的行为：</p>
 * <ol>
 *   <li><b>补偿失败必须留痕并升级为「需人工介入」</b>——
 *       如果只是 return false，这条脏数据就永远没人管了；</li>
 *   <li><b>反射异常必须解包</b>——{@code Method.invoke} 会用
 *       InvocationTargetException 包住真实异常，不解包的话
 *       运维在库里看到的失败原因是「InvocationTargetException」，
 *       等于没有原因，而真正的「工单不存在」被埋掉了；</li>
 *   <li><b>单步失败不得中断整批补偿</b>——补偿是尽力而为，
 *       第 3 步撤不掉不该连累第 2、1 步继续留着脏数据。</li>
 * </ol>
 *
 * @author OpsBrain AI
 * @since 2026-08-27
 */
@DisplayName("Saga 补偿编排器")
class SagaCompensationManagerTest {

    private ToolExecutionRepository repo;
    private ApplicationContext ctx;
    private DevOpsTools tools;
    private SagaCompensationManager manager;

    @BeforeEach
    void setUp() {
        repo = mock(ToolExecutionRepository.class);
        ctx = mock(ApplicationContext.class);
        tools = mock(DevOpsTools.class);
        when(ctx.getBean(DevOpsTools.class)).thenReturn(tools);
        manager = new SagaCompensationManager(repo, ctx);
    }

    /** 造一条「已成功、可补偿、尚未补偿」的记录 */
    private static ToolExecutionRecord pending(long id, int step, String key) {
        ToolExecutionRecord r = new ToolExecutionRecord();
        r.setId(id);
        r.setStepSeq(step);
        r.setToolName("createDevOpsTicket");
        r.setState(ToolExecutionState.SUCCESS);
        r.setCompensable(true);
        r.setCompensationAction("voidTicket");
        r.setBusinessKey(key);
        return r;
    }

    // ==================== compensateStep ====================

    @Nested
    @DisplayName("单步补偿")
    class Step {

        @Test
        @DisplayName("成功路径：先置 COMPENSATING，调用补偿方法，再标记 COMPENSATED")
        void happyPath() {
            ToolExecutionRecord r = pending(1L, 1, "TK-001");
            when(tools.voidTicket("TK-001")).thenReturn("已作废");

            assertTrue(manager.compensateStep(r));

            // 顺序有意义：先落 COMPENSATING 再调用，是为了进程在调用中途崩溃时
            // 库里留下「补偿中」而不是「成功」——重启后能识别出这条要接着处理。
            // 若反过来先调用再改状态，崩溃后这条记录看起来还是 SUCCESS，
            // 补偿会被重复执行（补偿动作虽幂等，但审计上无从判断到底做没做）
            InOrder order = inOrder(repo, tools);
            order.verify(repo).updateState(1L, ToolExecutionState.COMPENSATING);
            order.verify(tools).voidTicket("TK-001");
            order.verify(repo).markCompensated(1L);

            verify(repo, never()).markCompensationFailed(anyLong(), anyString());
            verify(repo, never()).updateState(1L, ToolExecutionState.MANUAL_INTERVENTION_REQUIRED);
        }

        @Test
        @DisplayName("非法状态迁移直接跳过——不触碰状态，也不调用补偿方法")
        void illegalTransitionIsSkipped() {
            // FAILED 意味着这一步没产生副作用，没有东西需要撤销。
            // 若照样执行补偿，等于对一个不存在的工单执行作废，
            // 补偿会失败并把它错误地升级成「需人工介入」，制造假告警
            ToolExecutionRecord r = pending(2L, 1, "TK-002");
            r.setState(ToolExecutionState.FAILED);

            assertFalse(manager.compensateStep(r));

            verify(repo, never()).updateState(anyLong(), any());
            verify(tools, never()).voidTicket(anyString());
            verify(repo, never()).markCompensated(anyLong());
        }

        @Test
        @DisplayName("已补偿过的记录不会被再次补偿（COMPENSATED 是终态）")
        void alreadyCompensatedIsSkipped() {
            ToolExecutionRecord r = pending(3L, 1, "TK-003");
            r.setState(ToolExecutionState.COMPENSATED);

            assertFalse(manager.compensateStep(r));
            verify(tools, never()).voidTicket(anyString());
        }

        @Test
        @DisplayName("PARTIAL_SUCCESS 可以补偿——它正是最需要补偿的状态")
        void partialSuccessIsCompensable() {
            ToolExecutionRecord r = pending(4L, 1, "TK-004");
            r.setState(ToolExecutionState.PARTIAL_SUCCESS);
            when(tools.voidTicket("TK-004")).thenReturn("ok");

            assertTrue(manager.compensateStep(r));
            verify(repo).markCompensated(4L);
        }

        @Test
        @DisplayName("补偿方法抛异常：留痕 + 升级为「需人工介入」，而不是悄悄返回 false")
        void failureIsRecordedAndEscalated() {
            // ── 本类最重要的一条 ────────────────────────────────
            // 只 return false 的话，这条脏数据就此没人管：
            // 主流程早已返回，补偿在后台失败，工单还在库里，
            // 直到有人对账才发现。必须落库 + 升级状态，
            // 才能被 listNeedingAttention 捞出来推到运维看板
            ToolExecutionRecord r = pending(5L, 2, "TK-005");
            when(tools.voidTicket("TK-005"))
                    .thenThrow(new RuntimeException("工单不存在"));

            assertFalse(manager.compensateStep(r));

            InOrder order = inOrder(repo);
            order.verify(repo).updateState(5L, ToolExecutionState.COMPENSATING);
            order.verify(repo).markCompensationFailed(eq(5L), anyString());
            order.verify(repo).updateState(5L, ToolExecutionState.MANUAL_INTERVENTION_REQUIRED);
            verify(repo, never()).markCompensated(anyLong());
        }

        @Test
        @DisplayName("反射异常被解包——落库的是真实根因，不是 InvocationTargetException")
        void rootCauseIsUnwrapped() {
            // Method.invoke 会把业务异常包进 InvocationTargetException。
            // 不解包的话运维在 compensation_error 里看到的是
            // 「InvocationTargetException」——等于没有原因，
            // 而真正的「工单 TK-006 不存在」被埋掉了，排查从这里断线
            ToolExecutionRecord r = pending(6L, 1, "TK-006");
            when(tools.voidTicket("TK-006"))
                    .thenThrow(new IllegalStateException("工单 TK-006 不存在"));

            manager.compensateStep(r);

            ArgumentCaptor<String> err = ArgumentCaptor.forClass(String.class);
            verify(repo).markCompensationFailed(eq(6L), err.capture());
            assertTrue(err.getValue().contains("工单 TK-006 不存在"),
                    "落库的失败原因必须包含业务消息，实际=" + err.getValue());
            assertFalse(err.getValue().contains("InvocationTargetException"),
                    "反射包装类不该出现在给人看的失败原因里，实际=" + err.getValue());
            assertTrue(err.getValue().contains("IllegalStateException"),
                    "异常类型仍需保留，用于区分业务失败与系统故障");
        }

        @Test
        @DisplayName("补偿方法名写错：报错点名签名要求，而不是一句「补偿失败」")
        void missingCompensationMethodIsDescriptive() {
            // 这是配置错误（@ToolMeta 里 compensationAction 拼错），
            // 与运行时故障的处置方式完全不同：前者要改代码，后者可以重试。
            // 错误信息里必须能看出「方法根本不存在」，否则会被当成偶发失败反复重试
            ToolExecutionRecord r = pending(7L, 1, "TK-007");
            r.setCompensationAction("voidTicketTypo");

            assertFalse(manager.compensateStep(r));

            ArgumentCaptor<String> err = ArgumentCaptor.forClass(String.class);
            verify(repo).markCompensationFailed(eq(7L), err.capture());
            assertTrue(err.getValue().contains("voidTicketTypo"),
                    "错误信息必须点名是哪个方法找不到，实际=" + err.getValue());
        }

        @Test
        @DisplayName("工具 Bean 取不到时补偿失败但不抛异常——批量补偿不能被一步带崩")
        void missingToolBeanFailsGracefully() {
            when(ctx.getBean(DevOpsTools.class))
                    .thenThrow(new NoSuchBeanDefinitionException("DevOpsTools"));
            ToolExecutionRecord r = pending(8L, 1, "TK-008");

            assertFalse(manager.compensateStep(r));
            verify(repo).markCompensationFailed(eq(8L), anyString());
            verify(repo).updateState(8L, ToolExecutionState.MANUAL_INTERVENTION_REQUIRED);
        }

        @Test
        @DisplayName("超长错误信息被截断——compensation_error 列有长度上限，写超会整条更新失败")
        void longErrorIsTruncated() {
            // 若不截断，UPDATE 会因超长而报错，被 repo 吞掉返回 0，
            // 结果是「补偿失败」这件事本身也没记下来——最坏的组合
            ToolExecutionRecord r = pending(9L, 1, "TK-009");
            when(tools.voidTicket("TK-009"))
                    .thenThrow(new RuntimeException("x".repeat(2000)));

            manager.compensateStep(r);

            ArgumentCaptor<String> err = ArgumentCaptor.forClass(String.class);
            verify(repo).markCompensationFailed(eq(9L), err.capture());
            assertTrue(err.getValue().length() <= 501,
                    "错误信息应截断到 500 字符（+1 位省略号），实际长度=" + err.getValue().length());
        }
    }

    // ==================== compensateSaga ====================

    @Nested
    @DisplayName("整体补偿")
    class Saga {

        @Test
        @DisplayName("sagaId 为空返回 noop，且不查库")
        void blankSagaIdIsNoop() {
            SagaCompensationManager.CompensationResult r1 = manager.compensateSaga(null, "x");
            SagaCompensationManager.CompensationResult r2 = manager.compensateSaga("  ", "x");

            assertEquals(0, r1.compensatedCount());
            assertEquals(0, r2.compensatedCount());
            assertTrue(r1.isFullySucceeded());
            verify(repo, never()).findCompensableBySagaDesc(any());
        }

        @Test
        @DisplayName("无待补偿步骤时返回 noop，且不判定为需人工介入")
        void noPendingStepsIsNoop() {
            when(repo.findCompensableBySagaDesc("S1")).thenReturn(List.of());

            SagaCompensationManager.CompensationResult r = manager.compensateSaga("S1", "后续步骤失败");

            assertEquals(0, r.compensatedCount());
            assertEquals(0, r.failedCount());
            assertFalse(r.needsManualIntervention(),
                    "「没什么要撤销的」不是异常，误报成需人工介入会淹没真正的告警");
        }

        @Test
        @DisplayName("按仓储给出的逆序依次补偿——后执行的先撤销")
        void compensatesInReverseOrder() {
            // 逆序是 Saga 铁律：第 3 步依赖第 2 步的产物，
            // 先撤第 2 步会让第 3 步的补偿因前置数据已消失而失败
            ToolExecutionRecord s3 = pending(30L, 3, "TK-C");
            ToolExecutionRecord s2 = pending(20L, 2, "TK-B");
            ToolExecutionRecord s1 = pending(10L, 1, "TK-A");
            when(repo.findCompensableBySagaDesc("S2")).thenReturn(List.of(s3, s2, s1));
            when(tools.voidTicket(anyString())).thenReturn("ok");

            SagaCompensationManager.CompensationResult r = manager.compensateSaga("S2", "回滚");

            InOrder order = inOrder(tools);
            order.verify(tools).voidTicket("TK-C");
            order.verify(tools).voidTicket("TK-B");
            order.verify(tools).voidTicket("TK-A");
            assertEquals(3, r.compensatedCount());
            assertTrue(r.isFullySucceeded());
        }

        @Test
        @DisplayName("单步失败不中断后续补偿——尽力而为，最大化清理脏数据")
        void singleFailureDoesNotAbortRest() {
            ToolExecutionRecord s3 = pending(30L, 3, "TK-C");
            ToolExecutionRecord s2 = pending(20L, 2, "TK-B");
            ToolExecutionRecord s1 = pending(10L, 1, "TK-A");
            when(repo.findCompensableBySagaDesc("S3")).thenReturn(List.of(s3, s2, s1));
            when(tools.voidTicket("TK-C")).thenReturn("ok");
            when(tools.voidTicket("TK-B")).thenThrow(new RuntimeException("下游超时"));
            when(tools.voidTicket("TK-A")).thenReturn("ok");

            SagaCompensationManager.CompensationResult r = manager.compensateSaga("S3", "回滚");

            // 若实现里用了 break/return，TK-A 这条脏数据会永远留下，
            // 而结果里也看不出它没被处理——两笔脏数据只报一笔
            verify(tools).voidTicket("TK-A");
            assertEquals(2, r.compensatedCount());
            assertEquals(1, r.failedCount());
            assertTrue(r.needsManualIntervention());
            assertFalse(r.isFullySucceeded());
        }

        @Test
        @DisplayName("结果标签带上工具名/步骤号/业务键——运维据此定位到具体那张单")
        void labelsIdentifyTheStep() {
            ToolExecutionRecord s2 = pending(20L, 2, "TK-B");
            when(repo.findCompensableBySagaDesc("S4")).thenReturn(List.of(s2));
            when(tools.voidTicket("TK-B")).thenThrow(new RuntimeException("boom"));

            SagaCompensationManager.CompensationResult r = manager.compensateSaga("S4", "回滚");

            assertEquals(1, r.failed().size());
            String label = r.failed().get(0);
            assertTrue(label.contains("createDevOpsTicket") && label.contains("2")
                            && label.contains("TK-B"),
                    "标签缺少定位信息就只能人工翻库，实际=" + label);
        }

        @Test
        @DisplayName("不满足待补偿条件的记录被跳过（如缺 businessKey）")
        void nonPendingRecordsAreSkipped() {
            // 缺 businessKey 时补偿动作没有入参可用，调用只会以 null 打过去，
            // 轻则失败重则误伤别的数据。这类记录应当跳过并留给人工
            ToolExecutionRecord broken = pending(40L, 1, null);
            ToolExecutionRecord good = pending(41L, 2, "TK-D");
            when(repo.findCompensableBySagaDesc("S5")).thenReturn(List.of(good, broken));
            when(tools.voidTicket("TK-D")).thenReturn("ok");

            SagaCompensationManager.CompensationResult r = manager.compensateSaga("S5", "回滚");

            verify(tools, times(1)).voidTicket(anyString());
            assertEquals(1, r.compensatedCount());
            assertEquals(0, r.failedCount());
        }

        @Test
        @DisplayName("已有 compensatedAt 的记录被跳过——补偿具备幂等性")
        void alreadyCompensatedRecordsAreSkipped() {
            ToolExecutionRecord done = pending(50L, 1, "TK-E");
            done.setCompensatedAt(LocalDateTime.now());
            when(repo.findCompensableBySagaDesc("S6")).thenReturn(List.of(done));

            SagaCompensationManager.CompensationResult r = manager.compensateSaga("S6", "回滚");

            verify(tools, never()).voidTicket(anyString());
            assertEquals(0, r.compensatedCount());
            assertEquals(0, r.failedCount());
        }

        @Test
        @DisplayName("状态更新落库失败时仍继续补偿——审计写不进去不该阻止清理脏数据")
        void repositoryFailureDoesNotBlockCompensation() {
            // repo 的写方法内部已吞异常返回 0，这里额外验证即使它抛出来，
            // 补偿流程也不会因为「记不下来」而放弃「做不做」
            ToolExecutionRecord r1 = pending(60L, 1, "TK-F");
            when(repo.findCompensableBySagaDesc("S7")).thenReturn(List.of(r1));
            doThrow(new RuntimeException("库连接断了"))
                    .when(repo).updateState(60L, ToolExecutionState.COMPENSATING);

            SagaCompensationManager.CompensationResult r = manager.compensateSaga("S7", "回滚");

            // 状态写不进去 → 这一步算失败并升级人工，但整批流程不得抛异常中断
            assertEquals(1, r.failedCount());
            assertTrue(r.needsManualIntervention());
        }
    }

    // ==================== 观测查询 ====================

    @Nested
    @DisplayName("观测查询")
    class Observability {

        @Test
        @DisplayName("listNeedingAttention 原样透传 limit——看板分页依赖它")
        void listNeedingAttentionDelegates() {
            when(repo.findNeedingAttention(20)).thenReturn(List.of(pending(1L, 1, "TK-1")));
            assertEquals(1, manager.listNeedingAttention(20).size());
            verify(repo).findNeedingAttention(20);
        }

        @Test
        @DisplayName("listSagaSteps 用正序查询（回放要按发生顺序看，不是补偿顺序）")
        void listSagaStepsUsesAscendingQuery() {
            when(repo.findBySaga("S9")).thenReturn(List.of());
            manager.listSagaSteps("S9");
            verify(repo).findBySaga("S9");
            verify(repo, never()).findCompensableBySagaDesc("S9");
        }
    }
}
