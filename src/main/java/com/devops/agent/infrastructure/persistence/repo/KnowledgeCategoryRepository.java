package com.devops.agent.infrastructure.persistence.repo;

import com.devops.agent.domain.rag.KnowledgeCategory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
public class KnowledgeCategoryRepository {

    private final JdbcTemplate jdbcTemplate;

    public KnowledgeCategoryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<KnowledgeCategory> findAll() {
        return jdbcTemplate.query("""
            SELECT c.id, c.parent_id, c.name, c.sort_order,
                   COUNT(d.id) AS doc_count, c.create_time, c.update_time
              FROM sys_knowledge_category c
              LEFT JOIN sys_knowledge_doc d ON d.category_id = c.id
             WHERE c.status = 'ACTIVE'
             GROUP BY c.id, c.parent_id, c.name, c.sort_order, c.create_time, c.update_time
             ORDER BY c.sort_order, LOWER(c.name), c.id
            """, (rs, rowNum) -> new KnowledgeCategory(
                rs.getLong("id"),
                rs.getObject("parent_id", Long.class),
                rs.getString("name"),
                rs.getInt("sort_order"),
                rs.getLong("doc_count"),
                rs.getObject("create_time", java.time.LocalDateTime.class),
                rs.getObject("update_time", java.time.LocalDateTime.class)));
    }

    public KnowledgeCategory findById(Long id) {
        return jdbcTemplate.query("""
            SELECT c.id, c.parent_id, c.name, c.sort_order,
                   (SELECT COUNT(*) FROM sys_knowledge_doc d
                     WHERE d.category_id = c.id) AS doc_count,
                   c.create_time, c.update_time
              FROM sys_knowledge_category c
             WHERE c.id = ? AND c.status = 'ACTIVE'
            """, rs -> rs.next() ? new KnowledgeCategory(
                rs.getLong("id"), rs.getObject("parent_id", Long.class),
                rs.getString("name"), rs.getInt("sort_order"), rs.getLong("doc_count"),
                rs.getObject("create_time", java.time.LocalDateTime.class),
                rs.getObject("update_time", java.time.LocalDateTime.class)) : null, id);
    }

    public KnowledgeCategory findByName(String name) {
        if (name == null || name.isBlank()) return null;
        return jdbcTemplate.query("""
            SELECT c.id, c.parent_id, c.name, c.sort_order,
                   (SELECT COUNT(*) FROM sys_knowledge_doc d WHERE d.category_id = c.id) AS doc_count,
                   c.create_time, c.update_time
              FROM sys_knowledge_category c
             WHERE c.status = 'ACTIVE' AND LOWER(c.name) = LOWER(?)
            """, rs -> rs.next() ? new KnowledgeCategory(
                rs.getLong("id"), rs.getObject("parent_id", Long.class),
                rs.getString("name"), rs.getInt("sort_order"), rs.getLong("doc_count"),
                rs.getObject("create_time", java.time.LocalDateTime.class),
                rs.getObject("update_time", java.time.LocalDateTime.class)) : null, name.trim());
    }

    public Long insert(Long parentId, String name, int sortOrder) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO sys_knowledge_category (parent_id, name, sort_order)
                VALUES (?, ?, ?)
                """, new String[]{"id"});
            if (parentId == null) ps.setNull(1, java.sql.Types.BIGINT);
            else ps.setLong(1, parentId);
            ps.setString(2, name);
            ps.setInt(3, sortOrder);
            return ps;
        }, keys);
        Number key = keys.getKey();
        return key == null ? null : key.longValue();
    }

    public int update(Long id, Long parentId, String name, int sortOrder) {
        return jdbcTemplate.update("""
            UPDATE sys_knowledge_category
               SET parent_id = ?, name = ?, sort_order = ?, update_time = CURRENT_TIMESTAMP
             WHERE id = ? AND status = 'ACTIVE'
            """, parentId, name, sortOrder, id);
    }

    public int renameDocuments(String oldName, String newName) {
        return jdbcTemplate.update("""
            UPDATE sys_knowledge_doc
               SET category = ?, update_time = CURRENT_TIMESTAMP
             WHERE category_id IN (SELECT id FROM sys_knowledge_category WHERE LOWER(name) = LOWER(?))
            """, newName, oldName);
    }

    public int delete(Long id) {
        return jdbcTemplate.update("DELETE FROM sys_knowledge_category WHERE id = ?", id);
    }

    public long countChildren(Long id) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_knowledge_category WHERE parent_id = ? AND status = 'ACTIVE'",
                Long.class, id);
        return count == null ? 0 : count;
    }

    public List<Map<String, Object>> findDocuments() {
        return jdbcTemplate.query("""
            SELECT id, title, category, category_id, version, status, update_time
              FROM sys_knowledge_doc
             ORDER BY update_time DESC, id DESC
            """, (rs, rowNum) -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", rs.getLong("id"));
                item.put("title", rs.getString("title"));
                item.put("category", rs.getString("category"));
                item.put("categoryId", rs.getObject("category_id", Long.class));
                item.put("version", rs.getInt("version"));
                item.put("status", rs.getString("status"));
                item.put("updateTime", rs.getObject("update_time", java.time.LocalDateTime.class));
                return item;
            });
    }

    public int moveDocument(Long docId, Long categoryId, String categoryName) {
        return jdbcTemplate.update("""
            UPDATE sys_knowledge_doc
               SET category = ?, category_id = ?, update_time = CURRENT_TIMESTAMP
             WHERE id = ?
            """, categoryName, categoryId, docId);
    }
}
