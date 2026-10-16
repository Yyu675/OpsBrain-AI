package com.devops.agent.domain.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 知识文档标签仓储
 * <p>与工单标签同构：全量替换、后端归一化、批量查询避免 N+1。</p>
 *
 * @author OpsBrain AI
 * @since 2026-08-10
 */
@Slf4j
@Repository
public class KnowledgeDocTagRepository {

    private static final int MAX_TAG_LENGTH = 64;
    private static final int MAX_TAGS_PER_DOC = 20;

    private final JdbcTemplate jdbcTemplate;

    public KnowledgeDocTagRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public int replaceTags(Long docId, List<String> tags) {
        deleteByDocId(docId);

        List<String> normalized = normalize(tags);
        if (normalized.isEmpty()) {
            return 0;
        }

        String sql = """
            INSERT INTO sys_knowledge_doc_tag (doc_id, tag)
            VALUES (?, ?)
            ON CONFLICT (doc_id, tag) DO NOTHING
            """;

        int written = 0;
        for (String tag : normalized) {
            written += jdbcTemplate.update(sql, docId, tag);
        }
        return written;
    }

    public List<String> findByDocId(Long docId) {
        try {
            return jdbcTemplate.queryForList(
                    "SELECT tag FROM sys_knowledge_doc_tag WHERE doc_id = ? ORDER BY id",
                    String.class, docId);
        } catch (Exception e) {
            return List.of();
        }
    }

    public Map<Long, List<String>> findByDocIds(List<Long> docIds) {
        if (docIds == null || docIds.isEmpty()) return Map.of();
        String ph = String.join(",", docIds.stream().map(x -> "?").toList());
        String sql = "SELECT doc_id, tag FROM sys_knowledge_doc_tag WHERE doc_id IN ("
                + ph + ") ORDER BY doc_id, id";

        Map<Long, List<String>> result = new HashMap<>();
        try {
            jdbcTemplate.query(sql, rs -> {
                result.computeIfAbsent(rs.getLong("doc_id"), k -> new ArrayList<>())
                        .add(rs.getString("tag"));
            }, docIds.toArray());
        } catch (Exception e) {
            log.warn("⚠️ [DocTag] 批量查询失败 | {}", e.getMessage());
        }
        return result;
    }

    public Map<String, Integer> findHotTags(int limit) {
        String sql = """
            SELECT t.tag, COUNT(*) AS cnt
              FROM sys_knowledge_doc_tag t
              JOIN sys_knowledge_doc d ON d.id = t.doc_id
             WHERE d.status = 'PUBLISHED'
             GROUP BY t.tag
             ORDER BY cnt DESC, t.tag
             LIMIT ?
            """;
        Map<String, Integer> result = new LinkedHashMap<>();
        try {
            jdbcTemplate.query(sql, rs -> {
                result.put(rs.getString("tag"), rs.getInt("cnt"));
            }, limit);
        } catch (Exception e) {
            log.warn("⚠️ [DocTag] 查询热门标签失败 | {}", e.getMessage());
        }
        return result;
    }

    public List<Long> findDocIdsByAllTags(List<String> tags) {
        List<String> normalized = normalize(tags);
        if (normalized.isEmpty()) return List.of();
        String ph = String.join(",", normalized.stream().map(x -> "?").toList());
        String sql = "SELECT doc_id FROM sys_knowledge_doc_tag WHERE tag IN ("
                + ph + ") GROUP BY doc_id HAVING COUNT(DISTINCT tag) = ?";

        Object[] args = new Object[normalized.size() + 1];
        for (int i = 0; i < normalized.size(); i++) args[i] = normalized.get(i);
        args[normalized.size()] = normalized.size();
        try {
            return jdbcTemplate.queryForList(sql, Long.class, args);
        } catch (Exception e) {
            return List.of();
        }
    }

    public int deleteByDocId(Long docId) {
        return jdbcTemplate.update(
                "DELETE FROM sys_knowledge_doc_tag WHERE doc_id = ?", docId);
    }

    private List<String> normalize(List<String> tags) {
        if (tags == null || tags.isEmpty()) return List.of();
        List<String> out = new ArrayList<>();
        Set<String> normalizedNames = new HashSet<>();
        for (String raw : tags) {
            if (raw == null) continue;
            String t = raw.trim();
            if (t.isEmpty()) continue;
            if (t.length() > MAX_TAG_LENGTH) {
                throw new IllegalArgumentException("标签长度不能超过 " + MAX_TAG_LENGTH + " 个字符");
            }
            String normalizedName = t.toLowerCase(java.util.Locale.ROOT);
            if (normalizedNames.add(normalizedName)) out.add(t);
            if (out.size() >= MAX_TAGS_PER_DOC) break;
        }
        return out;
    }
}
