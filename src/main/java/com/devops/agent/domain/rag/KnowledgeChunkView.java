package com.devops.agent.domain.rag;

import java.time.LocalDateTime;

/**
 * 知识库切片视图 DTO（管理后台浏览用）
 * <p>
 * 解耦 Controller 与 infrastructure 层的 {@code KnowledgeChunkEntity}。
 * Controller 不应直接依赖 infrastructure 实体——六层架构要求依赖方向单向，
 * Controller → domain，不得 Controller → infrastructure。
 * </p>
 *
 * @author OpsBrain AI
 * @since 2026-08-18
 */
public record KnowledgeChunkView(
        Long id,
        String docTitle,
        String sectionHeader,
        String contentPreview,
        int contentLength,
        String parentId,
        boolean hasParentText,
        int version,
        String status,
        String knowledgeSource,
        LocalDateTime effectiveAt,
        LocalDateTime expiredAt,
        LocalDateTime createTime,
        LocalDateTime updateTime
) {
}
