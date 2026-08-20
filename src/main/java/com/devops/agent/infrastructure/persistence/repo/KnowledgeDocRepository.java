package com.devops.agent.infrastructure.persistence.repo;

import com.devops.agent.domain.rag.KnowledgeDoc;
import com.devops.agent.domain.rag.KnowledgeDocLifecycle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识文档仓储
 * <p>
 * 用 JdbcTemplate 而非 JPA：涉及 {@code content_hash} 唯一冲突（去重）、
 * 版本号 CAS（乐观锁），原生 SQL 表达更直接。
 * </p>
 *
 * @author OpsBrain AI
 * @since 2026-08-10
 */
@Slf4j
@Repository
public class KnowledgeDocRepository {

    private final JdbcTemplate jdbcTemplate;

    public KnowledgeDocRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // ==================== 写 ====================

    /**
     * 插入文档，回填自增主键
     *
     * @throws DuplicateKeyException content_hash 冲突（内容完全相同）——
     *         调用方须捕获并提示，不可静默忽略，否则用户不知道自己提交的是重复内容
     */
    public Long insert(KnowledgeDoc doc) {
        String sql = """
            INSERT INTO sys_knowledge_doc
                (title, category, category_id, author, content, summary,
                 version, content_hash, simhash,
                 status, index_status, chunk_count,
                 effective_at, expired_at, knowledge_source,
                 source_ticket_id, source_type,
                 create_time, update_time)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """;

        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            // 显式指定返回列：PostgreSQL 的 RETURN_GENERATED_KEYS 会返回
            // 全部列，导致 KeyHolder.getKey() 抛「multiple keys」
            PreparedStatement ps = connection.prepareStatement(sql, new String[]{"id"});
            ps.setString(1, doc.getTitle());
            ps.setString(2, doc.getCategory());
            if (doc.getCategoryId() != null) ps.setLong(3, doc.getCategoryId());
            else ps.setNull(3, java.sql.Types.BIGINT);
            ps.setString(4, doc.getAuthor());
            ps.setString(5, doc.getContent());
            ps.setString(6, doc.getSummary());
            ps.setInt(7, doc.getVersion() != null ? doc.getVersion() : 1);
            ps.setString(8, doc.getContentHash());
            if (doc.getSimhash() != null) {
                ps.setLong(9, doc.getSimhash());
            } else {
                ps.setNull(9, java.sql.Types.BIGINT);
            }
            ps.setString(10, doc.getStatus() != null ? doc.getStatus() : KnowledgeDocLifecycle.STATUS_DRAFT);
            ps.setString(11, doc.getIndexStatus() != null ? doc.getIndexStatus() : KnowledgeDocLifecycle.INDEX_PENDING);
            ps.setInt(12, doc.getChunkCount() != null ? doc.getChunkCount() : 0);
            ps.setTimestamp(13, toTs(doc.getEffectiveAt()));
            ps.setTimestamp(14, toTs(doc.getExpiredAt()));
            ps.setString(15, doc.getKnowledgeSource() != null ? doc.getKnowledgeSource() : "SOP");
            // L1.5 来源回链
            if (doc.getSourceTicketId() != null) {
                ps.setLong(16, doc.getSourceTicketId());
            } else {
                ps.setNull(16, java.sql.Types.BIGINT);
            }
            ps.setString(17, doc.getSourceType());
            return ps;
        }, keyHolder);

        Number key = keyHolder.getKey();
        return key != null ? key.longValue() : null;
    }

    /**
     * 更新文档（版本号自增 + CAS）
     *
     * @param expectedVersion 客户端持有的版本号，null 则退化为无锁覆盖
     * @return 受影响行数，0 = 不存在或版本冲突
     */
    public int update(KnowledgeDoc doc, Integer expectedVersion) {
        boolean withCas = expectedVersion != null;

        String sql = withCas ? """
            UPDATE sys_knowledge_doc
               SET title = ?, category = ?, category_id = ?, author = ?, content = ?, summary = ?,
                   content_hash = ?, simhash = ?, status = ?,
                   index_status = ?, index_error = NULL,
                   effective_at = ?, expired_at = ?, knowledge_source = ?,
                   source_ticket_id = ?, source_type = ?,
                   version = version + 1, update_time = CURRENT_TIMESTAMP
             WHERE id = ? AND version = ?
            """ : """
            UPDATE sys_knowledge_doc
               SET title = ?, category = ?, category_id = ?, author = ?, content = ?, summary = ?,
                   content_hash = ?, simhash = ?, status = ?,
                   index_status = ?, index_error = NULL,
                   effective_at = ?, expired_at = ?, knowledge_source = ?,
                   source_ticket_id = ?, source_type = ?,
                   version = version + 1, update_time = CURRENT_TIMESTAMP
             WHERE id = ?
            """;

        List<Object> args = new ArrayList<>();
        args.add(doc.getTitle());
        args.add(doc.getCategory());
        args.add(doc.getCategoryId());
        args.add(doc.getAuthor());
        args.add(doc.getContent());
        args.add(doc.getSummary());
        args.add(doc.getContentHash());
        args.add(doc.getSimhash());
        args.add(doc.getStatus());
        args.add(doc.getIndexStatus());
        args.add(toTs(doc.getEffectiveAt()));
        args.add(toTs(doc.getExpiredAt()));
        args.add(doc.getKnowledgeSource());
        args.add(doc.getSourceTicketId());
        args.add(doc.getSourceType());
        args.add(doc.getId());
        if (withCas) {
            args.add(expectedVersion);
        }

        return jdbcTemplate.update(sql, args.toArray());
    }

    public int updateCategory(Long docId, String category, Long categoryId, Integer expectedVersion) {
        if (expectedVersion == null) {
            return jdbcTemplate.update("""
                UPDATE sys_knowledge_doc
                   SET category = ?, category_id = ?, version = version + 1,
                       update_time = CURRENT_TIMESTAMP
                 WHERE id = ?
                """, category, categoryId, docId);
        }
        return jdbcTemplate.update("""
            UPDATE sys_knowledge_doc
               SET category = ?, category_id = ?, version = version + 1,
                   update_time = CURRENT_TIMESTAMP
             WHERE id = ? AND version = ?
            """, category, categoryId, docId, expectedVersion);
    }

    /**
     * 更新向量化状态
     * <p>独立方法：向量化是文档保存之后的步骤，成败不应回滚文档本身。</p>
     */
    public int updateIndexStatus(Long docId, String indexStatus, String error, int chunkCount) {
        String sql = """
            UPDATE sys_knowledge_doc
               SET index_status = ?, index_error = ?, chunk_count = ?,
                   indexed_at = CASE WHEN ? = 'INDEXED' THEN CURRENT_TIMESTAMP ELSE indexed_at END,
                   update_time = CURRENT_TIMESTAMP
             WHERE id = ?
            """;
        return jdbcTemplate.update(sql, indexStatus, error, chunkCount, indexStatus, docId);
    }

    /**
     * 变更文档状态（发布/废弃/归档）
     * <p>
     * <b>不递增 version</b>：版本号代表「内容版本」，历史归档以其为键。
     * 发布/废弃是状态变更不是内容变更，递增会让历史版本槽位被
     * 状态操作占满——发布两次就在历史里出现两个相同内容的版本。
     * 内容变更的版本递增由 {@link #update} 负责。
     * </p>
     */
    public int updateStatus(Long docId, String status, String indexStatus) {
        String sql = """
            UPDATE sys_knowledge_doc
               SET status = ?, index_status = ?,
                   update_time = CURRENT_TIMESTAMP
             WHERE id = ? AND status <> ?
            """;
        return jdbcTemplate.update(sql, status, indexStatus, docId, status);
    }

    /**
     * 物理删除（仅合规场景，调用方须记审计）
     */
    public int deleteById(Long docId) {
        return jdbcTemplate.update("DELETE FROM sys_knowledge_doc WHERE id = ?", docId);
    }

    // ==================== 读 ====================

    public KnowledgeDoc findById(Long docId) {
        List<KnowledgeDoc> list = jdbcTemplate.query(
                "SELECT * FROM sys_knowledge_doc WHERE id = ?", new DocRowMapper(), docId);
        return list.isEmpty() ? null : list.get(0);
    }

    public KnowledgeDoc findByContentHash(String contentHash) {
        List<KnowledgeDoc> list = jdbcTemplate.query(
                "SELECT * FROM sys_knowledge_doc WHERE content_hash = ?", new DocRowMapper(), contentHash);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 按源工单反查已沉淀的文档（L1.5 来源回链）。
     * <p>只返回未物理删除的活跃文档（含草稿/已发布/已废弃/已归档），
     * 供工单详情页展示「已沉淀为知识」徽标与跳转入口。</p>
     */
    public List<KnowledgeDoc> findBySourceTicketId(Long sourceTicketId) {
        if (sourceTicketId == null) {
            return List.of();
        }
        return jdbcTemplate.query(
                "SELECT * FROM sys_knowledge_doc WHERE source_ticket_id = ? ORDER BY create_time DESC",
                new DocRowMapper(), sourceTicketId);
    }

    public List<KnowledgeDoc> findByCategory(Long categoryId, String categoryName) {
        return jdbcTemplate.query(
                "SELECT * FROM sys_knowledge_doc WHERE category_id = ? OR (category_id IS NULL AND LOWER(category) = LOWER(?))",
                new DocRowMapper(), categoryId, categoryName);
    }

    /**
     * 查找近似重复候选（同分类已发布/草稿，限最近）
     */
    public List<KnowledgeDoc> findSimhashCandidates(String category, Long excludeDocId, int limit) {
        StringBuilder sql = new StringBuilder("""
            SELECT id, title, content_hash, simhash, status, version, category
              FROM sys_knowledge_doc
             WHERE simhash IS NOT NULL
               AND status IN ('PUBLISHED', 'DRAFT')
            """);
        List<Object> args = new ArrayList<>();

        if (category != null && !category.isBlank()) {
            sql.append(" AND category = ?");
            args.add(category);
        }
        if (excludeDocId != null) {
            sql.append(" AND id <> ?");
            args.add(excludeDocId);
        }
        sql.append(" ORDER BY update_time DESC LIMIT ?");
        args.add(limit);

        // 只取判重必需字段，不拉 content（候选可能上百条）
        return jdbcTemplate.query(sql.toString(), (rs, n) -> {
            KnowledgeDoc d = new KnowledgeDoc();
            d.setId(rs.getLong("id"));
            d.setTitle(rs.getString("title"));
            d.setContentHash(rs.getString("content_hash"));
            long sh = rs.getLong("simhash");
            d.setSimhash(rs.wasNull() ? null : sh);
            d.setStatus(rs.getString("status"));
            d.setVersion(rs.getInt("version"));
            d.setCategory(rs.getString("category"));
            long categoryId = rs.getLong("category_id");
            d.setCategoryId(rs.wasNull() ? null : categoryId);
            return d;
        }, args.toArray());
    }

    public List<KnowledgeDoc> findPage(int page, int size, String status, String category,
                                       String keyword, String tag, String sort) {
        StringBuilder sql = new StringBuilder("SELECT * FROM sys_knowledge_doc WHERE 1=1");
        List<Object> args = new ArrayList<>();

        if (notBlank(status)) {
            sql.append(" AND status = ?");
            args.add(status.trim().toUpperCase());
        }
        if (notBlank(category)) {
            sql.append(" AND category = ?");
            args.add(category.trim());
        }
        if (notBlank(keyword)) {
            sql.append(" AND (LOWER(title) LIKE ? ESCAPE '\\' OR LOWER(content) LIKE ? ESCAPE '\\')");
            String kw = "%" + escapeLike(keyword.trim().toLowerCase()) + "%";
            args.add(kw);
            args.add(kw);
        }
        applyTagFilter(sql, args, tag);

        String normalizedSort = sort == null ? "UPDATED_DESC" : sort.trim().toUpperCase();
        switch (normalizedSort) {
            case "CREATED_DESC" -> sql.append(" ORDER BY create_time DESC, id DESC");
            case "TITLE_ASC" -> sql.append(" ORDER BY LOWER(title), id DESC");
            case "RELEVANCE" -> {
                if (notBlank(keyword)) {
                    String normalizedKeyword = keyword.trim().toLowerCase();
                    sql.append(" ORDER BY CASE")
                            .append(" WHEN LOWER(title) = ? THEN 0")
                            .append(" WHEN LOWER(title) LIKE ? ESCAPE '\\' THEN 1")
                            .append(" WHEN LOWER(title) LIKE ? ESCAPE '\\' THEN 2")
                            .append(" ELSE 3 END, update_time DESC");
                    args.add(normalizedKeyword);
                    args.add(escapeLike(normalizedKeyword) + "%");
                    args.add("%" + escapeLike(normalizedKeyword) + "%");
                } else {
                    sql.append(" ORDER BY update_time DESC, id DESC");
                }
            }
            default -> sql.append(" ORDER BY update_time DESC, id DESC");
        }
        sql.append(" LIMIT ? OFFSET ?");
        args.add(size);
        args.add((long) (page - 1) * size);

        return jdbcTemplate.query(sql.toString(), new DocRowMapper(), args.toArray());
    }

    public long countByQuery(String status, String category, String keyword, String tag) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM sys_knowledge_doc WHERE 1=1");
        List<Object> args = new ArrayList<>();

        if (notBlank(status)) {
            sql.append(" AND status = ?");
            args.add(status.trim().toUpperCase());
        }
        if (notBlank(category)) {
            sql.append(" AND category = ?");
            args.add(category.trim());
        }
        if (notBlank(keyword)) {
            sql.append(" AND (LOWER(title) LIKE ? ESCAPE '\\' OR LOWER(content) LIKE ? ESCAPE '\\')");
            String kw = "%" + escapeLike(keyword.trim().toLowerCase()) + "%";
            args.add(kw);
            args.add(kw);
        }
        applyTagFilter(sql, args, tag);

        Long n = jdbcTemplate.queryForObject(sql.toString(), Long.class, args.toArray());
        return n != null ? n : 0L;
    }

    /**
     * 扁平分类聚合（侧栏导航用）：仅统计非空分类，按文档数降序
     */
    public List<Map<String, Object>> findCategories() {
        String sql = """
            SELECT category AS name, COUNT(*) AS count
              FROM sys_knowledge_doc
             WHERE category IS NOT NULL AND category <> ''
             GROUP BY category
             ORDER BY count DESC, category
            """;
        return jdbcTemplate.query(sql, (rs, n) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", rs.getString("name"));
            row.put("count", rs.getLong("count"));
            return row;
        });
    }

    private void applyTagFilter(StringBuilder sql, List<Object> args, String tag) {
        if (notBlank(tag)) {
            sql.append(" AND EXISTS (SELECT 1 FROM sys_knowledge_doc_tag t"
                    + " WHERE t.doc_id = sys_knowledge_doc.id AND t.tag = ?)");
            args.add(tag.trim());
        }
    }

    /**
     * 查找向量化失败或待处理的文档（供定时补偿）
     */
    public List<KnowledgeDoc> findNeedingIndex(int limit) {
        String sql = """
            SELECT * FROM sys_knowledge_doc
             WHERE status = 'PUBLISHED'
               AND index_status IN ('PENDING', 'FAILED')
             ORDER BY update_time
             LIMIT ?
            """;
        return jdbcTemplate.query(sql, new DocRowMapper(), limit);
    }

    /**
     * 查询超保留期的已废弃文档（供归档定时扫描，R3-①）。
     * <p>
     * 判定：{@code status='DEPRECATED'} 且 {@code update_time} 早于截止时间。
     * deprecate 时 {@code update_time} 刷新为当下，之后任何写操作（含状态变更）
     * 都会续期——因此 {@code update_time} 恰好构成「废弃后闲置时长」的时钟，
     * 与 6.21 状态机（version 不随状态变更递增）口径一致。
     * </p>
     * <p>轻量查询：只取 id/title/category/status/update_time，不拉 content
     * （超期文档可能上百篇，排序只需时间列）。</p>
     */
    public List<KnowledgeDoc> findDeprecatedBefore(LocalDateTime cutoff, int limit) {
        String sql = """
            SELECT id, title, category, status, update_time
              FROM sys_knowledge_doc
             WHERE status = ?
               AND update_time < ?
             ORDER BY update_time
             LIMIT ?
            """;
        return jdbcTemplate.query(sql, (rs, n) -> {
            KnowledgeDoc d = new KnowledgeDoc();
            d.setId(rs.getLong("id"));
            d.setTitle(rs.getString("title"));
            d.setCategory(rs.getString("category"));
            d.setStatus(rs.getString("status"));
            d.setUpdateTime(rs.getObject("update_time", LocalDateTime.class));
            return d;
        }, KnowledgeDocLifecycle.STATUS_DEPRECATED, Timestamp.valueOf(cutoff), limit);
    }

    /**
     * 统计超保留期的已废弃文档数。
     * <p>与 {@link #findDeprecatedBefore} 共用同一 WHERE 条件——
     * 6.15 契约：计数与列表条件必须一致，否则 total 与行数矛盾。</p>
     */
    public long countDeprecatedBefore(LocalDateTime cutoff) {
        Long n = jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM sys_knowledge_doc
             WHERE status = ?
               AND update_time < ?
            """, Long.class, KnowledgeDocLifecycle.STATUS_DEPRECATED, Timestamp.valueOf(cutoff));
        return n != null ? n : 0L;
    }

    // ==================== 辅助 ====================

    private boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private Timestamp toTs(LocalDateTime t) {
        return t != null ? Timestamp.valueOf(t) : null;
    }

    private static class DocRowMapper implements RowMapper<KnowledgeDoc> {
        @Override
        public KnowledgeDoc mapRow(ResultSet rs, int rowNum) throws SQLException {
            KnowledgeDoc d = new KnowledgeDoc();
            d.setId(rs.getLong("id"));
            d.setTitle(rs.getString("title"));
            d.setCategory(rs.getString("category"));
            long categoryId = rs.getLong("category_id");
            d.setCategoryId(rs.wasNull() ? null : categoryId);
            d.setAuthor(rs.getString("author"));
            d.setContent(rs.getString("content"));
            d.setSummary(rs.getString("summary"));
            d.setVersion(rs.getInt("version"));
            d.setContentHash(rs.getString("content_hash"));
            long sh = rs.getLong("simhash");
            d.setSimhash(rs.wasNull() ? null : sh);
            d.setStatus(rs.getString("status"));
            d.setIndexStatus(rs.getString("index_status"));
            d.setIndexError(rs.getString("index_error"));
            d.setIndexedAt(rs.getObject("indexed_at", LocalDateTime.class));
            d.setChunkCount(rs.getInt("chunk_count"));
            d.setEffectiveAt(rs.getObject("effective_at", LocalDateTime.class));
            d.setExpiredAt(rs.getObject("expired_at", LocalDateTime.class));
            d.setKnowledgeSource(rs.getString("knowledge_source"));
            long stid = rs.getLong("source_ticket_id");
            d.setSourceTicketId(rs.wasNull() ? null : stid);
            d.setSourceType(rs.getString("source_type"));
            d.setCreateTime(rs.getObject("create_time", LocalDateTime.class));
            d.setUpdateTime(rs.getObject("update_time", LocalDateTime.class));
            return d;
        }
    }
}
