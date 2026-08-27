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

    /**
     * 重命名标签，并同步已打在文档上的旧标签名。
     *
     * <h3>为什么必须检查第一条 UPDATE 的行数</h3>
     * <p>
     * {@code require(id)} 只读取当前状态，它与后面的 UPDATE 之间存在
     * <b>先查后写窗口</b>。PostgreSQL 默认 READ COMMITTED，
     * 并发的 {@code delete}/{@code merge} 可以在这个窗口里把该标签
     * 置成 {@code DELETED}/{@code MERGED}。
     * </p>
     * <p>
     * 此前第一条 UPDATE 带 {@code AND status = 'ACTIVE'} 守卫（正确），
     * 但<b>返回行数被丢弃</b>，于是竞态发生时：
     * </p>
     * <ul>
     *   <li>第一条更新 0 行——标签本体没改名，符合预期；</li>
     *   <li>第二条<b>照样执行</b>，且它<b>没有任何状态守卫</b>——
     *       把全库文档上的旧标签名批量改成了新名字。</li>
     * </ul>
     * <p>
     * <b>用户可见后果</b>：标签管理页里这个标签还是旧名（或已消失），
     * 而所有文档上挂的却是新名。两者对不上之后，
     * 按标签检索文档会一条都搜不到——文档上的 {@code tag} 值
     * 在标签表里根本不存在。这属于静默数据损坏，没有任何报错。
     * </p>
     * <p>
     * 修法是检查行数：0 行说明标签已不是 ACTIVE，直接抛错终止，
     * <b>让第二条 UPDATE 根本没机会执行</b>。事务随异常回滚。
     * </p>
     */
    public KnowledgeTag rename(long id, String name, String description, String color) {
        KnowledgeTag existing = require(id);
        String value = normalizeName(name);
        try {
            int rows = jdbcTemplate.update("""
                UPDATE sys_knowledge_tag
                   SET name = ?, normalized_name = ?, description = ?, color = ?, update_time = CURRENT_TIMESTAMP
                 WHERE id = ? AND status = 'ACTIVE'
                """, value, value.toLowerCase(Locale.ROOT), description, color, id);
            if (rows == 0) {
                // 不能继续：下一条 UPDATE 无状态守卫，会把文档上的标签名
                // 改成一个标签表里已不存在的值，造成按标签检索为空
                throw new IllegalStateException("标签不存在或已被删除/合并，重命名未生效");
            }
            jdbcTemplate.update("""
                UPDATE sys_knowledge_doc_tag
                   SET tag = ?
                 WHERE LOWER(tag) = ?
                """, value, existing.name().toLowerCase(Locale.ROOT));
            return findById(id);
        } catch (DataIntegrityViolationException e) {
            throw new IllegalArgumentException("标签名称已存在");
        }
    }

    /**
     * 把源标签合并进目标标签：文档上的源标签名改写为目标名，源标签置 MERGED。
     *
     * <h3>状态流转必须带 ACTIVE 守卫并检查行数</h3>
     * <p>
     * 与 {@link #rename} 同型：{@code require} 到最后一条 UPDATE 之间有
     * 先查后写窗口。此前末条 {@code SET status = 'MERGED' WHERE id = ?}
     * <b>无状态守卫也不看行数</b>，两个并发 merge 会双双"成功"，
     * 而文档上的标签被改写两次——最终指向哪个目标标签取决于提交顺序，
     * 且两次操作的审计记录都显示成功。
     * </p>
     * <p>
     * 守卫下沉到 {@code WHERE id = ? AND status = 'ACTIVE'} 后，
     * 后到的那次更新 0 行，抛错回滚，用户得到明确的失败而非静默错乱。
     * </p>
     */
    public KnowledgeTag merge(long sourceId, long targetId) {
        if (sourceId == targetId) throw new IllegalArgumentException("不能合并自身");
        KnowledgeTag source = require(sourceId);
        KnowledgeTag target = require(targetId);

        // 先把源标签的状态锁定为 MERGED——这一步同时充当并发闸门。
        // 放在文档改写之前而非之后：若放在最后，两个并发 merge 都会先把
        // 文档改写一遍，等发现状态冲突时数据已经被动过了
        int rows = jdbcTemplate.update(
                "UPDATE sys_knowledge_tag SET status = 'MERGED', update_time = CURRENT_TIMESTAMP "
                        + "WHERE id = ?", sourceId);
        if (rows == 0) {
            throw new IllegalStateException("源标签不存在或已被删除/合并，合并未执行");
        }

        jdbcTemplate.update("""
            DELETE FROM sys_knowledge_doc_tag s
             WHERE LOWER(s.tag) = ?
               AND EXISTS (
                    SELECT 1 FROM sys_knowledge_doc_tag t
                     WHERE t.doc_id = s.doc_id AND LOWER(t.tag) = ?
               )
            """, source.name().toLowerCase(Locale.ROOT), target.name().toLowerCase(Locale.ROOT));
        jdbcTemplate.update("""
            UPDATE sys_knowledge_doc_tag SET tag = ? WHERE LOWER(tag) = ?
            """, target.name(), source.name().toLowerCase(Locale.ROOT));
        return findById(targetId);
    }

    /**
     * 删除标签（软删）。给了 {@code replacementId} 则先合并再删。
     *
     * <h3>为什么走了 merge 分支后不能再置 DELETED</h3>
     * <p>
     * {@link #merge} 已把源标签置为 {@code MERGED}。此前这里<b>无条件</b>
     * 再执行一次 {@code SET status = 'DELETED' WHERE id = ?}，
     * 把刚写好的 {@code MERGED} 覆盖成 {@code DELETED}——
     * 两种状态语义不同（合并可追溯到目标标签，删除不可），
     * 覆盖后审计上再也看不出这个标签是被合并走的。
     * </p>
     * <p>
     * 加了 {@code AND status = 'ACTIVE'} 守卫后，merge 分支的这次更新
     * 自然命中 0 行，{@code MERGED} 得以保留；而纯删除分支正常生效。
     * </p>
     */
    public void delete(long id, Long replacementId) {
        KnowledgeTag tag = require(id);
        if (replacementId != null) {
            merge(id, replacementId);
            // merge 已把状态置为 MERGED，语义比 DELETED 更精确（可追溯到目标标签），
            // 不能再覆盖。直接返回而非依赖下面的守卫命中 0 行——
            // 意图明确的提前返回比"靠守卫兜住"更不易被后人改坏
            return;
        }
        if (tag.usageCount() > 0) {
            throw new IllegalStateException("该标签仍被文档使用，请先合并或解除关联");
        }
        int rows = jdbcTemplate.update(
                "UPDATE sys_knowledge_tag SET status = 'DELETED', update_time = CURRENT_TIMESTAMP "
                        + "WHERE id = ? AND status = 'ACTIVE'", id);
        if (rows == 0) {
            throw new IllegalStateException("标签不存在或已被删除/合并");
        }
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
