package com.devops.agent.domain.governance;

import com.devops.agent.common.exception.OptimisticLockException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 动作白名单仓储（L3）。
 *
 * @author OpsBrain AI
 * @since 2026-08-25
 */
@Slf4j
@Repository
public class ActionAllowlistRepository {

    private final JdbcTemplate jdbcTemplate;

    public ActionAllowlistRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final RowMapper<ActionAllowlistEntry> ROW_MAPPER = (rs, n) -> {
        ActionAllowlistEntry e = new ActionAllowlistEntry();
        e.setId(rs.getLong("id"));
        e.setActionKey(rs.getString("action_key"));
        e.setDisplayName(rs.getString("display_name"));
        e.setDescription(rs.getString("description"));
        e.setCategory(rs.getString("category"));
        e.setRiskLevel(rs.getString("risk_level"));
        e.setTargetPattern(rs.getString("target_pattern"));
        e.setEnvironments(rs.getString("environments"));
        e.setParamSchema(rs.getString("param_schema"));

        // getBoolean 对 SQL NULL 返回 false —— 直接用会把「跟随策略」
        // 误读成「明确不需要审批」，正好是最危险的方向。必须 wasNull 判定
        boolean requires = rs.getBoolean("requires_approval");
        e.setRequiresApproval(rs.wasNull() ? null : requires);

        int blast = rs.getInt("max_blast_radius_count");
        e.setMaxBlastRadiusCount(rs.wasNull() ? null : blast);

        e.setEnabled(rs.getBoolean("enabled"));
        e.setVersion(rs.getInt("version"));
        e.setCreatedBy(rs.getString("created_by"));
        e.setUpdatedBy(rs.getString("updated_by"));
        if (rs.getTimestamp("create_time") != null) {
            e.setCreateTime(rs.getTimestamp("create_time").toLocalDateTime());
        }
        if (rs.getTimestamp("update_time") != null) {
            e.setUpdateTime(rs.getTimestamp("update_time").toLocalDateTime());
        }
        return e;
    };

    // ==================== 查询 ====================

    /**
     * 分页查询。
     *
     * @param keyword    对 action_key / display_name / description 模糊匹配
     * @param category   类别精确匹配
     * @param riskLevel  风险等级精确匹配
     * @param enabled    启用状态，null = 不限
     * @return {@code {items, total, page, size, totalPages}}
     */
    public Map<String, Object> query(String keyword, String category, String riskLevel,
                                     Boolean enabled, int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();

        if (keyword != null && !keyword.isBlank()) {
            // 三列一起匹配：用户记不住 action_key 时会按中文名或描述找
            where.append(" AND (action_key ILIKE ? OR display_name ILIKE ? OR description ILIKE ?)");
            String like = "%" + keyword.trim() + "%";
            args.add(like);
            args.add(like);
            args.add(like);
        }
        if (category != null && !category.isBlank()) {
            where.append(" AND category = ?");
            args.add(category.trim());
        }
        if (riskLevel != null && !riskLevel.isBlank()) {
            where.append(" AND risk_level = ?");
            args.add(riskLevel.trim());
        }
        if (enabled != null) {
            where.append(" AND enabled = ?");
            args.add(enabled);
        }

        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_action_allowlist" + where, Long.class, args.toArray());
        long totalCount = total == null ? 0L : total;

        // 页码钳制：前端传 page=99999 时若直接算 offset，会返回空列表 +
        // 「显示 1999981-20 共 20 条」这类自相矛盾的分页信息（本项目已修过一次同类缺陷）
        int safeSize = Math.max(1, Math.min(size, 200));
        int totalPages = (int) Math.max(1, (totalCount + safeSize - 1) / safeSize);
        int safePage = Math.max(1, Math.min(page, totalPages));

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(safeSize);
        pageArgs.add((long) (safePage - 1) * safeSize);

        // 排序：启用的排前面（用户最关心「现在开着哪些」），
        // 再按风险从高到低（危险的先看见），最后按类别与 key 稳定收敛
        List<ActionAllowlistEntry> items = jdbcTemplate.query("""
            SELECT * FROM sys_action_allowlist
            """ + where + """
             ORDER BY enabled DESC,
                      CASE risk_level
                        WHEN 'HIGH_RISK_EXECUTION' THEN 1
                        WHEN 'CONTROLLED_WRITE'    THEN 2
                        WHEN 'DRAFT'               THEN 3
                        WHEN 'READ_ONLY'           THEN 4
                        ELSE 99
                      END,
                      category, action_key
             LIMIT ? OFFSET ?
            """, ROW_MAPPER, pageArgs.toArray());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("total", totalCount);
        result.put("page", safePage);
        result.put("size", safeSize);
        result.put("totalPages", totalPages);
        return result;
    }

