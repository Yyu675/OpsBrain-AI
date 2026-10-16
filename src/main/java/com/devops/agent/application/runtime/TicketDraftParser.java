package com.devops.agent.application.runtime;

import com.devops.agent.domain.tools.TicketDraft;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 工单草稿解析器
 * <p>
 * 从工具返回文本中提取 {@link TicketDraft} 标记块。
 * 支撑 Single Writer 模式：工具产草稿，编排层解析后统一落库。
 * </p>
 *
 * @author OpsBrain AI
 * @since 2026-08-09
 */
@Slf4j
@Component
public class TicketDraftParser {

    /**
     * 草稿标记块正则
     * <p>
     * {@code DOTALL} 使 {@code .} 匹配换行——描述字段虽已转义换行，
     * 但仍保留此标志以容错。
     * </p>
     */
    private static final Pattern DRAFT_PATTERN = Pattern.compile(
            Pattern.quote(TicketDraft.MARKER_START) + "(.*?)" + Pattern.quote(TicketDraft.MARKER_END),
            Pattern.DOTALL);

    private final ObjectMapper objectMapper;

    public TicketDraftParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 从工具返回文本中提取草稿
     *
     * @param toolResult 工具返回文本
     * @return 草稿，无标记块或解析失败返回 null
     */
    public TicketDraft parse(String toolResult) {
        if (toolResult == null || toolResult.isBlank()) {
            return null;
        }

        Matcher m = DRAFT_PATTERN.matcher(toolResult);
        if (!m.find()) {
            return null;
        }

        String json = m.group(1);
        try {
            JsonNode node = objectMapper.readTree(json);
            String title = text(node, "title");
            String priority = text(node, "priority");
            String module = text(node, "module");
            String description = text(node, "description");

            // 标题与描述是工单可处理的最低信息量，缺失则视为无效草稿
            if (title.isBlank() || description.isBlank()) {
                log.warn("⚠️ [DraftParser] 草稿缺少必填字段，已忽略 | title空={} | desc空={}",
                        title.isBlank(), description.isBlank());
                return null;
            }

            return new TicketDraft(title, priority, module, description,
                    node.path("needsApproval").asBoolean(false));

        } catch (Exception e) {
            log.warn("⚠️ [DraftParser] 草稿 JSON 解析失败，已忽略 | {} | json={}",
                    e.getMessage(), truncate(json, 200));
            return null;
        }
    }

    /**
     * 移除草稿标记块
     * <p>
     * 标记块是给编排层的内部数据，不应进入会话记忆或前端展示，
     * 否则会污染上下文并可能被模型下一轮复述。
     * </p>
     *
     * @param toolResult 工具返回文本
     * @return 移除标记后的纯文本
     */
    public String stripMarker(String toolResult) {
        if (toolResult == null) return null;
        return DRAFT_PATTERN.matcher(toolResult).replaceAll("").trim();
    }

    private String text(JsonNode node, String field) {
        return node.path(field).asText("").trim();
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}