package com.devops.agent.application.impl;

import com.devops.agent.application.memory.AgentMemoryManager;
import com.devops.agent.application.runtime.AgentStateManager;
import com.devops.agent.application.context.ContextBudgetManager;
import com.devops.agent.application.runtime.CostQuotaManager;
import com.devops.agent.application.runtime.SagaCompensationManager;
import com.devops.agent.application.runtime.TicketDraftParser;
import com.devops.agent.application.runtime.ToolRuntimeManager;
import com.devops.agent.domain.approval.ApprovalService;
import com.devops.agent.domain.biz.service.AgentLogService;
import com.devops.agent.domain.biz.service.TicketService;
import com.devops.agent.domain.rag.KnowledgeScopeResolver;
import com.devops.agent.domain.tools.TicketDraft;
import com.devops.agent.domain.tools.ToolExecutionRecord;
import com.devops.agent.domain.tools.ToolExecutionState;
import com.devops.agent.infrastructure.persistence.repo.ToolExecutionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Single Writer 写入路径测试（{@code writeTicketFromDraft}）。
 *
 * <h3>为什么专测这一段</h3>
 * 它是 <b>AI 建单的唯一写入点</b>（P1-3 Single Writer），
 * 四条分支交织：审批拦截 / Saga 登记 / 落库 / 结果回写。
 * 此前只有集成测试间接覆盖，而集成测试跑的是「顺利建单」那一条，
 * 另外三条失败分支从未被验证过。
 *
 * <h3>这段代码的特殊之处：它的输出会被模型当成事实复述给用户</h3>
 * 每个分支都返回一段给模型看的提示文本。这些文本不是日志，
 * 而是<b>模型下一轮回答的依据</b>——写错的后果不是「日志难看」，
 * 而是<b>模型会对用户说谎</b>：
 * <ul>
 *   <li>审批单提交失败却回「已进入审批队列」→ 用户等一个永远不会来的审批；</li>
 *   <li>落库失败却回「工单创建成功」→ 用户拿着一个不存在的工单号去催进度。</li>
 * </ul>
 * 所以本测试大量断言<b>返回文本里必须/不得出现某些字眼</b>，
 * 这不是在测措辞，是在测「系统会不会让 AI 撒谎」。
 *
 * <h3>顺序为什么重要</h3>
 * 类注释写明「先登记 Saga 步骤再写库」，理由是反序会留下
 * 无补偿记录的孤儿工单。这条顺序用 {@link InOrder} 锁住。
 *
 * @author OpsBrain AI
 * @since 2026-08-27
 */
@DisplayName("Single Writer 工单写入路径")
class SingleWriterPathTest {

    private TicketService ticketService;
    private ToolExecutionRepository toolExecRepo;
    private ApprovalService approvalService;
    private AgentStateManager stateManager;
    private DevOpsAgentServiceImpl service;

    /** 反射调用 private 的 writeTicketFromDraft，返回其 record 结果 */
    private Object invokeWrite(TicketDraft draft, String traceId, String sessionId) throws Exception {
        Method m = DevOpsAgentServiceImpl.class.getDeclaredMethod(
                "writeTicketFromDraft", TicketDraft.class, String.class, String.class);
        m.setAccessible(true);
        return m.invoke(service, draft, traceId, sessionId);
    }

    /** 从 WriteOutcome record 里取字段（它是 private record，只能反射读） */
    private Object outcomeField(Object outcome, String field) throws Exception {
        Method accessor = outcome.getClass().getDeclaredMethod(field);
        accessor.setAccessible(true);
        return accessor.invoke(outcome);
    }

    private String ticketIdOf(Object outcome) throws Exception {
        return (String) outcomeField(outcome, "ticketId");
    }

    private String messageOf(Object outcome) throws Exception {
        return (String) outcomeField(outcome, "message");
    }

