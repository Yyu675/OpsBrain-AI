package com.devops.agent.domain.notify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * webhook 打码纯函数测试（批89 清查：敏感参数名不留头）。
 *
 * <p>为什么值得单测：这是「列表/整屏视图永远打码」承诺的实现体——
 * 打码方向错了（host 打掉、key 留下）等于没打。</p>
 */
class NotifyChannelMaskWebhookTest {

    @Test
    @DisplayName("敏感参数值只留尾 4 位，host+path 保留")
    void sensitiveParamKeepsTailOnly() {
        assertEquals(
                "https://oapi.dingtalk.com/robot/send?access_token=****abcd",
                NotifyChannelService.maskWebhook(
                        "https://oapi.dingtalk.com/robot/send?access_token=08f3a1b2c3d4abcd"));
    }

    @Test
    @DisplayName("非敏感参数仍头 4 尾 4（timestamp 之类要能读）")
    void normalParamKeepsHeadAndTail() {
        assertEquals(
                "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=****60a7&x=abcd****wxyz",
                NotifyChannelService.maskWebhook(
                        "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=d24d9f0160a7&x=abcd1234wxyz"));
    }

    @Test
    @DisplayName("无 query 参数退化为整串打码（ApiKeyCrypt.mask 口径）")
    void noQueryFallsBackToFullMask() {
        String masked = NotifyChannelService.maskWebhook("https://example.com/very/long/path");
        assertEquals("https:****path", masked);
    }

    @Test
    @DisplayName("短值整遮，不泄长度信息以外的内容")
    void shortValueFullyMasked() {
        assertEquals("https://h/p?key=****",
                NotifyChannelService.maskWebhook("https://h/p?key=abc"));
    }

    @Test
    @DisplayName("空输入返回 null（前端据此渲染「未配置」）")
    void blankReturnsNull() {
        assertNull(NotifyChannelService.maskWebhook(null));
        assertNull(NotifyChannelService.maskWebhook(""));
    }
}
