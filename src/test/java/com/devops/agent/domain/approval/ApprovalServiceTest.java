package com.devops.agent.domain.approval;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ApprovalService} 单元测试。
 *
 * <h3>这是「AI 能不能动生产系统」的状态机</h3>
 * 蓝图 §二规定 P0/P1 高危动作必须由人工确认后 AI 才可执行。
 * 本类管的就是那张审批单的状态流转：
 * {@code PENDING → APPROVED → EXECUTED / EXECUTE_FAILED}，
 * 以及 {@code REJECTED} 与 {@code EXPIRED} 两个旁支。
 *
 * <h3>三条最要紧的不变式</h3>
 * <ol>
 *   <li><b>CAS 防重复批准</b>。两个管理员同时点批准时，
 *       只能有一个生效——否则<b>同一个高危动作会被执行两次</b>
 *       （重启两次 Pod、扩容两次）。实现靠 {@code markApproved} 的
 *       影响行数：返回 0 说明状态在查询与更新之间被别人改了；</li>
 *   <li><b>驳回理由必填</b>。驳回是对 AI 提议的否决，
 *       没有依据就无法据此改进模型的判断；</li>
 *   <li><b>超时单必须被固化成 EXPIRED</b>。待审单无限积压时，
 *       审批队列会被几百条早已失去意义的单淹没，
 *       真正紧急的那条反而看不见。</li>
 * </ol>
 *
 * <h3>还有一条容易被忽略的：payload 是批准后能否执行的前提</h3>
 * {@code submit} 的 payload 存的是<b>可重放的动作上下文</b>。
 * 不传的话，批准之后编排层拿不到任何参数，那次批准就是空的——
 * 用户点了「同意」，系统却什么都做不了，而且没有任何报错。
 */
@DisplayName("审批服务 · 高危动作的人工闸门")
class ApprovalServiceTest {

    private ApprovalRequestRepository repository;
    private ApprovalService service;

    @BeforeEach
    void setUp() {
        repository = mock(ApprovalRequestRepository.class);
        service = new ApprovalService(repository);
        // @Value 字段在非 Spring 环境不注入，不设则为 0，
        // 会让 expiresAt 变成「立刻过期」，测出来的行为与生产完全不同
        ReflectionTestUtils.setField(service, "timeoutHours", 24);
    }

    private static ApprovalRequest approval(Long id, ApprovalStatus status) {
        ApprovalRequest r = new ApprovalRequest();
        r.setId(id);
        r.setActionType("TOOL_CALL");
        r.setToolName("k8s.pod.restart");
        r.setRiskLevel("DESTRUCTIVE_HIGH_RISK");
        r.setSummary("重启 order-svc 的 pod-3");
        r.setPayload("{\"ns\":\"prod\",\"pod\":\"order-svc-3\"}");
        r.setRequester("AI");
        r.setStatus(status.name());
        r.setCreateTime(LocalDateTime.of(2026, 8, 25, 9, 0));
        return r;
    }

    // ==================================================================

    @Nested
    @DisplayName("提交审批单")
    class Submit {

        @Test
        @DisplayName("提交时带上 payload —— 没有它，批准之后什么都执行不了")
        void submitCarriesReplayablePayload() {
            when(repository.insert(any())).thenReturn(7L);

            Long id = service.submit("TOOL_CALL", "k8s.pod.restart", "DESTRUCTIVE_HIGH_RISK",
                    "重启 pod", "{\"pod\":\"x\"}", "AI", "trace-1", "sess-1");

            assertThat(id).isEqualTo(7L);
            ArgumentCaptor<ApprovalRequest> cap = ArgumentCaptor.forClass(ApprovalRequest.class);
            verify(repository).insert(cap.capture());
            ApprovalRequest saved = cap.getValue();
            // payload 缺失的话，用户点了「同意」系统却什么都做不了，且没有任何报错
            assertThat(saved.getPayload()).isEqualTo("{\"pod\":\"x\"}");
            assertThat(saved.getStatus()).isEqualTo(ApprovalStatus.PENDING.name());
        }

