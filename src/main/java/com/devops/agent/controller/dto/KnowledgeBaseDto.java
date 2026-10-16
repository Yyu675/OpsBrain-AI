package com.devops.agent.controller.dto;

import com.devops.agent.domain.rag.ChunkProfile;
import com.devops.agent.domain.rag.KnowledgeBase;

import java.time.LocalDateTime;

/**
 * 知识库请求/响应 DTO。
 *
 * @author OpsBrain AI
 * @since 2026-09-18
 */
public class KnowledgeBaseDto {

    /**
     * 创建知识库请求。
     * <p>切片三参数可全部不传（跟随全局默认），也可只传其中一项。</p>
     */
    public record CreateRequest(
            String name,
            String code,
            String description,
            Integer parentChunkSize,
            Integer childChunkSize,
            Integer chunkOverlap
    ) {}

    /**
     * 更新知识库请求。null 字段不修改（读取-合并-写回）。
     *
     * @param clearChunkParams true 时清空全部切片参数（回到跟随全局默认）；
     *                         与三参数字段互斥——同时传参又清空是自相矛盾，服务端拒绝
     */
    public record UpdateRequest(
            String name,
            String code,
            String description,
            Integer parentChunkSize,
            Integer childChunkSize,
            Integer chunkOverlap,
            String status,
            boolean clearChunkParams
    ) {}

    /**
     * 知识库列表/详情项。
     *
     * @param effectiveParentChunkSize 合并全局默认后的生效值（前端展示用，
     *                                 区分「显式配置」与「跟随默认」）
     * @param indexedCount 已索引文档数（库级索引健康度）
     * @param failedCount  索引失败文档数——发现「文档在库里但检索不到」空洞的入口
     */
    public record Item(
            Long id,
            String name,
            String code,
            String description,
            Integer parentChunkSize,
            Integer childChunkSize,
            Integer chunkOverlap,
            int effectiveParentChunkSize,
            int effectiveChildChunkSize,
            int effectiveChunkOverlap,
            String status,
            long docCount,
            long indexedCount,
            long failedCount,
            LocalDateTime createTime,
            LocalDateTime updateTime
    ) {
        public static Item from(KnowledgeBase kb, long docCount) {
            return from(kb, docCount, 0, 0);
        }

        public static Item from(KnowledgeBase kb, long docCount, long indexedCount, long failedCount) {
            ChunkProfile p = kb.resolveProfile();
            return new Item(
                    kb.getId(), kb.getName(), kb.getCode(), kb.getDescription(),
                    kb.getParentChunkSize(), kb.getChildChunkSize(), kb.getChunkOverlap(),
                    p.parentChunkSize(), p.childChunkSize(), p.overlap(),
                    kb.getStatus(), docCount, indexedCount, failedCount,
                    kb.getCreateTime(), kb.getUpdateTime());
        }
    }
}
