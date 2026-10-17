package com.devops.agent.controller.dto;

/**
 * 知识库单条热门标签响应项（GET /api/v1/knowledge/docs/tags/hot）。
 *
 * <p>P0-2b 强契约化：替代原先的 {@code Map<String, Object>} 列表。
 * 字段严格对齐前端 {@code types.ts#KnowledgeHotTag}。</p>
 *
 * @param tag   标签名称
 * @param count 引用该标签的发布文档数
 */
public record KnowledgeHotTagResponse(
        String tag,
        int count
) {
}
