package com.devops.agent.common.audit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * {@link AuditActionRegistry} 路径归一与 action 解析测试。
 *
 * <p>保护的契约：<b>写操作必须能被识别并留痕</b>。
 * 归一化出错会让所有带 ID 的路径都落到 {@code generic}，
 * 审计表虽然还有记录，但无法按操作类型统计与告警——形同报废。</p>
 */
class AuditActionRegistryTest {

    @Test
    @DisplayName("数字 ID 段归一为 *")
    void normalizesNumericId() {
        assertEquals("/api/v1/knowledge/docs/*",
                AuditActionRegistry.normalize("/api/v1/knowledge/docs/42"));
    }

    @Test
    @DisplayName("ID 之后的动作段保留，不被一并吞掉")
    void keepsTrailingActionSegment() {
        assertEquals("/api/v1/knowledge/docs/*/publish",
                AuditActionRegistry.normalize("/api/v1/knowledge/docs/42/publish"));
    }

    @Test
    @DisplayName("UUID（32 位无分隔与 36 位标准）都归一为 *")
    void normalizesUuid() {
        assertEquals("/api/v1/x/*",
                AuditActionRegistry.normalize("/api/v1/x/" + "a".repeat(32)));
        assertEquals("/api/v1/x/*",
                AuditActionRegistry.normalize("/api/v1/x/550e8400-e29b-41d4-a716-446655440000"));
    }

    @Test
    @DisplayName("业务编号 TKT-20260808-0001 归一为 *")
    void normalizesBusinessCode() {
        assertEquals("/api/v1/tickets/*",
                AuditActionRegistry.normalize("/api/v1/tickets/TKT-20260808-0001"));
    }

    @Test
    @DisplayName("普通单词不得被误判为 ID——否则 /categories 会退化成 /*")
    void keepsPlainWords() {
        assertEquals("/api/v1/knowledge/categories",
                AuditActionRegistry.normalize("/api/v1/knowledge/categories"));
        // 32 个字符但非十六进制，不是 UUID
        String word = "z".repeat(32);
        assertEquals("/api/v1/" + word, AuditActionRegistry.normalize("/api/v1/" + word));
    }

    @Test
    @DisplayName("尾部斜杠与空段被规范掉")
    void handlesTrailingSlash() {
        assertEquals("/api/v1/tickets", AuditActionRegistry.normalize("/api/v1/tickets/"));
        assertEquals("/api/v1/tickets", AuditActionRegistry.normalize("/api/v1//tickets"));
    }

    @Test
    @DisplayName("已登记路由解析为语言无关标识")
    void resolvesRegisteredAction() {
        assertEquals("knowledge.doc.delete",
                AuditActionRegistry.resolve("DELETE", "/api/v1/knowledge/docs/42"));
        assertEquals("approval.approve",
                AuditActionRegistry.resolve("POST", "/api/v1/approvals/7/approve"));
        assertEquals("ticket.status.change",
                AuditActionRegistry.resolve("PATCH", "/api/v1/tickets/12/status"));
    }

    @Test
    @DisplayName("方法不同则 action 不同（PUT 与 DELETE 不可混为一谈）")
    void methodIsPartOfKey() {
        assertNotEquals(
                AuditActionRegistry.resolve("PUT", "/api/v1/knowledge/docs/1"),
                AuditActionRegistry.resolve("DELETE", "/api/v1/knowledge/docs/1"));
    }

    @Test
    @DisplayName("未登记路由回退 generic —— 仍然留痕，绝不因未登记而丢审计")
    void fallsBackToGeneric() {
        assertEquals(AuditActionRegistry.GENERIC,
                AuditActionRegistry.resolve("POST", "/api/v1/some/new/endpoint"));
    }

    @Test
    @DisplayName("null 输入安全兜底，不抛异常")
    void nullSafe() {
        assertEquals(AuditActionRegistry.GENERIC, AuditActionRegistry.resolve(null, "/x"));
        assertEquals(AuditActionRegistry.GENERIC, AuditActionRegistry.resolve("POST", null));
    }

    @Test
    @DisplayName("方法名大小写不敏感")
    void methodCaseInsensitive() {
        assertEquals("ticket.create", AuditActionRegistry.resolve("post", "/api/v1/tickets"));
    }
}
