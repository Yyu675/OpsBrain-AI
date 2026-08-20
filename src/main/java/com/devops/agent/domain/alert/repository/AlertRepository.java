package com.devops.agent.domain.alert.repository;

import com.devops.agent.domain.alert.entity.Alert;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 告警仓储（sys_alert 表）
 * <p>
 * 提供告警 CRUD、去重查询、状态变更、工单回填等操作。
 * 遵循项目 JdbcTemplate + RowMapper 模式，PG 主键使用 KeyHolder 显式指定列名。
 * </p>
 *
 * @author OpsBrain AI
 * @since 2026-08-14
 */
@Slf4j
@Repository
public class AlertRepository {

    private final JdbcTemplate jdbcTemplate;

    public AlertRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // ==================== RowMapper ====================

    private static final RowMapper<Alert> ALERT_ROW_MAPPER = (rs, rowNum) -> {
        Alert alert = new Alert();
        alert.setId(rs.getLong("id"));
        alert.setSource(rs.getString("source"));
        alert.setAlertName(rs.getString("alert_name"));
        alert.setLevel(rs.getString("level"));
        alert.setTitle(rs.getString("title"));
        alert.setDescription(rs.getString("description"));
        alert.setStatus(rs.getString("status"));
        alert.setDedupKey(rs.getString("dedup_key"));
        alert.setService(rs.getString("service"));
        alert.setModule(rs.getString("module"));
        alert.setOccurrenceCount(rs.getInt("occurrence_count"));
        alert.setFirstOccurredAt(rs.getObject("first_occurred_at", LocalDateTime.class));
        alert.setLastOccurredAt(rs.getObject("last_occurred_at", LocalDateTime.class));
        alert.setAcknowledgedAt(rs.getObject("acknowledged_at", LocalDateTime.class));
        alert.setResolvedAt(rs.getObject("resolved_at", LocalDateTime.class));
        alert.setTicketId(rs.getString("ticket_id"));
        alert.setCreateTime(rs.getObject("create_time", LocalDateTime.class));
        alert.setUpdateTime(rs.getObject("update_time", LocalDateTime.class));
        return alert;
    };

    // ==================== 查询 ====================

