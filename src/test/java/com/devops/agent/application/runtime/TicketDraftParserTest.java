package com.devops.agent.application.runtime;

import com.devops.agent.domain.tools.TicketDraft;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 工单草稿序列化 / 解析往返测试（P1-3 Single Writer）
 * <p>
 * 草稿是工具与编排层之间的契约载体，序列化错误会导致工单静默丢失，
 * 故对转义边界重点覆盖——运维文本常含引号、换行、Windows 路径反斜杠。
 * </p>
 */
class TicketDraftParserTest {

    private final TicketDraftParser parser = new TicketDraftParser(new ObjectMapper());

    @Test
    @DisplayName("普通文本：序列化后可完整解析回来")
    void roundTrip_plainText() {
        TicketDraft original = new TicketDraft(
                "生产环境 MySQL 主从延迟",
                "P0",
                "MYSQL",
                "从库 Seconds_Behind_Master 持续超过 300 秒",
                true);

        String toolOutput = "✅ 工单提交成功。\n\n" + original.toMarkerBlock();
        TicketDraft parsed = parser.parse(toolOutput);

        assertNotNull(parsed, "应能解析出草稿");
        assertEquals(original.title(), parsed.title());
        assertEquals(original.priority(), parsed.priority());
        assertEquals(original.module(), parsed.module());
        assertEquals(original.description(), parsed.description());
        assertTrue(parsed.needsApproval(), "HIGH 优先级应标记需审批");
    }

    @Test
    @DisplayName("含引号与换行：转义正确，不破坏 JSON")
    void roundTrip_quotesAndNewlines() {
        String desc = "执行 SHOW PROCESSLIST 报错：\n"
                + "ERROR 1045 (28000): Access denied for user \"root\"@\"localhost\"\n"
                + "\t重试仍失败";

        TicketDraft original = new TicketDraft(
                "MySQL 连接被拒绝", "P2", "MYSQL", desc, false);

        TicketDraft parsed = parser.parse(original.toMarkerBlock());

        assertNotNull(parsed, "含引号换行的文本也应能解析");
        assertEquals(desc, parsed.description(), "描述应与原文逐字一致");
    }

    @Test
    @DisplayName("含 Windows 路径反斜杠：不被误当转义符")
    void roundTrip_backslashes() {
        String desc = "日志路径 C:\\logs\\app\\error.log 中出现 OOMKilled";
        TicketDraft original = new TicketDraft(
                "容器 OOM", "P0", "K8S", desc, true);

        TicketDraft parsed = parser.parse(original.toMarkerBlock());

        assertNotNull(parsed);
        assertEquals(desc, parsed.description(), "反斜杠应原样保留");
    }

    @Test
    @DisplayName("无标记块：返回 null，不误判")
    void parse_noMarker_returnsNull() {
        assertNull(parser.parse("📚 检索到以下知识片段: K8s Pod 排查步骤"),
                "只读工具的返回不含草稿标记，应返回 null");
        assertNull(parser.parse(null));
        assertNull(parser.parse(""));
    }

    @Test
    @DisplayName("标记块 JSON 损坏：返回 null 而非抛异常")
    void parse_malformedJson_returnsNull() {
        String broken = TicketDraft.MARKER_START + "{\"title\":\"缺少右括号\"" + TicketDraft.MARKER_END;
        assertNull(parser.parse(broken), "损坏的 JSON 应安全降级为 null");
    }

    @Test
    @DisplayName("缺必填字段：视为无效草稿")
    void parse_missingRequiredFields_returnsNull() {
        String noTitle = TicketDraft.MARKER_START
                + "{\"title\":\"\",\"priority\":\"HIGH\",\"module\":\"K8S\",\"description\":\"有描述\"}"
                + TicketDraft.MARKER_END;
        assertNull(parser.parse(noTitle), "缺标题应视为无效");

        String noDesc = TicketDraft.MARKER_START
                + "{\"title\":\"有标题\",\"priority\":\"HIGH\",\"module\":\"K8S\",\"description\":\"\"}"
                + TicketDraft.MARKER_END;
        assertNull(parser.parse(noDesc), "缺描述应视为无效");
    }

    @Test
    @DisplayName("剔除标记块：内部数据不泄漏到记忆与前端")
    void stripMarker_removesInternalData() {
        TicketDraft draft = new TicketDraft("标题", "P3", "OTHER", "描述", false);
        String toolOutput = "✅ 工单提交成功。\n\n标题: 标题\n" + draft.toMarkerBlock();

        String stripped = parser.stripMarker(toolOutput);

        assertFalse(stripped.contains(TicketDraft.MARKER_START),
                "标记起始符应被移除");
        assertFalse(stripped.contains("needsApproval"),
                "草稿内部字段不应残留");
        assertTrue(stripped.contains("工单提交成功"),
                "面向用户的正文应保留");
    }
}