        @Test
        @DisplayName("新单必须带 expiresAt —— 没有它，超时任务永远扫不到这张单")
        void submitSetsExpiry() {
            when(repository.insert(any())).thenReturn(1L);

            service.submit("TOOL_CALL", "t", "LOW", "s", "{}", "AI", "tr", "se");

            ArgumentCaptor<ApprovalRequest> cap = ArgumentCaptor.forClass(ApprovalRequest.class);
            verify(repository).insert(cap.capture());
            assertThat(cap.getValue().getExpiresAt())
                    .as("超时时刻必须写死在单上，而不是查询时按当前配置反推")
                    .isNotNull()
                    .isAfter(LocalDateTime.now().plusHours(23));
        }

        @Test
        @DisplayName("requester 为空时记为 AI —— 审批单必须能说清是谁提的")
        void blankRequesterBecomesAi() {
            when(repository.insert(any())).thenReturn(1L);

            service.submit("TOOL_CALL", "t", "LOW", "s", "{}", "  ", "tr", "se");

            ArgumentCaptor<ApprovalRequest> cap = ArgumentCaptor.forClass(ApprovalRequest.class);
            verify(repository).insert(cap.capture());
            assertThat(cap.getValue().getRequester()).isEqualTo("AI");
        }

        @Test
        @DisplayName("超长 summary 被截断到 255，不因字段超长导致整单写入失败")
        void longSummaryIsTruncated() {
            when(repository.insert(any())).thenReturn(1L);

            service.submit("TOOL_CALL", "t", "LOW", "x".repeat(500), "{}", "AI", "tr", "se");

            ArgumentCaptor<ApprovalRequest> cap = ArgumentCaptor.forClass(ApprovalRequest.class);
            verify(repository).insert(cap.capture());
            // 高危动作的审批单因为摘要太长而入库失败 = 这个动作绕过了审批
            assertThat(cap.getValue().getSummary()).hasSize(255);
        }

        @Test
        @DisplayName("timeoutHours 配成 0 时兜底为 1 小时，不会立刻过期")
        void zeroTimeoutFallsBackToOneHour() {
            ReflectionTestUtils.setField(service, "timeoutHours", 0);
            when(repository.insert(any())).thenReturn(1L);

            service.submit("TOOL_CALL", "t", "LOW", "s", "{}", "AI", "tr", "se");

            ArgumentCaptor<ApprovalRequest> cap = ArgumentCaptor.forClass(ApprovalRequest.class);
            verify(repository).insert(cap.capture());
            // 配 0 会让每张单一提交就过期，审批功能等于被静默关掉
            assertThat(cap.getValue().getExpiresAt()).isAfter(LocalDateTime.now());
        }
    }

    @Nested
    @DisplayName("批准：CAS 防重复执行")
    class Approve {

        @Test
        @DisplayName("PENDING 单可批准，并返回含 payload 的最新单供编排层重放")
        void approvePendingReturnsPayload() {
            when(repository.findById(1L))
                    .thenReturn(Optional.of(approval(1L, ApprovalStatus.PENDING)),
                            Optional.of(approval(1L, ApprovalStatus.APPROVED)));
            when(repository.markApproved(anyLong(), anyString(), any())).thenReturn(1);

            ApprovalRequest result = service.approve(1L, "张明", "已确认影响面");

            assertThat(result.getStatus()).isEqualTo(ApprovalStatus.APPROVED.name());
            assertThat(result.getPayload()).isNotBlank();
            verify(repository).markApproved(eq(1L), eq("张明"), eq("已确认影响面"));
        }

        @Test
        @DisplayName("非 PENDING 单不可再批准（前置校验）")
        void nonPendingCannotBeApproved() {
            when(repository.findById(1L))
                    .thenReturn(Optional.of(approval(1L, ApprovalStatus.REJECTED)));

            assertThatThrownBy(() -> service.approve(1L, "张明", null))
                    .isInstanceOf(ApprovalService.ApprovalException.class)
                    .hasMessageContaining("已被处理");

            verify(repository, never()).markApproved(anyLong(), anyString(), any());
        }