    /**
     * 按去重键查找活跃告警（FIRING / ACKNOWLEDGED）
     */
    public Optional<Alert> findActiveByDedupKey(String dedupKey) {
        String sql = "SELECT * FROM sys_alert WHERE dedup_key = ? AND status IN ('FIRING', 'ACKNOWLEDGED') LIMIT 1";
        List<Alert> results = jdbcTemplate.query(sql, ALERT_ROW_MAPPER, dedupKey);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /**
     * 查找时间窗口内同 service+module 且已建单的活跃告警（方向 E：告警聚合降噪）
     * <p>
     * 用于告警风暴抑制：同一服务+模块在短时间内产生的多条<b>不同 dedup_key</b> 告警
     * （如一个节点挂了导致其上多个 Pod 各报不同告警），只应建一张工单。
     * 窗口内已有已建单（ticket_id 非空）的活跃告警时，新告警关联其 ticket_id 而不新建单。
     * </p>
     * <p>
     * 与 {@link #findActiveByDedupKey} 互补：后者处理「完全同键」重复（occurrence 递增），
     * 本方法处理「同服务不同键」的风暴聚合。取最近一条作为组代表。
     * </p>
     *
     * @param service       服务名（为空则不聚合——无法判定归属）
     * @param module        模块
     * @param windowMinutes 聚合时间窗口（分钟）
     * @return 组代表告警（含可关联的 ticket_id），无则 empty
     */
    public Optional<Alert> findActiveGroupTicket(String service, String module, int windowMinutes) {
        if (service == null || service.isBlank()) {
            return Optional.empty();   // 无 service 无法判定聚合归属，不抑制
        }
        String sql = """
            SELECT * FROM sys_alert
             WHERE service = ? AND module = ?
               AND status IN ('FIRING', 'ACKNOWLEDGED')
               AND ticket_id IS NOT NULL
               AND last_occurred_at >= CURRENT_TIMESTAMP - CAST(? AS INTEGER) * INTERVAL '1 minute'
             ORDER BY last_occurred_at DESC
             LIMIT 1
            """;
        List<Alert> results = jdbcTemplate.query(sql, ALERT_ROW_MAPPER, service, module, windowMinutes);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /**
     * 按 ID 查询
     */
    public Optional<Alert> findById(Long id) {
        String sql = "SELECT * FROM sys_alert WHERE id = ?";
        List<Alert> results = jdbcTemplate.query(sql, ALERT_ROW_MAPPER, id);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /**
     * 按状态分页查询
     */
    public List<Alert> findByStatus(String status, int page, int size) {
        int offset = (page - 1) * size;
        String sql = "SELECT * FROM sys_alert WHERE status = ? ORDER BY last_occurred_at DESC LIMIT ? OFFSET ?";
        return jdbcTemplate.query(sql, ALERT_ROW_MAPPER, status, size, offset);
    }

    /**
     * 按状态 + 级别组合条件分页查询（供告警列表页）
     * <p>
     * 筛选分页下沉到 SQL（同 6.15 工单契约）：前端本地过滤只能作用于当前页，
     * 会静默隐藏页外数据。WHERE 与 count 查询共用 {@link #buildWhere}，
     * 保证 {@code total} 与实际行数一致。
     * </p>
     */
    public List<Alert> findPage(String status, String level, int page, int size) {
        int offset = (page - 1) * size;
        WhereClause where = buildWhere(status, level);
        String sql = "SELECT * FROM sys_alert " + where.sql()
                + " ORDER BY last_occurred_at DESC LIMIT ? OFFSET ?";
        List<Object> params = new java.util.ArrayList<>(where.params());
        params.add(size);
        params.add(offset);
        return jdbcTemplate.query(sql, ALERT_ROW_MAPPER, params.toArray());
    }

    /**
     * 按状态 + 级别组合条件计数（与 {@link #findPage} 共用 WHERE）
     */
    public int countByQuery(String status, String level) {
        WhereClause where = buildWhere(status, level);
        String sql = "SELECT COUNT(*) FROM sys_alert " + where.sql();
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, where.params().toArray());
        return count != null ? count : 0;
    }

    /**
     * 构建动态 WHERE（status / level 两个可选条件）
     * <p>字段值用参数化占位符，禁止拼接用户输入（SQL 注入防护）。</p>
     */
    private WhereClause buildWhere(String status, String level) {
        StringBuilder sql = new StringBuilder("WHERE 1=1");
        List<Object> params = new java.util.ArrayList<>();
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = ?");
            params.add(status.trim().toUpperCase());
        }
        if (level != null && !level.isBlank()) {
            sql.append(" AND level = ?");
            params.add(level.trim().toUpperCase());
        }
        return new WhereClause(sql.toString(), params);
    }

    /** WHERE 子句与其参数 */
    private record WhereClause(String sql, List<Object> params) {
    }

    /**
     * 查询所有活跃告警（FIRING / ACKNOWLEDGED）
     */
    public List<Alert> findAllActive() {
        String sql = "SELECT * FROM sys_alert WHERE status IN ('FIRING', 'ACKNOWLEDGED') ORDER BY last_occurred_at DESC";
        return jdbcTemplate.query(sql, ALERT_ROW_MAPPER);
    }

    /**
     * 按条件计数
     */
    public int countByStatus(String status) {
        String sql = "SELECT COUNT(*) FROM sys_alert WHERE status = ?";
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, status);
        return count != null ? count : 0;
    }

