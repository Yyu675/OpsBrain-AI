package com.devops.agent.infrastructure.persistence.repo;

import com.devops.agent.infrastructure.persistence.entity.KnowledgeChunkEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 知识库切片写入器（原生 SQL）
 * <p>
 * 不用 JPA 写入的原因：本表有两个 PostgreSQL 特有类型，
 * Hibernate 无法从 Java String 隐式转换，会直接报错：
 * <ul>
 *   <li>{@code embedding vector(1536)} —— pgvector 扩展类型，
 *       报 {@code column "embedding" is of type vector but
 *       expression is of type character varying}</li>
 *   <li>{@code chunk_meta jsonb} —— 需 {@code @JdbcTypeCode(SqlTypes.JSON)}
 *       或显式转型</li>
 * </ul>
 * 本类用 {@code ?::vector} / {@code ?::jsonb} 显式转型，一次解决两者。
 * </p>
 * <p>
 * {@link KnowledgeChunkRepo}（JPA）保留用于查询与统计。
 * </p>
 *
 * @author OpsBrain AI
 * @since 2026-08-09
 */
@Slf4j
@Repository
public class KnowledgeChunkWriter {

    /** 批量插入分批大小，避免单条 SQL 过大 */
    private static final int BATCH_SIZE = 50;

    private final JdbcTemplate jdbcTemplate;

    public KnowledgeChunkWriter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 批量插入切片
     *
     * @param entities 待插入的切片
     * @return 实际插入行数
     */
    public int batchInsert(List<KnowledgeChunkEntity> entities) {
        if (entities == null || entities.isEmpty()) {
            return 0;
        }

        String sql = """
            INSERT INTO sys_knowledge_chunk
                (doc_title, section_header, content, parent_id, parent_text,
                 chunk_meta, embedding,
                 doc_id, content_hash,
                 version, effective_at, expired_at, status, knowledge_source,
                 create_time, update_time)
            VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::vector,
                    ?, ?,
                    ?, ?, ?, ?, ?,
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """;

        int total = 0;
        for (int i = 0; i < entities.size(); i += BATCH_SIZE) {
            int end = Math.min(i + BATCH_SIZE, entities.size());
            List<KnowledgeChunkEntity> batch = entities.subList(i, end);

            // 转为参数数组：batchUpdate(String, List<Object[]>) 签名明确，
            // 避免泛型重载歧义
            List<Object[]> args = new java.util.ArrayList<>(batch.size());
            for (KnowledgeChunkEntity e : batch) {
                args.add(new Object[]{
                        e.getDocTitle(),
                        e.getSectionHeader(),
                        e.getContent(),
                        e.getParentId(),
                        e.getParentText(),
                        e.getChunkMeta() != null ? e.getChunkMeta() : "{}",
                        e.getEmbedding(),
                        e.getDocId(),                       // 全量重建按此删旧切片
                        e.getContentHash(),                  // 切片级去重依据
                        e.getVersion() != null ? e.getVersion() : 1,
                        toTs(e.getEffectiveAt()),
                        toTs(e.getExpiredAt()),
                        e.getStatus() != null ? e.getStatus() : "ACTIVE",
                        e.getKnowledgeSource() != null ? e.getKnowledgeSource() : "UNKNOWN"
                });
            }

            int[] rows = jdbcTemplate.batchUpdate(sql, args);
            for (int r : rows) {
                if (r > 0) total += r;
            }
        }

        log.debug("📥 [ChunkWriter] 批量插入完成 | 提交={} | 入库={}", entities.size(), total);
        return total;
    }

    /**
     * 清空全部切片（全量重建用）
     */
    public int truncateAll() {
        // 用 DELETE 而非 TRUNCATE：TRUNCATE 在事务中会锁表且无法回滚
        return jdbcTemplate.update("DELETE FROM sys_knowledge_chunk");
    }

    /**
     * 按文档标题删除切片（增量更新用）
     */
    public int deleteByDocTitle(String docTitle) {
        return jdbcTemplate.update("DELETE FROM sys_knowledge_chunk WHERE doc_title = ?", docTitle);
    }

    /**
     * 按文档 ID 删除切片
     * <p>
     * 文档级全量重建的第一步（见 {@code KnowledgeDocLifecycle}）：
     * 文档变更时必须按 doc_id 清掉旧切片。
     * 仅靠 doc_title 关联时，改标题即断链，旧切片会残留污染检索。
     * </p>
     */
    public int deleteByDocId(Long docId) {
        if (docId == null) return 0;
        return jdbcTemplate.update("DELETE FROM sys_knowledge_chunk WHERE doc_id = ?", docId);
    }

    private Timestamp toTs(LocalDateTime t) {
        return t != null ? Timestamp.valueOf(t) : null;
    }
}