    public Optional<ActionAllowlistEntry> findById(long id) {
        List<ActionAllowlistEntry> rows = jdbcTemplate.query(
                "SELECT * FROM sys_action_allowlist WHERE id = ?", ROW_MAPPER, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public Optional<ActionAllowlistEntry> findByActionKey(String actionKey) {
        List<ActionAllowlistEntry> rows = jdbcTemplate.query(
                "SELECT * FROM sys_action_allowlist WHERE action_key = ?", ROW_MAPPER, actionKey);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** 类别聚合，供筛选下拉。从实际数据聚合而非硬编码，避免列出库里没有的选项 */
    public Map<String, Object> filterOptions() {
        List<String> categories = jdbcTemplate.queryForList(
                "SELECT DISTINCT category FROM sys_action_allowlist ORDER BY category",
                String.class);
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("categories", categories);
        return options;
    }

    /** 启用/停用条目的计数，供页面顶部统计条 */
    public Map<String, Object> stats() {
        Map<String, Object> row = jdbcTemplate.queryForMap("""
            SELECT COUNT(*)                                        AS total,
                   COUNT(*) FILTER (WHERE enabled)                 AS enabled_count,
                   COUNT(*) FILTER (WHERE risk_level = 'HIGH_RISK_EXECUTION' AND enabled)
                                                                   AS high_risk_enabled,
                   COUNT(*) FILTER (WHERE enabled AND environments ILIKE '%prod%')
                                                                   AS prod_enabled
              FROM sys_action_allowlist
            """);
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("total", row.get("total"));
        stats.put("enabledCount", row.get("enabled_count"));
        // 这两个是「该警惕」的数字，单独拎出来让管理员一眼看到风险敞口
        stats.put("highRiskEnabled", row.get("high_risk_enabled"));
        stats.put("prodEnabled", row.get("prod_enabled"));
        return stats;
    }

    // ==================== 写入 ====================

    public Long insert(ActionAllowlistEntry e, String operator) {
        String sql = """
            INSERT INTO sys_action_allowlist
                (action_key, display_name, description, category, risk_level,
                 target_pattern, environments, param_schema, requires_approval,
                 max_blast_radius_count, enabled, version, created_by, updated_by,
                 create_time, update_time)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, 0, ?, ?,
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """;
        KeyHolder kh = new GeneratedKeyHolder();
        // 显式指定返回列：PostgreSQL 的 RETURN_GENERATED_KEYS 会返回全部列，
        // KeyHolder.getKey() 遇多列抛「multiple keys」（项目已踩过）
        jdbcTemplate.update(conn -> {
            PreparedStatement ps = conn.prepareStatement(sql, new String[]{"id"});
            ps.setString(1, e.getActionKey());
            ps.setString(2, e.getDisplayName());
            ps.setString(3, e.getDescription());
            ps.setString(4, e.getCategory());
            ps.setString(5, e.getRiskLevel());
            ps.setString(6, e.getTargetPattern());
            ps.setString(7, e.getEnvironments());
            ps.setString(8, e.getParamSchema());
            if (e.getRequiresApproval() == null) {
                ps.setNull(9, java.sql.Types.BOOLEAN);
            } else {
                ps.setBoolean(9, e.getRequiresApproval());
            }
            if (e.getMaxBlastRadiusCount() == null) {
                ps.setNull(10, java.sql.Types.INTEGER);
            } else {
                ps.setInt(10, e.getMaxBlastRadiusCount());
            }
            ps.setBoolean(11, e.isEnabled());
            ps.setString(12, operator);
            ps.setString(13, operator);
            return ps;
        }, kh);

        Number key = kh.getKey();
        Long id = key == null ? null : key.longValue();
        log.warn("🔐 [ActionAllowlist] 新增动作 | key={} | risk={} | enabled={} | operator={}",
                e.getActionKey(), e.getRiskLevel(), e.isEnabled(), operator);
        return id;
    }

    /**
     * 按版本号 CAS 更新。
     *
     * <p>{@code action_key} 不在更新列内——它是审计记录的关联键，
     * 改掉会让历史执行记录指向一个不再存在的动作。要换标识只能停用旧的、新建一条。</p>
     */
    public void update(ActionAllowlistEntry e, int expectedVersion, String operator) {
        int affected = jdbcTemplate.update("""
            UPDATE sys_action_allowlist
               SET display_name           = ?,
                   description            = ?,
                   category               = ?,
                   risk_level             = ?,
                   target_pattern         = ?,
                   environments           = ?,
                   param_schema           = ?::jsonb,
                   requires_approval      = ?,
                   max_blast_radius_count = ?,
                   enabled                = ?,
                   version                = version + 1,
                   updated_by             = ?,
                   update_time            = CURRENT_TIMESTAMP
             WHERE id = ? AND version = ?
            """,
                e.getDisplayName(), e.getDescription(), e.getCategory(), e.getRiskLevel(),
                e.getTargetPattern(), e.getEnvironments(), e.getParamSchema(),
                e.getRequiresApproval(), e.getMaxBlastRadiusCount(), e.isEnabled(),
                operator, e.getId(), expectedVersion);

        if (affected == 0) {
            Integer actual = currentVersion(e.getId());
            log.warn("⚠️ [ActionAllowlist] CAS 更新失败 | id={} | expected={} | actual={}",
                    e.getId(), expectedVersion, actual);
            throw new OptimisticLockException(
                    e.getActionKey() == null ? String.valueOf(e.getId()) : e.getActionKey(),
                    expectedVersion, actual);
        }
        log.warn("🔐 [ActionAllowlist] 动作已更新 | key={} | risk={} | enabled={} | operator={}",
                e.getActionKey(), e.getRiskLevel(), e.isEnabled(), operator);
    }

    /**
     * 切换启用状态（轻量路径，同样走 CAS）。
     *
     * <p>单独提供而不复用 {@link #update}：列表页的开关只想改一个布尔值，
     * 走全量更新需要先查再回填，中间任何一个字段读漏都会被静默重置。
     * 「一次操作只改它声称要改的东西」在安全配置里尤其重要。</p>
     */
    public void toggleEnabled(long id, boolean enabled, int expectedVersion, String operator) {
        int affected = jdbcTemplate.update("""
            UPDATE sys_action_allowlist
               SET enabled = ?, version = version + 1,
                   updated_by = ?, update_time = CURRENT_TIMESTAMP
             WHERE id = ? AND version = ?
            """, enabled, operator, id, expectedVersion);

        if (affected == 0) {
            Integer actual = currentVersion(id);
            throw new OptimisticLockException(String.valueOf(id), expectedVersion, actual);
        }
        log.warn("🔐 [ActionAllowlist] 启用状态变更 | id={} | enabled={} | operator={}",
                id, enabled, operator);
    }

    private Integer currentVersion(Long id) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT version FROM sys_action_allowlist WHERE id = ?", Integer.class, id);
        } catch (Exception ex) {
            return null;
        }
    }
}