    /**
     * 统计活跃告警总数
     */
    public int countActive() {
        String sql = "SELECT COUNT(*) FROM sys_alert WHERE status IN ('FIRING', 'ACKNOWLEDGED')";
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class);
        return count != null ? count : 0;
    }

    // ==================== 写入 ====================

    /**
     * 创建告警（FIRING 状态）
     * <p>
     * 使用 KeyHolder 显式指定 id 列，避免 PG 多列返回导致 getKey 异常。
     * </p>
     */
    public Alert save(Alert alert) {
        String sql = "INSERT INTO sys_alert (source, alert_name, level, title, description, status, " +
                "dedup_key, service, module, occurrence_count, first_occurred_at, last_occurred_at, " +
                "acknowledged_at, resolved_at, ticket_id, create_time, update_time) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

        LocalDateTime now = LocalDateTime.now();
        if (alert.getCreateTime() == null) alert.setCreateTime(now);
        if (alert.getUpdateTime() == null) alert.setUpdateTime(now);
        if (alert.getStatus() == null) alert.setStatus("FIRING");
        if (alert.getOccurrenceCount() == null) alert.setOccurrenceCount(1);

        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, new String[]{"id"});
            ps.setString(1, alert.getSource());
            ps.setString(2, alert.getAlertName());
            ps.setString(3, alert.getLevel());
            ps.setString(4, alert.getTitle());
            ps.setString(5, alert.getDescription());
            ps.setString(6, alert.getStatus());
            ps.setString(7, alert.getDedupKey());
            ps.setString(8, alert.getService());
            ps.setString(9, alert.getModule());
            ps.setInt(10, alert.getOccurrenceCount());
            ps.setObject(11, alert.getFirstOccurredAt());
            ps.setObject(12, alert.getLastOccurredAt());
            ps.setObject(13, alert.getAcknowledgedAt());
            ps.setObject(14, alert.getResolvedAt());
            ps.setString(15, alert.getTicketId());
            ps.setObject(16, alert.getCreateTime());
            ps.setObject(17, alert.getUpdateTime());
            return ps;
        }, keyHolder);

        alert.setId(Objects.requireNonNull(keyHolder.getKey(), "save alert 主键获取失败").longValue());
        log.info("✅ 告警创建 | id={} alertName={} level={} dedupKey={}", alert.getId(), alert.getAlertName(), alert.getLevel(), alert.getDedupKey());
        return alert;
    }

    /**
     * 更新告警状态（幂等）
     */
    public void updateStatus(Long id, String status) {
        String sql = "UPDATE sys_alert SET status = ?, update_time = ? WHERE id = ?";
        int rows = jdbcTemplate.update(sql, status, LocalDateTime.now(), id);
        if (rows > 0) {
            log.info("✅ 告警状态变更 | id={} status={}", id, status);
        } else {
            log.warn("⚠️ 告警状态变更无影响 | id={} status={}（可能已不存在）", id, status);
        }
    }

    /**
     * 递增重复次数 + 更新最后触发时间
     */
    public void incrementOccurrence(Long id) {
        String sql = "UPDATE sys_alert SET occurrence_count = occurrence_count + 1, " +
                "last_occurred_at = ?, update_time = ? WHERE id = ?";
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update(sql, now, now, id);
    }

    /**
     * 回填关联工单号
     */
    public void updateTicketId(Long id, String ticketId) {
        String sql = "UPDATE sys_alert SET ticket_id = ?, update_time = ? WHERE id = ?";
        jdbcTemplate.update(sql, ticketId, LocalDateTime.now(), id);
        log.info("✅ 告警工单回填 | id={} ticketId={}", id, ticketId);
    }

    /**
     * 标记人工确认
     * <p>条件带 {@code status <> 'RESOLVED'}：已恢复告警不可再确认。</p>
     *
     * @return 受影响行数（0 表示告警不存在或已恢复）
     */
    public int acknowledge(Long id) {
        String sql = "UPDATE sys_alert SET status = 'ACKNOWLEDGED', acknowledged_at = ?, update_time = ? WHERE id = ? AND status <> 'RESOLVED'";
        LocalDateTime now = LocalDateTime.now();
        int rows = jdbcTemplate.update(sql, now, now, id);
        if (rows > 0) {
            log.info("✅ 告警人工确认 | id={}", id);
        } else {
            log.warn("⚠️ 告警确认无影响 | id={}（可能已不存在或已恢复）", id);
        }
        return rows;
    }

    /**
     * 标记已恢复
     *
     * @return 受影响行数（0 表示告警不存在或已恢复）
     */
    public int resolve(Long id) {
        String sql = "UPDATE sys_alert SET status = 'RESOLVED', resolved_at = ?, update_time = ? WHERE id = ? AND status <> 'RESOLVED'";
        LocalDateTime now = LocalDateTime.now();
        int rows = jdbcTemplate.update(sql, now, now, id);
        if (rows > 0) {
            log.info("✅ 告警已恢复 | id={}", id);
        } else {
            log.warn("⚠️ 告警恢复无影响 | id={}（可能已不存在或已恢复）", id);
        }
        return rows;
    }

    /**
     * 物理删除（仅用于清理测试数据）
     */
    public void deleteById(Long id) {
        jdbcTemplate.update("DELETE FROM sys_alert WHERE id = ?", id);
        log.info("🗑️ 告警删除 | id={}", id);
    }
}