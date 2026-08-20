package com.devops.agent.domain.approval;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 审批单仓储（方向 D）
 *
 * <h3>关键设计：决策用 CAS 防并发</h3>
 * {@code markApproved}/{@code markRejected} 带 {@code AND status='PENDING'} 条件——
 * 两个管理员同时点批准时只有一个生效（受影响 0 行 = 已被他人决策），
 * 避免同一动作被执行两次。
 *
 * @author OpsBrain AI
 * @since 2026-08-20
 */
@Slf4j
@Repository
public class ApprovalRequestRepository {

    private final JdbcTemplate jdbcTemplate;

    public ApprovalRequestRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 提交审批单
     *
     * @return 生成的审批单 ID
     */
    public Long insert(ApprovalRequest req) {
        String sql = """
            INSERT INTO sys_approval_request
                (action_type, tool_name, risk_level, summary, payload, requester,
                 trace_id, session_id, status, expires_at, create_time, update_time)
            VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """;
        KeyHolder kh = new GeneratedKeyHolder();
        // 显式指定返回列：PostgreSQL 的 RETURN_GENERATED_KEYS 会返回全部列，
        // KeyHolder.getKey() 遇多列抛「multiple keys」（6.12 已踩过）
        jdbcTemplate.update(conn -> {
            PreparedStatement ps = conn.prepareStatement(sql, new String[]{"id"});
            ps.setString(1, req.getActionType());
            ps.setString(2, req.getToolName());
            ps.setString(3, req.getRiskLevel());
            ps.setString(4, req.getSummary());
            ps.setString(5, req.getPayload());
            ps.setString(6, req.getRequester());
            ps.setString(7, req.getTraceId());
            ps.setString(8, req.getSessionId());
            ps.setString(9, req.getStatus() != null ? req.getStatus() : ApprovalStatus.PENDING.name());
            if (req.getExpiresAt() != null) {
                ps.setTimestamp(10, java.sql.Timestamp.valueOf(req.getExpiresAt()));
            } else {
                ps.setNull(10, java.sql.Types.TIMESTAMP);
            }
            return ps;
        }, kh);
        Number key = kh.getKey();
        return key != null ? key.longValue() : null;
    }

    public Optional<ApprovalRequest> findById(Long id) {
        String sql = "SELECT * FROM sys_approval_request WHERE id = ?";
        return jdbcTemplate.query(sql, new ApprovalRowMapper(), id).stream().findFirst();
    }

    /** 待审队列：最早提交的先审（分页） */
    public List<ApprovalRequest> findPending(int limit, int offset) {
        String sql = """
            SELECT * FROM sys_approval_request
             WHERE status = 'PENDING'
             ORDER BY create_time
             LIMIT ? OFFSET ?
            """;
        return jdbcTemplate.query(sql, new ApprovalRowMapper(), limit, offset);
    }

    /** 按状态查询（status 为空则全部），倒序（最新决策在前） */
    public List<ApprovalRequest> findByStatus(String status, int limit, int offset) {
        if (status == null || status.isBlank()) {
            String sql = "SELECT * FROM sys_approval_request ORDER BY create_time DESC LIMIT ? OFFSET ?";
            return jdbcTemplate.query(sql, new ApprovalRowMapper(), limit, offset);
        }
        String sql = """
            SELECT * FROM sys_approval_request
             WHERE status = ?
             ORDER BY create_time DESC
             LIMIT ? OFFSET ?
            """;
        return jdbcTemplate.query(sql, new ApprovalRowMapper(), status, limit, offset);
    }

    public int countByStatus(String status) {
        if (status == null || status.isBlank()) {
            Long c = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sys_approval_request", Long.class);
            return c != null ? c.intValue() : 0;
        }
        Long c = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_approval_request WHERE status = ?", Long.class, status);
        return c != null ? c.intValue() : 0;
    }

