package com.devops.agent.infrastructure.persistence.repo;

import com.devops.agent.domain.rag.KnowledgeDoc;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 知识文档历史版本仓储
 * <p>
 * <b>只存原文，不存向量</b>。向量是可再生的派生物（1536 维 ≈ 6KB/切片，
 * 比正文大两个数量级），原文才是本体。需要回滚时读原文重新向量化即可。
 * </p>
 *
 * @author OpsBrain AI
 * @since 2026-08-10
 */
@Slf4j
@Repository
public class KnowledgeDocHistoryRepository {

    private final JdbcTemplate jdbcTemplate;

    public KnowledgeDocHistoryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 归档当前版本为历史
     * <p>
     * 必须在更新<b>之前</b>调用——此时库里还是旧版本，归档的才是被替换的那一版。
     * 反序会归档新版本，使历史链断裂且无法察觉。
     * </p>
     *
     * @return 受影响行数（归档失败返回 0，调用方须记 ERROR——但**不阻塞文档更新**）
     */
    public int archive(KnowledgeDoc doc, String changeType, String changedBy, String changeReason) {
        String sql = """
            INSERT INTO sys_knowledge_doc_history
                (doc_id, version, title, category, author, content, content_hash,
                 changed_by, change_reason, change_type, create_time)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
            ON CONFLICT (doc_id, version) DO NOTHING
            """;
        try {
            return jdbcTemplate.update(sql,
                    doc.getId(),
                    doc.getVersion(),
                    doc.getTitle(),
                    doc.getCategory(),
                    doc.getAuthor(),
                    doc.getContent(),
                    doc.getContentHash(),
                    changedBy,
                    changeReason,
                    changeType);
        } catch (Exception e) {
            log.error("🚨 [DocHistory] 归档失败，该版本历史将缺失 | docId={} | version={} | {}",
                    doc.getId(), doc.getVersion(), e.getMessage());
            return 0;
        }
    }

    /**
     * 版本历史列表（不含正文）
     */
    public List<Map<String, Object>> listVersions(Long docId) {
        // 列名用 camelCase 别名：前端 KnowledgeDocVersion 契约是 camelCase，
        // 直接返回 queryForList 的 snake_case 键会让版本抽屉的类型/作者/时间渲染为空。
        // 注意：PostgreSQL 会把未加引号的别名折叠为小写，必须用双引号保留大小写
        String sql = """
            SELECT id, doc_id AS "docId", version, title, category, author,
                   content_hash AS "contentHash",
                   LENGTH(content) AS "contentLength",
                   changed_by AS "changedBy", change_reason AS "changeReason",
                   change_type AS "changeType", create_time AS "createTime"
              FROM sys_knowledge_doc_history
             WHERE doc_id = ?
             ORDER BY version DESC
            """;
        return jdbcTemplate.queryForList(sql, docId);
    }

    /**
     * 取指定历史版本全文（供查看/回滚）
     */
    public KnowledgeDoc findVersion(Long docId, int version) {
        String sql = """
            SELECT * FROM sys_knowledge_doc_history
             WHERE doc_id = ? AND version = ?
            """;
        List<KnowledgeDoc> list = jdbcTemplate.query(sql, this::mapHistory, docId, version);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 删除文档的全部历史（物理删除文档时级联）
     */
    public int deleteByDocId(Long docId) {
        return jdbcTemplate.update(
                "DELETE FROM sys_knowledge_doc_history WHERE doc_id = ?", docId);
    }

    public int countVersions(Long docId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_knowledge_doc_history WHERE doc_id = ?",
                Integer.class, docId);
        return n != null ? n : 0;
    }

    private KnowledgeDoc mapHistory(ResultSet rs, int rowNum) throws SQLException {
        KnowledgeDoc d = new KnowledgeDoc();
        // id 取 doc_id 而非历史记录自身 id——调用方拿到的是「文档的某个版本」
        d.setId(rs.getLong("doc_id"));
        d.setVersion(rs.getInt("version"));
        d.setTitle(rs.getString("title"));
        d.setCategory(rs.getString("category"));
        d.setAuthor(rs.getString("author"));
        d.setContent(rs.getString("content"));
        d.setContentHash(rs.getString("content_hash"));
        d.setCreateTime(rs.getObject("create_time", LocalDateTime.class));
        return d;
    }
}