package com.devops.agent.infrastructure.persistence.repo;

import com.devops.agent.infrastructure.persistence.entity.KnowledgeChunkEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 知识库切片数据访问层
 * <p>职责：操作 sys_knowledge_chunk 表的 CRUD，支持批量插入、模糊搜索、删除等操作
 * <p>依赖：Spring Data JPA
 * <p>层级：Infrastructure Layer - Persistence
 * <p>
 * MVP-5 知识治理增强：新增按状态/生效时间/过期时间过滤查询
 * </p>
 *
 * @author OpsBrain AI Team
 * @since 2026-07-15
 */
@Repository
public interface KnowledgeChunkRepo extends JpaRepository<KnowledgeChunkEntity, Long> {

    /**
     * 根据文档标题模糊查询切片
     *
     * @param docTitle 文档标题关键词
     * @return 匹配的切片列表
     */
    List<KnowledgeChunkEntity> findByDocTitleContaining(String docTitle);

    /**
     * 根据父段落 ID 查询所有子切片
     *
     * @param parentId 父段落 ID
     * @return 子切片列表
     */
    List<KnowledgeChunkEntity> findByParentId(String parentId);

    /**
     * 清空知识库（全量重建时使用）
     * 注意：此操作会删除所有切片，需谨慎调用
     */
    @Modifying
    @Query("DELETE FROM KnowledgeChunkEntity")
    void truncateAll();

    /**
     * 根据文档标题删除相关切片（增量更新时使用）
     *
     * @param docTitle 文档标题
     * @return 删除的记录数
     */
    @Modifying
    @Query("DELETE FROM KnowledgeChunkEntity k WHERE k.docTitle = :docTitle")
    int deleteByDocTitle(@Param("docTitle") String docTitle);

    /**
     * 统计孤儿切片数（P1-9）。
     * <p>
     * 孤儿切片指 {@code doc_id IS NULL} 的切片，由已废弃的 {@code /ingest} 端点产生——
     * 它们逃脱文档生命周期治理（{@code deprecate}/{@code purge} 按 {@code doc_id} 清理，
     * 删不到 NULL 的），却仍参与检索，持续污染结果。此计数用于定时清理任务监控与日志。
     * </p>
     */
    @Query("SELECT COUNT(k) FROM KnowledgeChunkEntity k WHERE k.docId IS NULL")
    long countOrphanChunks();

    /**
     * 删除全部孤儿切片（P1-9）。
     * <p>
     * 由 {@link com.devops.agent.application.runtime.OrphanChunkCleanupScheduler} 定时调用。
     * 该端点已停用（返回 410 Gone），不再产生新孤儿；本方法清理历史遗留。
     * </p>
     *
     * @return 删除的记录数
     */
    @Modifying
    @Query("DELETE FROM KnowledgeChunkEntity k WHERE k.docId IS NULL")
    int deleteOrphanChunks();

    /**
     * 统计知识库总切片数
     *
     * @return 切片总数
     */
    @Query("SELECT COUNT(k) FROM KnowledgeChunkEntity k")
    long countTotalChunks();

    /**
     * 统计知识库文档数（按 doc_title 去重）
     *
     * @return 文档数
     */
    @Query("SELECT COUNT(DISTINCT k.docTitle) FROM KnowledgeChunkEntity k")
    long countDistinctDocuments();

    /**
     * 检查文档是否已存在
     *
     * @param docTitle 文档标题
     * @return 是否存在
     */
    boolean existsByDocTitle(String docTitle);

    // ==================== MVP-5 知识治理查询 ====================

    /**
     * 查询生效且未过期的 ACTIVE 状态切片（用于检索时过滤）
     *
     * @param now 当前时间
     * @return 符合条件的切片 ID 列表（供向量检索后二次过滤）
     */
    @Query("""
        SELECT k.id FROM KnowledgeChunkEntity k
        WHERE k.status = 'ACTIVE'
        AND (k.effectiveAt IS NULL OR k.effectiveAt <= :now)
        AND (k.expiredAt IS NULL OR k.expiredAt > :now)
        """)
    List<Long> findActiveChunkIds(@Param("now") LocalDateTime now);

    /**
     * 统计 ACTIVE 且生效的切片数
     */
    @Query("""
        SELECT COUNT(k) FROM KnowledgeChunkEntity k
        WHERE k.status = 'ACTIVE'
        AND (k.effectiveAt IS NULL OR k.effectiveAt <= :now)
        AND (k.expiredAt IS NULL OR k.expiredAt > :now)
        """)
    long countActiveChunks(@Param("now") LocalDateTime now);

    /**
     * 按知识来源统计文档数
     */
    @Query("""
        SELECT k.knowledgeSource, COUNT(DISTINCT k.docTitle)
        FROM KnowledgeChunkEntity k
        WHERE k.status = 'ACTIVE'
        GROUP BY k.knowledgeSource
        """)
    List<Object[]> countDocumentsBySource();

    /**
     * 知识库最后更新时间
     * <p>
     * 无数据时返回 {@code null}——调用方须如实呈现「暂无数据」，
     * 不可退化为当前时间。此前 {@code /stats} 端点直接返回
     * {@code System.currentTimeMillis()}，界面上永远显示「刚刚更新」，
     * 属于用假数据掩盖空状态。
     * </p>
     */
    @Query("SELECT MAX(k.updateTime) FROM KnowledgeChunkEntity k")
    LocalDateTime findLastUpdateTime();

    /**
     * 分页浏览切片（管理后台）
     * <p>
     * 只返回 ACTIVE 且在有效期内的切片，与检索口径一致——
     * 否则管理员在后台看到某切片，AI 却检索不到它，会误判为检索故障。
     * </p>
     */
    @Query("""
        SELECT k FROM KnowledgeChunkEntity k
        WHERE k.status = 'ACTIVE'
          AND (k.effectiveAt IS NULL OR k.effectiveAt <= :now)
          AND (k.expiredAt   IS NULL OR k.expiredAt   >  :now)
        ORDER BY k.docTitle, k.id
        """)
    org.springframework.data.domain.Page<KnowledgeChunkEntity> findActivePage(
            @Param("now") LocalDateTime now,
            org.springframework.data.domain.Pageable pageable);

    /**
     * 按关键词分页检索切片（管理后台搜索）
     * <p>
     * 匹配文档标题、章节标题与正文。{@code LIKE} 元字符由调用方转义——
     * 不转义时用户搜「50%」会命中全部记录（{@code %} 成通配符）。
     * </p>
     */
    @Query("""
        SELECT k FROM KnowledgeChunkEntity k
        WHERE k.status = 'ACTIVE'
          AND (k.effectiveAt IS NULL OR k.effectiveAt <= :now)
          AND (k.expiredAt   IS NULL OR k.expiredAt   >  :now)
          AND (LOWER(k.docTitle)      LIKE :kw ESCAPE '\\'
            OR LOWER(k.sectionHeader) LIKE :kw ESCAPE '\\'
            OR LOWER(k.content)       LIKE :kw ESCAPE '\\')
        ORDER BY k.docTitle, k.id
        """)
    org.springframework.data.domain.Page<KnowledgeChunkEntity> searchActivePage(
            @Param("now") LocalDateTime now,
            @Param("kw") String keywordPattern,
            org.springframework.data.domain.Pageable pageable);
}