    /**
     * 批准（CAS：仅 PENDING 可批准）
     *
     * @return 受影响行数，0 = 已被他人决策（并发）或状态已流转
     */
    public int markApproved(Long id, String approver, String reason) {
        String sql = """
            UPDATE sys_approval_request
               SET status = 'APPROVED', approver = ?, decision_reason = ?,
                   decided_at = CURRENT_TIMESTAMP, update_time = CURRENT_TIMESTAMP
             WHERE id = ? AND status = 'PENDING'
            """;
        return jdbcTemplate.update(sql, approver, reason, id);
    }

    /** 驳回（CAS：仅 PENDING 可驳回） */
    public int markRejected(Long id, String approver, String reason) {
        String sql = """
            UPDATE sys_approval_request
               SET status = 'REJECTED', approver = ?, decision_reason = ?,
                   decided_at = CURRENT_TIMESTAMP, update_time = CURRENT_TIMESTAMP
             WHERE id = ? AND status = 'PENDING'
            """;
        return jdbcTemplate.update(sql, approver, reason, id);
    }

    /**
     * 回写执行结果（APPROVED → EXECUTED / EXECUTE_FAILED）
     * <p>CAS 带 {@code status='APPROVED'}：防止未批准的单被标记已执行。</p>
     */
    public int markExecuted(Long id, boolean success, String result) {
        String sql = """
            UPDATE sys_approval_request
               SET status = ?, executed_at = CURRENT_TIMESTAMP, execute_result = ?,
                   update_time = CURRENT_TIMESTAMP
             WHERE id = ? AND status = 'APPROVED'
            """;
        String next = success ? ApprovalStatus.EXECUTED.name() : ApprovalStatus.EXECUTE_FAILED.name();
        return jdbcTemplate.update(sql, next, result, id);
    }

    /**
     * 批量标记超时（PENDING 且 expires_at 已过）
     * <p>命中部分索引 {@code idx_approval_pending_expire}。</p>
     *
     * @return 标记数
     */
    public int markExpired(LocalDateTime now) {
        String sql = """
            UPDATE sys_approval_request
               SET status = 'EXPIRED', update_time = CURRENT_TIMESTAMP
             WHERE status = 'PENDING' AND expires_at IS NOT NULL AND expires_at < ?
            """;
        return jdbcTemplate.update(sql, now);
    }

    private static class ApprovalRowMapper implements RowMapper<ApprovalRequest> {
        @Override
        public ApprovalRequest mapRow(ResultSet rs, int rowNum) throws SQLException {
            ApprovalRequest r = new ApprovalRequest();
            r.setId(rs.getLong("id"));
            r.setActionType(rs.getString("action_type"));
            r.setToolName(rs.getString("tool_name"));
            r.setRiskLevel(rs.getString("risk_level"));
            r.setSummary(rs.getString("summary"));
            r.setPayload(rs.getString("payload"));
            r.setRequester(rs.getString("requester"));
            r.setTraceId(rs.getString("trace_id"));
            r.setSessionId(rs.getString("session_id"));
            r.setStatus(rs.getString("status"));
            r.setApprover(rs.getString("approver"));
            r.setDecisionReason(rs.getString("decision_reason"));
            r.setExecuteResult(rs.getString("execute_result"));
            var decided = rs.getTimestamp("decided_at");
            if (decided != null) r.setDecidedAt(decided.toLocalDateTime());
            var expires = rs.getTimestamp("expires_at");
            if (expires != null) r.setExpiresAt(expires.toLocalDateTime());
            var executed = rs.getTimestamp("executed_at");
            if (executed != null) r.setExecutedAt(executed.toLocalDateTime());
            var ct = rs.getTimestamp("create_time");
            if (ct != null) r.setCreateTime(ct.toLocalDateTime());
            var ut = rs.getTimestamp("update_time");
            if (ut != null) r.setUpdateTime(ut.toLocalDateTime());
            return r;
        }
    }
}
