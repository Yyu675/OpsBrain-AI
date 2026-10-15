package com.devops.agent.domain.notify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 渠道回执判据（{@link NotifyPayloads#accepted}）单元测试。
 *
 * <h3>为什么单独立一个类</h3>
 * 这个方法是**「送达」与「没送达」的唯一裁决点**：它判 true，看门狗的
 * {@code delivered} 才 +1，灰度 DoD 里的「通知送达率」才有数。原实现是
 * 报文串匹配 {@code body.contains("\"errcode\":0")}——真实钉钉回紧凑 JSON 所以
 * 线上一直"能用"，但等价回执（多一个空格、键序不同）会被判**拒收**，
 * 而错误报文里恰好出现同样子串时又会被判**送达**（两个方向的错都在）。
 *
 * <p>2026-10-15 实弹抓到：本机假接收端回 {@code {"errcode": 0, "errmsg": "ok"}}
 * （带空格）即被判「渠道拒收」，而它是标准的成功回执。改结构化解析后钉住行为。</p>
 */
@DisplayName("通知载荷（渠道回执判据）")
class NotifyPayloadsTest {

    @Nested
    @DisplayName("判收：HTTP 200 + errcode==0")
    class Accepted {

        @Test
        @DisplayName("紧凑回执（钉钉/企微真实格式）→ 送达")
        void compactOkIsAccepted() {
            assertTrue(NotifyPayloads.accepted(200, "{\"errcode\":0,\"errmsg\":\"ok\"}"));
        }

        @Test
        @DisplayName("等价回执：多一个空格 → 仍算送达（F4 回归：旧实现判成拒收）")
        void spacedOkIsAccepted() {
            assertTrue(NotifyPayloads.accepted(200, "{\"errcode\": 0, \"errmsg\": \"ok\"}"));
        }

        @Test
        @DisplayName("等价回执：键序不同 → 仍算送达")
        void reorderedOkIsAccepted() {
            assertTrue(NotifyPayloads.accepted(200, "{\"errmsg\":\"ok\",\"errcode\":0}"));
        }

        @Test
        @DisplayName("errcode 非 0（限流 130101 / 加签失败 310000）→ 未送达")
        void nonZeroErrcodeIsRejected() {
            assertFalse(NotifyPayloads.accepted(200, "{\"errcode\":310000,\"errmsg\":\"sign not match\"}"));
        }

        @Test
        @DisplayName("错误报文里恰好含 \"errcode\":0 子串 → 不得误判为送达（旧实现的假阳性）")
        void errcodeSubstringInsideMessageIsNotDelivery() {
            assertFalse(NotifyPayloads.accepted(200,
                    "{\"errcode\":310000,\"errmsg\":\"invalid field \\\"errcode\\\":0 not a code\"}"));
        }

        @Test
        @DisplayName("HTTP 非 200 / 空体 / 非 JSON 体 → 一律未送达（宁可否，不可假报）")
        void nonSuccessIsRejected() {
            assertFalse(NotifyPayloads.accepted(500, "{\"errcode\":0}"));
            assertFalse(NotifyPayloads.accepted(200, ""));
            assertFalse(NotifyPayloads.accepted(200, "   "));
            assertFalse(NotifyPayloads.accepted(200, null));
            assertFalse(NotifyPayloads.accepted(200, "ok"));
        }

        @Test
        @DisplayName("无 errcode 字段的 JSON（别的渠道错误页）→ 未送达")
        void jsonWithoutErrcodeIsRejected() {
            assertFalse(NotifyPayloads.accepted(200, "{\"message\":\"success\"}"));
        }
    }
}
