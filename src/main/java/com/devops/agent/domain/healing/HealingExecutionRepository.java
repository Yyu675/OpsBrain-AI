package com.devops.agent.domain.healing;

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
 * {@code sys_healing_execution} 仓储（S3-1 批次 2）。
 * <p>
 * 与 ApprovalRequestRepository 同族：JdbcTemplate + GeneratedKeyHolder。
 * 写入分两步——执行当下 insert（终态直行一次落行），审批通过后的
 * 执行用 {@link #markFinished} 回填终态字段。
 * </p>
 */
@Repository
public class HealingExecutionRepository {

    private static final RowMapper<HealingExecution> ROW_MAPPER = (rs, n) -> new HealingExecution(
            rs.getLong("id"),
            rs.getString("action_key"),
            rs.getString("environment"),
            rs.getString("target"),
            rs.getString("params_json"),
            rs.getObject("alert_id") == null ? null : rs.getLong("alert_id"),
            rs.getString("requested_by"),
            rs.getString("gate_decision"),
            rs.getObject("approval_id") == null ? null : rs.getLong("approval_id"),
            rs.getString("executor_key"),
            rs.getString("status"),
            rs.getString("dry_run_plan"),
            rs.getString("output"),
            rs.getString("error"),
            rs.getString("pre_snapshot_json"),
            rs.getString("undo_token"),
            rs.getObject("created_at", LocalDateTime.class),
            rs.getObject("finished_at", LocalDateTime.class));

    private final JdbcTemplate jdbcTemplate;

    public HealingExecutionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 插入一行（draft 形态），返回库分配的 id。 */
    public long insert(HealingExecution e) {
        String sql = """
                INSERT INTO sys_healing_execution
                    (action_key, environment, target, params_json, alert_id, requested_by,
                     gate_decision, approval_id, executor_key, status,
                     dry_run_plan, output, error, pre_snapshot_json, undo_token, finished_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """;
        KeyHolder kh = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            // 显式列名取键（getKey() 遇多列会抛 multiple keys，Approval 仓已踩过）
            PreparedStatement ps = con.prepareStatement(sql, new String[]{"id"});
            ps.setString(1, e.actionKey());
            ps.setString(2, e.environment());
            ps.setString(3, e.target());
            ps.setString(4, e.paramsJson());
            if (e.alertId() == null) { ps.setObject(5, null); } else { ps.setLong(5, e.alertId()); }
            ps.setString(6, e.requestedBy());
            ps.setString(7, e.gateDecision());
            if (e.approvalId() == null) { ps.setObject(8, null); } else { ps.setLong(8, e.approvalId()); }
            ps.setString(9, e.executorKey());
            ps.setString(10, e.status());
            ps.setString(11, e.dryRunPlan());
            ps.setString(12, e.output());
            ps.setString(13, e.error());
            ps.setString(14, e.preSnapshotJson());
            ps.setString(15, e.undoToken());
            ps.setObject(16, e.finishedAt());
            return ps;
        }, kh);
        return Objects.requireNonNull(kh.getKey()).longValue();
    }

    /** 审批通过后的执行回填：终态 + 输出 + 快照 + 撤销凭据 + 完成时间。 */
    public void markFinished(long id, String status, String output, String error,
                             String preSnapshotJson, String undoToken) {
        jdbcTemplate.update("""
                UPDATE sys_healing_execution
                   SET status = ?, output = ?, error = ?,
                       pre_snapshot_json = ?, undo_token = ?, finished_at = ?
                 WHERE id = ?
                """, status, output, error, preSnapshotJson, undoToken, LocalDateTime.now(), id);
    }

    /** 审批单创建后回填审批外键（行先入库、单后补链的两步顺序的第二步）。 */
    public void attachApproval(long id, long approvalId) {
        jdbcTemplate.update("UPDATE sys_healing_execution SET approval_id = ? WHERE id = ?",
                approvalId, id);
    }

    public Optional<HealingExecution> findById(long id) {
        List<HealingExecution> rows = jdbcTemplate.query(
                "SELECT * FROM sys_healing_execution WHERE id = ?", ROW_MAPPER, id);
        return rows.stream().findFirst();
    }

    /** 最近的执行台账（管理页/审计查询用）。 */
    public List<HealingExecution> listRecent(int limit) {
        return jdbcTemplate.query(
                "SELECT * FROM sys_healing_execution ORDER BY id DESC LIMIT ?",
                ROW_MAPPER, Math.max(1, Math.min(limit, 200)));
    }

    /** 某告警触发的全部执行（告警详情页联查）。 */
    public List<HealingExecution> listByAlert(long alertId) {
        return jdbcTemplate.query(
                "SELECT * FROM sys_healing_execution WHERE alert_id = ? ORDER BY id DESC",
                ROW_MAPPER, alertId);
    }
}
