package com.devops.agent.controller.dto;

import java.util.List;
import java.util.Map;

/**
 * 工单热门标签响应（GET /api/v1/tickets/tags/hot）。
 *
 * <p>P0-2b 强契约化：替代弱类型 {@code Map<String, Object>}。
 * 字段严格对齐前端 {@code ticket.service.ts#fetchHotTags} 消费端解构期望。</p>
 *
 * @param tags   按热度降序排列的标签列表
 * @param counts 每个标签对应的出现次数
 */
public record TicketHotTagsResponse(
        List<String> tags,
        Map<String, Integer> counts
) {
}
