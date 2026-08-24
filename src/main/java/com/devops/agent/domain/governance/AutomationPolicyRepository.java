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
 * 自动化策略仓储（L3）。
 *
 * @author OpsBrain AI
 * @since 2026-08-25
 */
@Slf4j
@Repository
public class AutomationPolicyRepository {

    private final JdbcTemplate jdbcTemplate;

    public AutomationPolicyRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final RowMapper<AutomationPolicy> ROW_MAPPER = (rs, n) -> {
        AutomationPolicy p = new AutomationPolicy();
        p.setId(rs.getLong("id"));
        p.setName(rs.getString("name"));
        p.setDescription(rs.getString("description"));
        p.setMatchAlertLevels(rs.getString("match_alert_levels"));
        p.setMatchModule(rs.getString("match_module"));
        p.setMatchServicePattern(rs.getString("match_service_pattern"));
        p.setMatchAlertNamePattern(rs.getString("match_alert_name_pattern"));
        p.setActionKey(rs.getString("action_key"));
        p.setActionParams(rs.getString("action_params"));
        p.setEnvironment(rs.getString("environment"));
        p.setPriority(rs.getInt("priority"));
        p.setStopOnMatch(rs.getBoolean("stop_on_match"));
        p.setCooldownMinutes(rs.getInt("cooldown_minutes"));
        p.setMaxExecutionsPerDay(rs.getInt("max_executions_per_day"));
        p.setDryRun(rs.getBoolean("dry_run"));
        p.setEnabled(rs.getBoolean("enabled"));
        p.setVersion(rs.getInt("version"));
        p.setCreatedBy(rs.getString("created_by"));
        p.setUpdatedBy(rs.getString("updated_by"));
        if (rs.getTimestamp("create_time") != null) {
            p.setCreateTime(rs.getTimestamp("create_time").toLocalDateTime());
        }
        if (rs.getTimestamp("update_time") != null) {
            p.setUpdateTime(rs.getTimestamp("update_time").toLocalDateTime());
        }
        return p;
    };

    // ==================== 查询 ====================

