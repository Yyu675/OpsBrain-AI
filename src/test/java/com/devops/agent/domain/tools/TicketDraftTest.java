package com.devops.agent.domain.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 工单草稿契约测试（P1-3 Single Writer）
 * <p>
 * 验证审批标记的判定——这是写前拦截高风险操作的依据，
 * 判错会导致高风险工单绕过审批直接落库。
 * </p>
 */
class TicketDraftTest {

    @Test
    @DisplayName("HIGH 优先级标记需审批")
    void highPriority_needsApproval() {
        TicketDraft draft = new TicketDraft(
                "生产库宕机", "P0", "MYSQL", "主库无法连接", true);
        assertTrue(draft.needsApproval(), "HIGH 优先级必须标记需审批");
        assertTrue(draft.toMarkerBlock().contains("\"needsApproval\":true"),
                "审批标记须序列化到草稿块");
    }

    @Test
    @DisplayName("非 HIGH 优先级不需审批")
    void lowPriority_noApproval() {
        TicketDraft draft = new TicketDraft(
                "日志轮转优化", "P3", "OTHER", "建议调整 logrotate 周期", false);
        assertFalse(draft.needsApproval());
        assertTrue(draft.toMarkerBlock().contains("\"needsApproval\":false"));
    }

    @Test
    @DisplayName("标记块被注释包裹，模型通常不会复述给用户")
    void markerBlock_isHtmlComment() {
        TicketDraft draft = new TicketDraft("标题", "P3", "OTHER", "描述", false);
        String block = draft.toMarkerBlock();

        assertTrue(block.startsWith("<!--"), "应以 HTML 注释开头");
        assertTrue(block.endsWith("-->"), "应以 HTML 注释结尾");
    }

    @Test
    @DisplayName("null 字段不产生非法 JSON")
    void nullFields_produceValidJson() {
        TicketDraft draft = new TicketDraft(null, null, null, null, false);
        String block = draft.toMarkerBlock();

        // null 应转为空串而非字面量 null，否则 JSON 结构虽合法但语义错误
        assertFalse(block.contains(":null,"), "null 字段应序列化为空串");
        assertTrue(block.contains("\"title\":\"\""));
    }

    @Test
    @DisplayName("控制字符被转义，不破坏 JSON")
    void controlChars_escaped() {
        //  等控制字符在 JSON 字符串中非法，必须转 \\uXXXX
        TicketDraft draft = new TicketDraft(
                "标题含控制符", "P3", "OTHER", "描述", false);
        String block = draft.toMarkerBlock();

        assertTrue(block.contains("\\u0001"), "控制字符应转为 \\uXXXX 形式");
    }
}