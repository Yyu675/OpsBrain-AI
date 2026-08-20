package com.devops.agent.infrastructure.persistence.repo;

import com.devops.agent.domain.rag.KnowledgeTag;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Locale;

@Repository
public class KnowledgeTagRepository {

    private final JdbcTemplate jdbcTemplate;

    public KnowledgeTagRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void ensureSchema() {
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS sys_knowledge_tag (
                id BIGSERIAL PRIMARY KEY,
                name VARCHAR(64) NOT NULL,
                normalized_name VARCHAR(64) NOT NULL,
                description VARCHAR(255),
                color VARCHAR(16),
                status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
                create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
            """);
        jdbcTemplate.execute("""
            CREATE UNIQUE INDEX IF NOT EXISTS uk_knowledge_tag_normalized
                ON sys_knowledge_tag (normalized_name)
            """);
        jdbcTemplate.update("""
            INSERT INTO sys_knowledge_tag (name, normalized_name)
            SELECT s.name, s.normalized_name
              FROM (
                    SELECT MIN(TRIM(t.tag)) AS name, LOWER(TRIM(t.tag)) AS normalized_name
                      FROM sys_knowledge_doc_tag t
                     WHERE TRIM(t.tag) <> ''
                     GROUP BY LOWER(TRIM(t.tag))
                   ) s
             WHERE NOT EXISTS (
                    SELECT 1 FROM sys_knowledge_tag k
                     WHERE k.normalized_name = s.normalized_name
               )
            """);
    }

    public List<KnowledgeTag> findAll() {
        return jdbcTemplate.query("""
            SELECT k.id, k.name, k.description, k.color,
                   COUNT(DISTINCT t.doc_id) AS usage_count
              FROM sys_knowledge_tag k
              LEFT JOIN sys_knowledge_doc_tag t ON LOWER(t.tag) = k.normalized_name
             WHERE k.status = 'ACTIVE'
             GROUP BY k.id, k.name, k.description, k.color
             ORDER BY usage_count DESC, LOWER(k.name), k.id
            """, (rs, n) -> new KnowledgeTag(
                rs.getLong("id"), rs.getString("name"), rs.getString("description"),
                rs.getString("color"), rs.getLong("usage_count")));
    }

    /** 文档编辑时自动初始化新标签字典记录。 */
    public void ensureNames(List<String> names) {
        if (names == null) return;
        for (String raw : names) {
            if (raw == null || raw.isBlank()) continue;
            String value = raw.trim();
            if (value.length() > 64) throw new IllegalArgumentException("标签长度不能超过64个字符");
            jdbcTemplate.update("""
                INSERT INTO sys_knowledge_tag (name, normalized_name)
                VALUES (?, ?)
                ON CONFLICT (normalized_name) DO NOTHING
                """, value, value.toLowerCase(Locale.ROOT));
        }
    }

    public KnowledgeTag findById(long id) {
        List<KnowledgeTag> rows = jdbcTemplate.query("""
            SELECT k.id, k.name, k.description, k.color,
                   COUNT(DISTINCT t.doc_id) AS usage_count
              FROM sys_knowledge_tag k
              LEFT JOIN sys_knowledge_doc_tag t ON LOWER(t.tag) = k.normalized_name
             WHERE k.id = ? AND k.status = 'ACTIVE'
             GROUP BY k.id, k.name, k.description, k.color
            """, (rs, n) -> new KnowledgeTag(
                rs.getLong("id"), rs.getString("name"), rs.getString("description"),
                rs.getString("color"), rs.getLong("usage_count")), id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public KnowledgeTag create(String name, String description, String color) {
        String value = normalizeName(name);
        try {
            Long id = jdbcTemplate.queryForObject("""
                INSERT INTO sys_knowledge_tag (name, normalized_name, description, color)
                VALUES (?, ?, ?, ?)
                RETURNING id
                """, Long.class, value, value.toLowerCase(java.util.Locale.ROOT), description, color);
            return findById(id);
        } catch (DataIntegrityViolationException e) {
            throw new IllegalArgumentException("标签名称已存在");
        }
    }

    public KnowledgeTag rename(long id, String name, String description, String color) {
        KnowledgeTag existing = require(id);
        String value = normalizeName(name);
        try {
            jdbcTemplate.update("""
                UPDATE sys_knowledge_tag
                   SET name = ?, normalized_name = ?, description = ?, color = ?, update_time = CURRENT_TIMESTAMP
                 WHERE id = ? AND status = 'ACTIVE'
                """, value, value.toLowerCase(java.util.Locale.ROOT), description, color, id);
            jdbcTemplate.update("""
                UPDATE sys_knowledge_doc_tag
                   SET tag = ?
                 WHERE LOWER(tag) = ?
                """, value, existing.name().toLowerCase(java.util.Locale.ROOT));
            return findById(id);
        } catch (DataIntegrityViolationException e) {
            throw new IllegalArgumentException("标签名称已存在");
        }
    }

    public KnowledgeTag merge(long sourceId, long targetId) {
        if (sourceId == targetId) throw new IllegalArgumentException("不能合并自身");
        KnowledgeTag source = require(sourceId);
        KnowledgeTag target = require(targetId);
        jdbcTemplate.update("""
            DELETE FROM sys_knowledge_doc_tag s
             WHERE LOWER(s.tag) = ?
               AND EXISTS (
                    SELECT 1 FROM sys_knowledge_doc_tag t
                     WHERE t.doc_id = s.doc_id AND LOWER(t.tag) = ?
               )
            """, source.name().toLowerCase(java.util.Locale.ROOT), target.name().toLowerCase(java.util.Locale.ROOT));
        jdbcTemplate.update("""
            UPDATE sys_knowledge_doc_tag SET tag = ? WHERE LOWER(tag) = ?
            """, target.name(), source.name().toLowerCase(java.util.Locale.ROOT));
        jdbcTemplate.update("UPDATE sys_knowledge_tag SET status = 'MERGED', update_time = CURRENT_TIMESTAMP WHERE id = ?", sourceId);
        return findById(targetId);
    }

    public void delete(long id, Long replacementId) {
        KnowledgeTag tag = require(id);
        if (replacementId != null) {
            merge(id, replacementId);
        } else if (tag.usageCount() > 0) {
            throw new IllegalStateException("该标签仍被文档使用，请先合并或解除关联");
        }
        jdbcTemplate.update("UPDATE sys_knowledge_tag SET status = 'DELETED', update_time = CURRENT_TIMESTAMP WHERE id = ?", id);
    }

    private KnowledgeTag require(long id) {
        KnowledgeTag tag = findById(id);
        if (tag == null) throw new IllegalStateException("标签不存在");
        return tag;
    }

    private String normalizeName(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) throw new IllegalArgumentException("标签名称不能为空");
        if (value.length() > 64) throw new IllegalArgumentException("标签长度不能超过64个字符");
        return value;
    }
}