    public Map<String, Object> query(String keyword, String actionKey, String environment,
                                     Boolean enabled, int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();

        if (keyword != null && !keyword.isBlank()) {
            where.append(" AND (name ILIKE ? OR description ILIKE ? OR action_key ILIKE ?)");
            String like = "%" + keyword.trim() + "%";
            args.add(like);
            args.add(like);
            args.add(like);
        }
        if (actionKey != null && !actionKey.isBlank()) {
            where.append(" AND action_key = ?");
            args.add(actionKey.trim());
        }
        if (environment != null && !environment.isBlank()) {
            where.append(" AND environment = ?");
            args.add(environment.trim());
        }
        if (enabled != null) {
            where.append(" AND enabled = ?");
            args.add(enabled);
        }

        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_automation_policy" + where, Long.class, args.toArray());
        long totalCount = total == null ? 0L : total;

        // 页码钳制：防「显示 1999981-20 共 20 条」这类矛盾文案（项目已修过同类缺陷）
        int safeSize = Math.max(1, Math.min(size, 200));
        int totalPages = (int) Math.max(1, (totalCount + safeSize - 1) / safeSize);
        int safePage = Math.max(1, Math.min(page, totalPages));

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(safeSize);
        pageArgs.add((long) (safePage - 1) * safeSize);

        // 排序即引擎的求值顺序：priority 升序、同值按 id。
        // 与 idx_automation_policy_eval 一致——让用户在列表里看到的顺序
        // 就是引擎实际求值的顺序，否则「为什么是这条策略生效」无法自证
        List<AutomationPolicy> items = jdbcTemplate.query("""
            SELECT * FROM sys_automation_policy
            """ + where + """
             ORDER BY priority ASC, id ASC
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

    public Optional<AutomationPolicy> findById(long id) {
        List<AutomationPolicy> rows = jdbcTemplate.query(
                "SELECT * FROM sys_automation_policy WHERE id = ?", ROW_MAPPER, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public Optional<AutomationPolicy> findByName(String name) {
        List<AutomationPolicy> rows = jdbcTemplate.query(
                "SELECT * FROM sys_automation_policy WHERE name = ?", ROW_MAPPER, name);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** 按求值顺序取全部启用策略，供匹配预演与将来的引擎使用 */
    public List<AutomationPolicy> findEnabledInEvalOrder() {
        return jdbcTemplate.query("""
            SELECT * FROM sys_automation_policy
             WHERE enabled = TRUE
             ORDER BY priority ASC, id ASC
            """, ROW_MAPPER);
    }

    /** 引用了指定动作的策略数。停用动作前用它给出影响面提示 */
    public int countByActionKey(String actionKey) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_automation_policy WHERE action_key = ?",
                Integer.class, actionKey);
        return n == null ? 0 : n;
    }

    public Map<String, Object> stats() {
        Map<String, Object> row = jdbcTemplate.queryForMap("""
            SELECT COUNT(*)                                          AS total,
                   COUNT(*) FILTER (WHERE enabled)                   AS enabled_count,
                   COUNT(*) FILTER (WHERE enabled AND dry_run)       AS dry_run_count,
                   COUNT(*) FILTER (WHERE enabled AND NOT dry_run)   AS live_count,
                   COUNT(*) FILTER (WHERE enabled AND NOT dry_run
                                      AND environment = 'prod')      AS prod_live_count
              FROM sys_automation_policy
            """);
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("total", row.get("total"));
        stats.put("enabledCount", row.get("enabled_count"));
        stats.put("dryRunCount", row.get("dry_run_count"));
        // 这两个是真正「会动手」的策略数——风险敞口，单独拎出来
        stats.put("liveCount", row.get("live_count"));
        stats.put("prodLiveCount", row.get("prod_live_count"));
        return stats;
    }

    // ==================== 写入 ====================

    public Long insert(AutomationPolicy p, String operator) {
        String sql = """
            INSERT INTO sys_automation_policy
                (name, description, match_alert_levels, match_module,
                 match_service_pattern, match_alert_name_pattern,
                 action_key, action_params, environment,
                 priority, stop_on_match, cooldown_minutes, max_executions_per_day,
                 dry_run, enabled, version, created_by, updated_by,
                 create_time, update_time)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?,
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """;
        KeyHolder kh = new GeneratedKeyHolder();
        // 显式指定返回列：PG 的 RETURN_GENERATED_KEYS 会返回全部列，
        // KeyHolder.getKey() 遇多列抛「multiple keys」（项目已踩过）
        jdbcTemplate.update(conn -> {
            PreparedStatement ps = conn.prepareStatement(sql, new String[]{"id"});
            ps.setString(1, p.getName());
            ps.setString(2, p.getDescription());
            ps.setString(3, p.getMatchAlertLevels());
            ps.setString(4, p.getMatchModule());
            ps.setString(5, p.getMatchServicePattern());
            ps.setString(6, p.getMatchAlertNamePattern());
            ps.setString(7, p.getActionKey());
            ps.setString(8, p.getActionParams());
            ps.setString(9, p.getEnvironment());
            ps.setInt(10, p.getPriority());
            ps.setBoolean(11, p.isStopOnMatch());
            ps.setInt(12, p.getCooldownMinutes());
            ps.setInt(13, p.getMaxExecutionsPerDay());
            ps.setBoolean(14, p.isDryRun());
            ps.setBoolean(15, p.isEnabled());
            ps.setString(16, operator);
            ps.setString(17, operator);
            return ps;
        }, kh);

        Number key = kh.getKey();
        log.warn("🔐 [AutomationPolicy] 新增策略 | name={} | action={} | dryRun={} | enabled={} | operator={}",
                p.getName(), p.getActionKey(), p.isDryRun(), p.isEnabled(), operator);
        return key == null ? null : key.longValue();
    }

    public void update(AutomationPolicy p, int expectedVersion, String operator) {
        int affected = jdbcTemplate.update("""
            UPDATE sys_automation_policy
               SET name                     = ?,
                   description              = ?,
                   match_alert_levels       = ?,
                   match_module             = ?,
                   match_service_pattern    = ?,
                   match_alert_name_pattern = ?,
                   action_key               = ?,
                   action_params            = ?::jsonb,
                   environment              = ?,
                   priority                 = ?,
                   stop_on_match            = ?,
                   cooldown_minutes         = ?,
                   max_executions_per_day   = ?,
                   dry_run                  = ?,
                   enabled                  = ?,
                   version                  = version + 1,
                   updated_by               = ?,
                   update_time              = CURRENT_TIMESTAMP
             WHERE id = ? AND version = ?
            """,
                p.getName(), p.getDescription(), p.getMatchAlertLevels(), p.getMatchModule(),
                p.getMatchServicePattern(), p.getMatchAlertNamePattern(),
                p.getActionKey(), p.getActionParams(), p.getEnvironment(),
                p.getPriority(), p.isStopOnMatch(), p.getCooldownMinutes(),
                p.getMaxExecutionsPerDay(), p.isDryRun(), p.isEnabled(),
                operator, p.getId(), expectedVersion);

        if (affected == 0) {
            Integer actual = currentVersion(p.getId());
            log.warn("⚠️ [AutomationPolicy] CAS 更新失败 | id={} | expected={} | actual={}",
                    p.getId(), expectedVersion, actual);
            throw new OptimisticLockException(
                    p.getName() == null ? String.valueOf(p.getId()) : p.getName(),
                    expectedVersion, actual);
        }
        log.warn("🔐 [AutomationPolicy] 策略已更新 | name={} | action={} | dryRun={} | enabled={} | operator={}",
                p.getName(), p.getActionKey(), p.isDryRun(), p.isEnabled(), operator);
    }

    /**
     * 切换启用状态（轻量路径，同样走 CAS）。
     *
     * <p>与白名单同理：列表页的开关只想改一个布尔值，走全量更新
     * 需要前端先回填全部字段，任何一项读漏都会被静默重置。</p>
     */
    public void toggleEnabled(long id, boolean enabled, int expectedVersion, String operator) {
        int affected = jdbcTemplate.update("""
            UPDATE sys_automation_policy
               SET enabled = ?, version = version + 1,
                   updated_by = ?, update_time = CURRENT_TIMESTAMP
             WHERE id = ? AND version = ?
            """, enabled, operator, id, expectedVersion);

        if (affected == 0) {
            throw new OptimisticLockException(String.valueOf(id), expectedVersion, currentVersion(id));
        }
        log.warn("🔐 [AutomationPolicy] 启用状态变更 | id={} | enabled={} | operator={}",
                id, enabled, operator);
    }

    /**
     * 切换演练模式。
     *
     * <p>单独成端点是刻意的：<b>关掉 dry_run 是本模块风险最高的单个操作</b>——
     * 策略从「只记录」变成「真动手」。把它和其他字段混在一次更新里，
     * 会让这个变更淹没在 diff 中，审计日志也无法区分
     * 「改了个描述」和「让策略开始真实执行」。</p>
     */
    public void toggleDryRun(long id, boolean dryRun, int expectedVersion, String operator) {
        int affected = jdbcTemplate.update("""
            UPDATE sys_automation_policy
               SET dry_run = ?, version = version + 1,
                   updated_by = ?, update_time = CURRENT_TIMESTAMP
             WHERE id = ? AND version = ?
            """, dryRun, operator, id, expectedVersion);

        if (affected == 0) {
            throw new OptimisticLockException(String.valueOf(id), expectedVersion, currentVersion(id));
        }
        // 关掉演练用 error 级别：这是「策略开始真实操作生产系统」的时刻，
        // 事故复盘时必须能一眼定位
        if (dryRun) {
            log.warn("🔐 [AutomationPolicy] 已切回演练模式 | id={} | operator={}", id, operator);
        } else {
            log.error("🚨 [AutomationPolicy] 演练模式已关闭，策略将真实执行 | id={} | operator={}",
                    id, operator);
        }
    }

    public void delete(long id, int expectedVersion) {
        int affected = jdbcTemplate.update(
                "DELETE FROM sys_automation_policy WHERE id = ? AND version = ?",
                id, expectedVersion);
        if (affected == 0) {
            throw new OptimisticLockException(String.valueOf(id), expectedVersion, currentVersion(id));
        }
        log.warn("🔐 [AutomationPolicy] 策略已删除 | id={}", id);
    }

    private Integer currentVersion(Long id) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT version FROM sys_automation_policy WHERE id = ?", Integer.class, id);
        } catch (Exception e) {
            return null;
        }
    }
}
