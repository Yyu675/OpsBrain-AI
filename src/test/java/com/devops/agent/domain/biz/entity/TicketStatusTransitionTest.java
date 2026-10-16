package com.devops.agent.domain.biz.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.devops.agent.domain.biz.entity.TicketEnums.Status;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工单状态机流转规则测试。
 *
 * <p>保护的契约：<b>状态只能沿业务允许的路径流转</b>。
 * 修复前 {@code updateStatus} 只校验「目标值是不是合法枚举」，
 * 不校验「能不能从当前状态走过去」——CLOSED 可以被改回 PENDING、
 * VOID（作废）可以被复活。这类越权流转不会报错，
 * 但会让 SLA 统计、首响计时、复盘归档全部失真。</p>
 */
class TicketStatusTransitionTest {

    // ==================== 被修复的越权路径 ====================

    @Test
    @DisplayName("已关闭的工单不能直接退回待处理")
    void closedCannotGoBackToPending() {
        assertFalse(Status.canTransition(Status.CLOSED, Status.PENDING),
                "CLOSED → PENDING 会让已归档工单重新进入待办池，SLA 计时错乱");
    }

    @Test
    @DisplayName("作废是不可逆终态，不能复活到任何状态")
    void voidIsIrreversible() {
        for (String to : new String[]{Status.PENDING, Status.PROCESSING,
                Status.RESOLVED, Status.CLOSED}) {
            assertFalse(Status.canTransition(Status.VOID, to),
                    "VOID → " + to + " 会让「这张单到底存不存在」不可判定");
        }
        assertTrue(Status.isTerminal(Status.VOID));
    }

    @Test
    @DisplayName("已解决/已关闭不能直接作废，必须先重开")
    void resolvedAndClosedCannotBeVoidedDirectly() {
        assertFalse(Status.canTransition(Status.RESOLVED, Status.VOID));
        assertFalse(Status.canTransition(Status.CLOSED, Status.VOID));
    }

    // ==================== 正常业务路径必须畅通 ====================

    @Test
    @DisplayName("标准闭环：待处理 → 处理中 → 已解决 → 已关闭")
    void happyPathIsAllowed() {
        assertTrue(Status.canTransition(Status.PENDING, Status.PROCESSING));
        assertTrue(Status.canTransition(Status.PROCESSING, Status.RESOLVED));
        assertTrue(Status.canTransition(Status.RESOLVED, Status.CLOSED));
    }

    @Test
    @DisplayName("误接单可退回待处理")
    void processingCanReturnToPending() {
        assertTrue(Status.canTransition(Status.PROCESSING, Status.PENDING));
    }

    @Test
    @DisplayName("验证不通过可从已解决重开")
    void resolvedCanReopen() {
        assertTrue(Status.canTransition(Status.RESOLVED, Status.PROCESSING));
    }

    @Test
    @DisplayName("故障复发可从已关闭重开")
    void closedCanReopenOnRecurrence() {
        assertTrue(Status.canTransition(Status.CLOSED, Status.PROCESSING));
    }

    @Test
    @DisplayName("活跃态可直接作废（误报工单）")
    void activeStatesCanBeVoided() {
        assertTrue(Status.canTransition(Status.PENDING, Status.VOID));
        assertTrue(Status.canTransition(Status.PROCESSING, Status.VOID));
    }

    // ==================== 边界 ====================

    @Test
    @DisplayName("同态视为合法——幂等重试不应报错")
    void sameStateIsIdempotent() {
        for (String s : Status.ALL) {
            assertTrue(Status.canTransition(s, s), s + " → " + s + " 应幂等放行");
        }
    }

    @Test
    @DisplayName("大小写与空白不影响判定")
    void normalizesInput() {
        assertTrue(Status.canTransition("pending", " processing "));
    }

    @Test
    @DisplayName("null 输入一律拒绝，不抛异常")
    void nullIsRejected() {
        assertFalse(Status.canTransition(null, Status.PENDING));
        assertFalse(Status.canTransition(Status.PENDING, null));
        assertFalse(Status.canTransition(null, null));
    }

    @Test
    @DisplayName("nextStates 供前端置灰非法选项")
    void nextStatesDrivesUi() {
        assertTrue(Status.nextStates(Status.PENDING).contains(Status.PROCESSING));
        assertFalse(Status.nextStates(Status.PENDING).contains(Status.CLOSED),
                "待处理不能一步跳到已关闭，前端该选项应置灰");
        assertTrue(Status.nextStates(Status.VOID).isEmpty(),
                "终态无可选项，前端应整体禁用状态切换");
    }

    @Test
    @DisplayName("未知状态不会让流转判定崩溃")
    void unknownStateIsRejected() {
        assertFalse(Status.canTransition("WHATEVER", Status.PENDING));
        assertTrue(Status.nextStates("WHATEVER").isEmpty());
    }
}