    @BeforeEach
    void setUp() {
        ticketService = mock(TicketService.class);
        toolExecRepo = mock(ToolExecutionRepository.class);
        approvalService = mock(ApprovalService.class);
        stateManager = mock(AgentStateManager.class);
        ToolRuntimeManager toolRuntimeManager = mock(ToolRuntimeManager.class);

        service = new DevOpsAgentServiceImpl(
                mock(com.devops.agent.common.guard.SecurityInputGuard.class),
                mock(com.devops.agent.infrastructure.cache.SemanticCacheService.class),
                mock(com.devops.agent.application.router.DevOpsIntentRouter.class),
                mock(AgentLogService.class),
                new ObjectMapper(),
                mock(ContextBudgetManager.class),
                stateManager,
                mock(CostQuotaManager.class),
                ticketService,
                mock(AgentMemoryManager.class),
                toolExecRepo,
                toolRuntimeManager,
                mock(SagaCompensationManager.class),
                mock(TicketDraftParser.class),
                approvalService,
                mock(ChatMemoryProvider.class),
                mock(KnowledgeScopeResolver.class));

        // Saga 步骤登记默认成功，返回步骤主键
        when(toolExecRepo.insert(any())).thenReturn(100L);
        when(toolExecRepo.nextStepSeq(anyString())).thenReturn(1);
        // lookupMeta 返回 null 是允许的分支（走 READ_ONLY 兜底），
        // 默认给 null 以避免构造注解实例的复杂度；需要 meta 的用例单独覆盖
        when(toolRuntimeManager.lookupMeta(anyString())).thenReturn(null);
    }

    private TicketDraft draft(boolean needsApproval) {
        return new TicketDraft("核心交易库主从延迟", "P0", "MYSQL",
                "主从延迟 300s，影响下单", needsApproval);
    }

    // ==================================================================
    // 分支 1：正常落库
    // ==================================================================

    @Nested
    @DisplayName("正常路径")
    class HappyPath {

        @Test
        @DisplayName("落库成功后回填工单号，并把步骤更新为 SUCCESS")
        void writesAndMarksSuccess() throws Exception {
            when(ticketService.saveTicket(any(), any(), any(), any(), any(), any()))
                    .thenReturn("TKT-20260827-0001");

            Object outcome = invokeWrite(draft(false), "trace-1", "sess-1");

            assertThat(ticketIdOf(outcome)).isEqualTo("TKT-20260827-0001");
            verify(toolExecRepo).updateResult(eq(100L), eq(ToolExecutionState.SUCCESS),
                    anyString(), any(), any(), eq("TKT-20260827-0001"), any(), anyInt());
        }

        @Test
        @DisplayName("businessKey 必须是工单号——它是 Saga 补偿动作的唯一入参")
        void businessKeyIsTicketId() throws Exception {
            // voidTicket(businessKey) 是补偿动作。这里若写错（比如写成 traceId），
            // 补偿时会去作废一个不存在的工单，补偿必然失败并升级为「需人工介入」，
            // 而真正该作废的那张单永远留在库里
            when(ticketService.saveTicket(any(), any(), any(), any(), any(), any()))
                    .thenReturn("TKT-X");

            invokeWrite(draft(false), "trace-1", "sess-1");

            ArgumentCaptor<String> bizKey = ArgumentCaptor.forClass(String.class);
            verify(toolExecRepo).updateResult(anyLong(), any(), anyString(),
                    any(), any(), bizKey.capture(), any(), anyInt());
            assertThat(bizKey.getValue()).isEqualTo("TKT-X");
        }

