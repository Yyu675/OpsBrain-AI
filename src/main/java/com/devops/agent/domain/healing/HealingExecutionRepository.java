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
            rs.getObject("finished_at", LocalDateTime.class),
            rs.getString("verify_status"),
            rs.getString("verify_result_json"),
            rs.getObject("verified_at", LocalDateTime.class));

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

    /** 撤销收场：状态（UNDONE / UNDO_FAILED）+ 撤销输出 + 完成时间。 */
    public void markUndoOutcome(long id, String status, String output, String error) {
        jdbcTemplate.update("""
                UPDATE sys_healing_execution
                   SET status = ?, output = ?, error = ?, finished_at = ?
                 WHERE id = ?
                """, status, output, error, LocalDateTime.now(), id);
    }

    /** S3-5：写入步骤时间线（编排器在终态/阶段节点后覆盖式写入全量序列）。 */
    public void updateStepsJson(long id, String stepsJson) {
        jdbcTemplate.update("UPDATE sys_healing_execution SET steps_json = ? WHERE id = ?",
                stepsJson, id);
    }

    /** S3-5：读出既有步骤序列（追加节点的读-改-写第一步；无记录/空列返回 null）。 */
    public String readStepsJson(long id) {
        List<String> rows = jdbcTemplate.query(
                "SELECT steps_json FROM sys_healing_execution WHERE id = ?",
                (rs, n) -> rs.getString(1), id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * S4-1：策略引擎冷却判据——同（动作×环境×目标）最近一次真正执行的创建时间。
     * {@code REJECTED} 行（演练/拒批/幂等拦截）不计：被拒的尝试不该挡住下一次认真尝试。
     */
    public Optional<LocalDateTime> lastExecutionAt(String actionKey, String environment, String target) {
        List<LocalDateTime> rows = jdbcTemplate.query(
                """
                SELECT created_at FROM sys_healing_execution
                 WHERE action_key = ? AND environment = ? AND target = ? AND status <> 'REJECTED'
                 ORDER BY id DESC
                 LIMIT 1
                """,
                (rs, n) -> rs.getTimestamp(1) == null ? null : rs.getTimestamp(1).toLocalDateTime(),
                actionKey, environment, target);
        return rows.isEmpty() ? Optional.empty() : Optional.ofNullable(rows.get(0));
    }

    /**
     * S4-1：策略引擎日上限判据——同（动作×环境）自 since 起的真正执行条数。
     * 同样排除 {@code REJECTED} 行（口径与 {@link #lastExecutionAt} 对齐）。
     */
    public int countSince(String actionKey, String environment, LocalDateTime since) {
        Integer n = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM sys_healing_execution
                 WHERE action_key = ? AND environment = ?
                   AND status <> 'REJECTED' AND created_at >= ?
                """,
                Integer.class, actionKey, environment, since);
        return n == null ? 0 : n;
    }

    /** 按审批单 id 反查执行台账（审批中心批准回调的桥）。 */
    public Optional<HealingExecution> findByApprovalId(long approvalId) {
        List<HealingExecution> rows = jdbcTemplate.query(
                "SELECT * FROM sys_healing_execution WHERE approval_id = ? ORDER BY id DESC",
                ROW_MAPPER, approvalId);
        return rows.stream().findFirst();
    }

    /**
     * 验证结论回填（V9）：verify_status + verify_result_json + verified_at。
     * PASS/FAIL/UNKNOWN/SKIPPED 四态尽收（SKIPPED 也要写——防止扫描器反复捞起）。
     */
    public void markVerified(long id, String verifyStatus, String verifyResultJson) {
        jdbcTemplate.update("""
                UPDATE sys_healing_execution
                   SET verify_status = ?, verify_result_json = ?, verified_at = ?
                 WHERE id = ?
                """, verifyStatus, verifyResultJson, LocalDateTime.now(), id);
    }

    /**
     * 待验证扫描（定时心跳的取数口）：SUCCEEDED 且未验证且已过沉淀期且在观察窗内。
     *
     * @param settledBefore finished_at 必须早于此时刻（刚执行完的指标还没稳定，不准验）
     * @param observedAfter finished_at 必须晚于此时刻（超出观察窗的不再验——
     *                      陈旧执行回头验出来的指标没有因果力）
     */
    public List<HealingExecution> listPendingVerification(LocalDateTime settledBefore,
                                                          LocalDateTime observedAfter,
                                                          int limit) {
        return jdbcTemplate.query("""
                SELECT * FROM sys_healing_execution
                 WHERE status = 'SUCCEEDED' AND verify_status IS NULL
                   AND finished_at IS NOT NULL
                   AND finished_at < ? AND finished_at > ?
                 ORDER BY finished_at ASC LIMIT ?
                """, ROW_MAPPER, settledBefore, observedAfter, Math.max(1, Math.min(limit, 100)));
    }

    /**
     * 幂等闸的计数（3-3.4）：同一告警同一动作在时间窗内的「活台账」数。
     * <p>
     * 「活」= PENDING_APPROVAL（等人点头）或 SUCCEEDED（已成功且未撤销）。
     * REJECTED / FAILED / UNDONE 不拦——失败重试与撤后重来是正当诉求。
     * </p>
     */
    public int countRecentBlocking(long alertId, String actionKey, LocalDateTime since) {
        Integer n = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM sys_healing_execution
                 WHERE alert_id = ? AND action_key = ?
                   AND status IN ('PENDING_APPROVAL', 'SUCCEEDED')
                   AND created_at > ?
                """, Integer.class, alertId, actionKey, since);
        return n == null ? 0 : n;
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