        @Test
        @DisplayName("CAS 落空 → 明确报「刚被他人处理」，而不是静默成功")
        void casMissThrows() {
            // 竞态：查询时还是 PENDING，更新瞬间被另一个管理员改掉
            when(repository.findById(1L))
                    .thenReturn(Optional.of(approval(1L, ApprovalStatus.PENDING)));
            when(repository.markApproved(anyLong(), anyString(), any())).thenReturn(0);

            assertThatThrownBy(() -> service.approve(1L, "李四", null))
                    .isInstanceOf(ApprovalService.ApprovalException.class)
                    .hasMessageContaining("刚被他人处理");

            // 这条是防「同一高危动作被执行两次」的最后一道：
            // 若 rows==0 时不抛异常，两个管理员的批准都会返回成功，
            // 编排层就会重放执行两次——重启两次 Pod、扩容两次
        }

        @Test
        @DisplayName("批准理由可选，但超长会被截断到 500")
        void approveReasonOptionalAndTruncated() {
            when(repository.findById(1L))
                    .thenReturn(Optional.of(approval(1L, ApprovalStatus.PENDING)),
                            Optional.of(approval(1L, ApprovalStatus.APPROVED)));
            when(repository.markApproved(anyLong(), anyString(), any())).thenReturn(1);

            service.approve(1L, "张明", "x".repeat(800));

            ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
            verify(repository).markApproved(anyLong(), anyString(), reason.capture());
            assertThat(reason.getValue()).hasSize(500);
        }

        @Test
        @DisplayName("审批单不存在 → ApprovalException（映射 40004）")
        void missingApprovalThrows() {
            when(repository.findById(anyLong())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.approve(999L, "张明", null))
                    .isInstanceOf(ApprovalService.ApprovalException.class)
                    .hasMessageContaining("不存在");
        }

        @Test
        @DisplayName("ID 为 null 被拒，不去查库")
        void nullIdRejected() {
            assertThatThrownBy(() -> service.getById(null))
                    .isInstanceOf(ApprovalService.ApprovalException.class);

            verify(repository, never()).findById(any());
        }
    }

    @Nested
    @DisplayName("驳回：必须留下依据")
    class Reject {

        @Test
        @DisplayName("驳回理由必填 —— 没有依据就无法据此改进 AI 的判断")
        void rejectRequiresReason() {
            assertThatThrownBy(() -> service.reject(1L, "张明", null))
                    .isInstanceOf(ApprovalService.ApprovalException.class)
                    .hasMessageContaining("理由");
            assertThatThrownBy(() -> service.reject(1L, "张明", "   "))
                    .isInstanceOf(ApprovalService.ApprovalException.class);

            // 校验在查库之前，缺理由的请求不该产生任何数据库往返
            verify(repository, never()).findById(any());
            verify(repository, never()).markRejected(anyLong(), anyString(), anyString());
        }

        @Test
        @DisplayName("驳回成功")
        void rejectSucceeds() {
            when(repository.findById(1L))
                    .thenReturn(Optional.of(approval(1L, ApprovalStatus.PENDING)),
                            Optional.of(approval(1L, ApprovalStatus.REJECTED)));
            when(repository.markRejected(anyLong(), anyString(), anyString())).thenReturn(1);

            ApprovalRequest result = service.reject(1L, "张明", "影响面过大，改走灰度");

            assertThat(result.getStatus()).isEqualTo(ApprovalStatus.REJECTED.name());
            verify(repository).markRejected(eq(1L), eq("张明"), eq("影响面过大，改走灰度"));
        }

        @Test
        @DisplayName("驳回同样有 CAS 保护")
        void rejectCasMissThrows() {
            when(repository.findById(1L))
                    .thenReturn(Optional.of(approval(1L, ApprovalStatus.PENDING)));
            when(repository.markRejected(anyLong(), anyString(), anyString())).thenReturn(0);

            assertThatThrownBy(() -> service.reject(1L, "张明", "不批"))
                    .isInstanceOf(ApprovalService.ApprovalException.class)
                    .hasMessageContaining("刚被他人处理");
        }

        @Test
        @DisplayName("已决策的单不能再驳回")
        void decidedCannotBeRejected() {
            when(repository.findById(1L))
                    .thenReturn(Optional.of(approval(1L, ApprovalStatus.APPROVED)));

            assertThatThrownBy(() -> service.reject(1L, "张明", "反悔了"))
                    .isInstanceOf(ApprovalService.ApprovalException.class)
                    .hasMessageContaining("已被处理");

            // 已批准的动作可能已经执行了，「反悔」不能靠改审批单状态实现
            verify(repository, never()).markRejected(anyLong(), anyString(), anyString());
        }
    }