        @Test
        @DisplayName("先登记 Saga 步骤再落库——反序会留下无补偿记录的孤儿工单")
        void registersSagaStepBeforePersisting() throws Exception {
            when(ticketService.saveTicket(any(), any(), any(), any(), any(), any()))
                    .thenReturn("TKT-X");

            invokeWrite(draft(false), "trace-1", "sess-1");

            InOrder order = inOrder(toolExecRepo, ticketService);
            order.verify(toolExecRepo).insert(any());
            order.verify(ticketService).saveTicket(any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("traceId 原生传入 saveTicket，不做事后回填")
        void traceIdPassedNatively() throws Exception {
            // 事后回填曾是历史做法，问题是回填发生在另一个线程/时刻，
            // 失败时工单与会话就此失联，再也无法回溯 AI 当时的判断依据
            when(ticketService.saveTicket(any(), any(), any(), any(), any(), any()))
                    .thenReturn("TKT-X");

            invokeWrite(draft(false), "trace-abc", "sess-1");

            verify(ticketService).saveTicket(anyString(), anyString(), anyString(),
                    anyString(), eq(null), eq("trace-abc"));
        }

        @Test
        @DisplayName("成功文本要求模型不要复述工单号——防止模型编造")
        void successMessageForbidsModelFromQuotingId() throws Exception {
            // 工单号由系统直接展示给用户。若让模型复述，它可能记错或编造，
            // 而用户拿着一个错号去查是查不到的
            when(ticketService.saveTicket(any(), any(), any(), any(), any(), any()))
                    .thenReturn("TKT-X");

            String msg = messageOf(invokeWrite(draft(false), "trace-1", "sess-1"));

            assertThat(msg).contains("创建成功");
            assertThat(msg).contains("无需提供工单号");
        }
    }

    // ==================================================================
    // 分支 2：审批拦截
    // ==================================================================

    @Nested
    @DisplayName("审批拦截")
    class ApprovalGate {

        @Test
        @DisplayName("开关关闭时（默认）不拦截，高风险工单照常写入")
        void disabledByDefault() throws Exception {
            // approvalRequired 默认 false（L1 阶段保持现有体验）。
            // 这条用例锁住「默认不拦」这个事实——若哪天默认值被改成 true，
            // 所有 P0 工单会突然全部转入审批队列，而这不是配置变更能解释的行为
            ReflectionTestUtils.setField(service, "approvalRequired", false);
            when(ticketService.saveTicket(any(), any(), any(), any(), any(), any()))
                    .thenReturn("TKT-X");

            Object outcome = invokeWrite(draft(true), "trace-1", "sess-1");

            assertThat(ticketIdOf(outcome)).isEqualTo("TKT-X");
            verify(approvalService, never()).submit(any(), any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("开关开启且草稿标记需审批时，拦在写库之前")
        void blocksBeforeWriteWhenEnabled() throws Exception {
            // ── 本组最重要的一条 ──────────────────────────────
            // 拦截必须发生在写库之前。此前工具直接写库，只能事后作废——
            // 脏数据已经产生，审批变成了「先斩后奏」
            ReflectionTestUtils.setField(service, "approvalRequired", true);
            when(approvalService.submit(any(), any(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(77L);

            Object outcome = invokeWrite(draft(true), "trace-1", "sess-1");

            assertThat(ticketIdOf(outcome)).isNull();
            verify(ticketService, never()).saveTicket(any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("审批分支登记为 SKIPPED——未写库就不该被补偿")
        void registersSkippedStep() throws Exception {
            // 若登记成 SUCCESS 且可补偿，后续 Saga 回滚会尝试作废一张
            // 根本不存在的工单，补偿失败并误报「需人工介入」
            ReflectionTestUtils.setField(service, "approvalRequired", true);
            when(approvalService.submit(any(), any(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(77L);

            invokeWrite(draft(true), "trace-1", "sess-1");

            ArgumentCaptor<ToolExecutionRecord> rec =
                    ArgumentCaptor.forClass(ToolExecutionRecord.class);
            verify(toolExecRepo).insert(rec.capture());
            assertThat(rec.getValue().getState()).isEqualTo(ToolExecutionState.SKIPPED);
        }

        @Test
        @DisplayName("审批单 payload 存草稿 JSON——批准后据此重放建单")
        void payloadCarriesDraftJson() throws Exception {
            // payload 为空则批准后无从执行：审批通过了，工单却建不出来，
            // 而审批单显示「已通过」——最难解释的一种状态
            ReflectionTestUtils.setField(service, "approvalRequired", true);
            when(approvalService.submit(any(), any(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(77L);

            invokeWrite(draft(true), "trace-1", "sess-1");

            ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
            verify(approvalService).submit(eq("CREATE_TICKET"), anyString(), anyString(),
                    anyString(), payload.capture(), anyString(), anyString(), anyString());
            assertThat(payload.getValue())
                    .contains("核心交易库主从延迟")
                    .contains("P0")
                    .contains("MYSQL");
        }

        @Test
        @DisplayName("审批中的提示文本必须明确「勿声称已创建」")
        void approvalMessageForbidsClaimingCreated() throws Exception {
            ReflectionTestUtils.setField(service, "approvalRequired", true);
            when(approvalService.submit(any(), any(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(77L);

            String msg = messageOf(invokeWrite(draft(true), "trace-1", "sess-1"));

            assertThat(msg).contains("审批");
            assertThat(msg).contains("77");
            assertThat(msg).contains("请勿声称工单已创建成功");
        }
    }

    // ==================================================================
    // 分支 3：审批单提交失败
    // ==================================================================

    @Nested
    @DisplayName("审批单提交失败")
    class ApprovalSubmitFailure {

        @Test
        @DisplayName("提交异常时如实告知失败，不得谎称「已进入审批」")
        void doesNotClaimQueuedOnFailure() throws Exception {
            // ── 这条防的是「让 AI 对用户说谎」──────────────────
            // 若这里仍返回「已进入审批队列」，用户会一直等一个
            // 根本不存在的审批单。而系统侧没有任何记录，无人会发现
            ReflectionTestUtils.setField(service, "approvalRequired", true);
            when(approvalService.submit(any(), any(), any(), any(), any(), any(), any(), any()))
                    .thenThrow(new RuntimeException("审批表写入失败"));

            Object outcome = invokeWrite(draft(true), "trace-1", "sess-1");
            String msg = messageOf(outcome);

            assertThat(ticketIdOf(outcome)).isNull();
            assertThat(msg).contains("提交失败");
            assertThat(msg).contains("不要声称工单已创建或已进入审批");
        }

        @Test
        @DisplayName("提交失败也不落库——审批没走通就不该有工单")
        void stillDoesNotPersist() throws Exception {
            ReflectionTestUtils.setField(service, "approvalRequired", true);
            when(approvalService.submit(any(), any(), any(), any(), any(), any(), any(), any()))
                    .thenThrow(new RuntimeException("boom"));

            invokeWrite(draft(true), "trace-1", "sess-1");

            verify(ticketService, never()).saveTicket(any(), any(), any(), any(), any(), any());
        }
    }

    // ==================================================================
    // 分支 4：落库失败
    // ==================================================================

    @Nested
    @DisplayName("落库失败")
    class PersistFailure {

        @Test
        @DisplayName("落库异常时返回 null 工单号，且文本明确「不要声称成功」")
        void doesNotClaimSuccess() throws Exception {
            when(ticketService.saveTicket(any(), any(), any(), any(), any(), any()))
                    .thenThrow(new RuntimeException("duplicate key"));

            Object outcome = invokeWrite(draft(false), "trace-1", "sess-1");
            String msg = messageOf(outcome);

            assertThat(ticketIdOf(outcome)).isNull();
            assertThat(msg).contains("创建失败");
            assertThat(msg).contains("不要声称成功");
        }

        @Test
        @DisplayName("不向模型暴露内部异常详情（P2-38）")
        void hidesInternalErrorFromModel() throws Exception {
            // 模型会把看到的内容复述给用户。把 "duplicate key" 这类
            // 数据库错误透出去，等于向终端用户暴露内部实现
            when(ticketService.saveTicket(any(), any(), any(), any(), any(), any()))
                    .thenThrow(new RuntimeException("duplicate key value violates unique constraint"));

            String msg = messageOf(invokeWrite(draft(false), "trace-1", "sess-1"));

            assertThat(msg).doesNotContain("duplicate key");
            assertThat(msg).doesNotContain("unique constraint");
        }

        @Test
        @DisplayName("落库失败要把 Saga 步骤标记为 FAILED，供后续排查")
        void marksStepFailed() throws Exception {
            when(ticketService.saveTicket(any(), any(), any(), any(), any(), any()))
                    .thenThrow(new RuntimeException("boom"));

            invokeWrite(draft(false), "trace-1", "sess-1");

            verify(toolExecRepo).updateResult(eq(100L), eq(ToolExecutionState.FAILED),
                    any(), any(), anyString(), any(), any(), anyInt());
        }

        @Test
        @DisplayName("Saga 登记失败（返回 null）时仍继续写库，不因审计失败阻断主流程")
        void nullStepIdDoesNotBlockWrite() throws Exception {
            // 审计是旁路。登记不上就不登记，但工单该建还得建——
            // 反过来会让一次数据库抖动直接阻断所有 AI 建单
            when(toolExecRepo.insert(any())).thenReturn(null);
            when(ticketService.saveTicket(any(), any(), any(), any(), any(), any()))
                    .thenReturn("TKT-X");

            Object outcome = invokeWrite(draft(false), "trace-1", "sess-1");

            assertThat(ticketIdOf(outcome)).isEqualTo("TKT-X");
            // stepId 为 null 时不应再调 updateResult（没有主键可更新）
            verify(toolExecRepo, never()).updateResult(any(), any(), any(),
                    any(), any(), any(), any(), anyInt());
        }
    }
}
