package com.devops.agent.infrastructure.persistence.repo;

import com.devops.agent.domain.rag.KnowledgeBase;
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
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库仓储（JdbcTemplate，与 {@link KnowledgeDocRepository} 同一模式）。
 *
 * @author OpsBrain AI
 * @since 2026-09-18
 */
@Slf4j
@Repository
public class KnowledgeBaseRepository {

    private final JdbcTemplate jdbcTemplate;

    public KnowledgeBaseRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // ==================== 写 ====================

    /**
     * 插入知识库，回填自增主键。
     *
     * @throws DuplicateKeyException code 大小写不敏感冲突——调用方须转成
     *         用户可读错误，不可静默（用户会以为建好了，实际没有）
     */
    public Long insert(KnowledgeBase kb) {
        String sql = """
            INSERT INTO sys_knowledge_base
                (name, code, description,
                 parent_chunk_size, child_chunk_size, chunk_overlap,
                 status, create_time, update_time)
            VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """;

        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, new String[]{"id"});
            ps.setString(1, kb.getName());
            ps.setString(2, kb.getCode());
            ps.setString(3, kb.getDescription());
            setNullableInt(ps, 4, kb.getParentChunkSize());
            setNullableInt(ps, 5, kb.getChildChunkSize());
            setNullableInt(ps, 6, kb.getChunkOverlap());
            ps.setString(7, kb.getStatus() != null ? kb.getStatus() : KnowledgeBase.STATUS_ACTIVE);
            return ps;
        }, keyHolder);

        Number key = keyHolder.getKey();
        return key != null ? key.longValue() : null;
    }

    /**
     * 更新知识库（全字段覆盖，调用方负责读取-合并-写回）。
     *
     * @return 受影响行数，0 = 不存在
     */
    public int update(KnowledgeBase kb) {
        return jdbcTemplate.update("""
            UPDATE sys_knowledge_base
               SET name = ?, code = ?, description = ?,
                   parent_chunk_size = ?, child_chunk_size = ?, chunk_overlap = ?,
                   status = ?, update_time = CURRENT_TIMESTAMP
             WHERE id = ?
            """,
                kb.getName(), kb.getCode(), kb.getDescription(),
                kb.getParentChunkSize(), kb.getChildChunkSize(), kb.getChunkOverlap(),
                kb.getStatus(), kb.getId());
    }

    // ==================== 读 ====================

    public KnowledgeBase findById(Long id) {
        List<KnowledgeBase> list = jdbcTemplate.query(
                "SELECT * FROM sys_knowledge_base WHERE id = ?", new KbRowMapper(), id);
        return list.isEmpty() ? null : list.get(0);
    }

    public KnowledgeBase findByCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        List<KnowledgeBase> list = jdbcTemplate.query(
                "SELECT * FROM sys_knowledge_base WHERE LOWER(code) = LOWER(?)",
                new KbRowMapper(), code.trim());
        return list.isEmpty() ? null : list.get(0);
    }

    /** 全量列表（知识库数量级是个位数~几十，不分页），按创建时间升序 */
    public List<KnowledgeBase> findAll() {
        return jdbcTemplate.query(
                "SELECT * FROM sys_knowledge_base ORDER BY create_time, id",
                new KbRowMapper());
    }

    /** 库内文档数（列表展示用） */
    public long countDocs(Long kbId) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_knowledge_doc WHERE kb_id = ?", Long.class, kbId);
        return n != null ? n : 0L;
    }

    /**
     * 各库的文档索引健康度（总数 / 已索引 / 索引失败），一次 GROUP BY 取全量。
     *
     * <p>供知识库列表展示「N 篇 · M 篇索引失败」——失败计数是
     * 「全库重建」之外发现索引空洞的主要入口。不逐库 COUNT（N+1），
     * 库数量级虽小，但多一次往返没有任何收益。</p>
     */
    public Map<Long, KbDocStats> docStatsByBase() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
            SELECT kb_id, index_status, COUNT(*) AS cnt
              FROM sys_knowledge_doc
             GROUP BY kb_id, index_status
            """);
        Map<Long, long[]> acc = new HashMap<>();
        for (Map<String, Object> row : rows) {
            Object kbIdObj = row.get("kb_id");
            if (kbIdObj == null) {
                continue; // 历史游离数据（kb_id 为 NULL）不计入任何库
            }
            long kbId = ((Number) kbIdObj).longValue();
            String indexStatus = String.valueOf(row.get("index_status"));
            long cnt = ((Number) row.get("cnt")).longValue();
            long[] a = acc.computeIfAbsent(kbId, k -> new long[3]);
            a[0] += cnt;
            if ("INDEXED".equals(indexStatus)) {
                a[1] += cnt;
            } else if ("FAILED".equals(indexStatus)) {
                a[2] += cnt;
            }
        }
        Map<Long, KbDocStats> result = new HashMap<>();
        acc.forEach((kbId, a) -> result.put(kbId, new KbDocStats(a[0], a[1], a[2])));
        return result;
    }

    /** 库级文档索引健康度（total / indexed / failed） */
    public record KbDocStats(long total, long indexed, long failed) {}

    // ==================== 辅助 ====================

    private void setNullableInt(PreparedStatement ps, int idx, Integer v) throws SQLException {
        if (v != null) {
            ps.setInt(idx, v);
        } else {
            ps.setNull(idx, java.sql.Types.INTEGER);
        }
    }

    private static class KbRowMapper implements RowMapper<KnowledgeBase> {
        @Override
        public KnowledgeBase mapRow(ResultSet rs, int rowNum) throws SQLException {
            KnowledgeBase kb = new KnowledgeBase();
            kb.setId(rs.getLong("id"));
            kb.setName(rs.getString("name"));
            kb.setCode(rs.getString("code"));
            kb.setDescription(rs.getString("description"));
            int parent = rs.getInt("parent_chunk_size");
            kb.setParentChunkSize(rs.wasNull() ? null : parent);
            int child = rs.getInt("child_chunk_size");
            kb.setChildChunkSize(rs.wasNull() ? null : child);
            int overlap = rs.getInt("chunk_overlap");
            kb.setChunkOverlap(rs.wasNull() ? null : overlap);
            kb.setStatus(rs.getString("status"));
            kb.setCreateTime(rs.getObject("create_time", LocalDateTime.class));
            kb.setUpdateTime(rs.getObject("update_time", LocalDateTime.class));
            return kb;
        }
    }
}