    @Nested
    @DisplayName("执行回写与超时固化")
    class ExecutionAndExpiry {

        @Test
        @DisplayName("回写成功结果")
        void recordExecutionSuccess() {
            when(repository.markExecuted(anyLong(), eq(true), any())).thenReturn(1);

            service.recordExecution(1L, true, "Pod 已重启");

            verify(repository).markExecuted(1L, true, "Pod 已重启");
        }

        @Test
        @DisplayName("回写未命中（状态非 APPROVED）不抛异常 —— 它是旁路留痕")
        void recordExecutionMissDoesNotThrow() {
            when(repository.markExecuted(anyLong(), anyBooleanArg(), any())).thenReturn(0);

            // 抛异常会让「动作已经执行了、只是没记上」变成一次失败回滚，
            // 而动作的副作用已经产生，回滚审批单状态只会让记录与现实不符
            service.recordExecution(1L, true, "结果");

            verify(repository).markExecuted(eq(1L), eq(true), anyString());
        }

        @Test
        @DisplayName("超长执行结果截断到 2000")
        void longResultTruncated() {
            when(repository.markExecuted(anyLong(), anyBooleanArg(), any())).thenReturn(1);

            service.recordExecution(1L, false, "x".repeat(5000));

            ArgumentCaptor<String> res = ArgumentCaptor.forClass(String.class);
            verify(repository).markExecuted(anyLong(), eq(false), res.capture());
            assertThat(res.getValue()).hasSize(2000);
        }

        @Test
        @DisplayName("超时固化返回处理条数 —— 待审单无限积压会淹没真正紧急的那条")
        void expireOverdueReturnsCount() {
            when(repository.markExpired(any())).thenReturn(3);

            assertThat(service.expireOverdue()).isEqualTo(3);

            verify(repository).markExpired(any(LocalDateTime.class));
        }

        @Test
        @DisplayName("无超时单时返回 0，不打无谓的告警日志")
        void expireOverdueNoop() {
            when(repository.markExpired(any())).thenReturn(0);

            assertThat(service.expireOverdue()).isZero();
        }
    }

    @Nested
    @DisplayName("列表与分页")
    class Listing {

        @Test
        @DisplayName("待审队列按最早优先，分页元信息完整")
        void listPendingPaging() {
            when(repository.findPending(anyInt(), anyInt()))
                    .thenReturn(List.of(approval(1L, ApprovalStatus.PENDING)));
            when(repository.countByStatus(ApprovalStatus.PENDING.name())).thenReturn(21);

            Map<String, Object> page = service.listPending(2, 10);

            assertThat(page.get("total")).isEqualTo(21);
            assertThat(page.get("page")).isEqualTo(2);
            assertThat(page.get("totalPages")).isEqualTo(3);
            // 第 2 页 × 每页 10 → offset 20
            verify(repository).findPending(10, 10);
        }

        @Test
        @DisplayName("分页参数钳制：page≥1、size∈[1,200]")
        void clampsPaging() {
            when(repository.findPending(anyInt(), anyInt())).thenReturn(List.of());
            when(repository.countByStatus(anyString())).thenReturn(0);

            service.listPending(0, 99999);

            // page=0 会让 offset 变成负数；size 无上限可一次拉走整张审批表
            verify(repository).findPending(200, 0);
        }

        @Test
        @DisplayName("按状态查询把 null 透传（=不筛状态）")
        void listByStatusPassesNull() {
            when(repository.findByStatus(any(), anyInt(), anyInt())).thenReturn(List.of());
            when(repository.countByStatus(any())).thenReturn(0);

            service.listByStatus(null, 1, 20);

            verify(repository).findByStatus(null, 20, 0);
        }
    }

    /** Mockito 的 anyBoolean 与本类静态导入冲突时的局部别名 */
    private static boolean anyBooleanArg() {
        return org.mockito.ArgumentMatchers.anyBoolean();
    }
}
