package com.devops.agent.infrastructure.persistence.repo;

import com.devops.agent.domain.tools.ToolExecutionRecord;
import com.devops.agent.domain.tools.ToolExecutionState;
import com.devops.agent.domain.tools.ToolFailureType;
import com.devops.agent.domain.tools.ToolRiskLevel;
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

/**
 * 工具执行记录数据访问层
 * <p>
 * 支撑 Saga 补偿：持久化每步状态，使进程重启后仍能恢复未完成的补偿。
 * </p>
 *
 * @author OpsBrain AI
 * @since 2026-08-08
 */
@Slf4j
@Repository
public class ToolExecutionRepository {

    private final JdbcTemplate jdbcTemplate;

    public ToolExecutionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 插入执行记录，回填自增主键
     *
     * @return 生成的主键 ID，失败返回 null
     */
    public Long insert(ToolExecutionRecord r) {
        String sql = """
            INSERT INTO sys_agent_tool_execution
                (trace_id, session_id, saga_id, step_seq, tool_name, risk_level,
                 tool_args, tool_result, state, failure_type, error_message,
                 compensable, compensation_action, business_key,
                 attempt_count, duration_ms, create_time, update_time)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """;

        KeyHolder keyHolder = new GeneratedKeyHolder();
        try {
            jdbcTemplate.update(connection -> {
                // 显式指定只返回 id：PostgreSQL 的 RETURN_GENERATED_KEYS
                // 会返回全部列，使 keyHolder.getKey() 因"多个键"抛异常
                PreparedStatement ps = connection.prepareStatement(sql, new String[]{"id"});
                ps.setString(1, r.getTraceId());
                ps.setString(2, r.getSessionId());
                ps.setString(3, r.getSagaId());
                ps.setInt(4, r.getStepSeq() != null ? r.getStepSeq() : 1);
                ps.setString(5, r.getToolName());
                ps.setString(6, r.getRiskLevel() != null ? r.getRiskLevel().name() : null);
                ps.setString(7, r.getToolArgs());
                ps.setString(8, r.getToolResult());
                ps.setString(9, r.getState() != null ? r.getState().name() : ToolExecutionState.PENDING.name());
                ps.setString(10, r.getFailureType() != null ? r.getFailureType().name() : null);
                ps.setString(11, r.getErrorMessage());
                ps.setBoolean(12, Boolean.TRUE.equals(r.getCompensable()));
                ps.setString(13, r.getCompensationAction());
                ps.setString(14, r.getBusinessKey());
                ps.setInt(15, r.getAttemptCount() != null ? r.getAttemptCount() : 1);
                if (r.getDurationMs() != null) {
                    ps.setInt(16, r.getDurationMs());
                } else {
                    ps.setNull(16, java.sql.Types.INTEGER);
                }
                return ps;
            }, keyHolder);

            Number key = keyHolder.getKey();
            return key != null ? key.longValue() : null;
        } catch (Exception e) {
            log.warn("⚠️ [ToolExecRepo] 插入执行记录失败 | tool={} | {}", r.getToolName(), e.getMessage());
            return null;
        }
    }

    /**
     * 更新执行结果与状态
     */
    public int updateResult(Long id, ToolExecutionState state, String result,
                            ToolFailureType failureType, String errorMessage,
                            String businessKey, Integer durationMs, Integer attemptCount) {
        String sql = """
            UPDATE sys_agent_tool_execution
               SET state = ?, tool_result = ?, failure_type = ?, error_message = ?,
                   business_key = COALESCE(?, business_key),
                   duration_ms = ?, attempt_count = ?, update_time = CURRENT_TIMESTAMP
             WHERE id = ?
            """;
        try {
            return jdbcTemplate.update(sql,
                    state != null ? state.name() : null,
                    result,
                    failureType != null ? failureType.name() : null,
                    errorMessage,
                    businessKey,
                    durationMs,
                    attemptCount,
                    id);
        } catch (Exception e) {
            log.warn("⚠️ [ToolExecRepo] 更新执行结果失败 | id={} | {}", id, e.getMessage());
            return 0;
        }
    }

    /**
     * 仅更新状态（补偿流转用）
     */
    public int updateState(Long id, ToolExecutionState state) {
        String sql = """
            UPDATE sys_agent_tool_execution
               SET state = ?, update_time = CURRENT_TIMESTAMP
             WHERE id = ?
            """;
        try {
            return jdbcTemplate.update(sql, state.name(), id);
        } catch (Exception e) {
            log.warn("⚠️ [ToolExecRepo] 更新状态失败 | id={} | {}", id, e.getMessage());
            return 0;
        }
    }

    /**
     * 标记补偿成功
     */
    public int markCompensated(Long id) {
        String sql = """
            UPDATE sys_agent_tool_execution
               SET state = ?, compensated_at = CURRENT_TIMESTAMP, update_time = CURRENT_TIMESTAMP
             WHERE id = ?
            """;
        try {
            return jdbcTemplate.update(sql, ToolExecutionState.COMPENSATED.name(), id);
        } catch (Exception e) {
            log.warn("⚠️ [ToolExecRepo] 标记补偿成功失败 | id={} | {}", id, e.getMessage());
            return 0;
        }
    }

    /**
     * 标记补偿失败（后续需人工介入）
     */
    public int markCompensationFailed(Long id, String error) {
        String sql = """
            UPDATE sys_agent_tool_execution
               SET state = ?, compensation_error = ?, update_time = CURRENT_TIMESTAMP
             WHERE id = ?
            """;
        try {
            return jdbcTemplate.update(sql,
                    ToolExecutionState.COMPENSATION_FAILED.name(), error, id);
        } catch (Exception e) {
            log.warn("⚠️ [ToolExecRepo] 标记补偿失败失败 | id={} | {}", id, e.getMessage());
            return 0;
        }
    }

    /**
     * 查询 Saga 内待补偿步骤（按步骤序号<b>逆序</b>）
     * <p>Saga 补偿铁律：逆序回滚，后执行的先撤销。</p>
     */
    public List<ToolExecutionRecord> findCompensableBySagaDesc(String sagaId) {
        String sql = """
            SELECT * FROM sys_agent_tool_execution
             WHERE saga_id = ?
               AND state IN (?, ?)
               AND compensable = TRUE
               AND compensated_at IS NULL
             ORDER BY step_seq DESC
            """;
        try {
            return jdbcTemplate.query(sql, new RecordRowMapper(), sagaId,
                    ToolExecutionState.SUCCESS.name(),
                    ToolExecutionState.PARTIAL_SUCCESS.name());
        } catch (Exception e) {
            log.warn("⚠️ [ToolExecRepo] 查询待补偿步骤失败 | sagaId={} | {}", sagaId, e.getMessage());
            return List.of();
        }
    }

    /**
     * 查询 Saga 全部步骤（正序，供回放）
     */
    public List<ToolExecutionRecord> findBySaga(String sagaId) {
        String sql = "SELECT * FROM sys_agent_tool_execution WHERE saga_id = ? ORDER BY step_seq";
        try {
            return jdbcTemplate.query(sql, new RecordRowMapper(), sagaId);
        } catch (Exception e) {
            // 与 findNeedingAttention 同型：空列表与「这个 Saga 真的没有步骤」
            // 在接口上无法区分。本方法是 Saga 链路回放（/saga/{id}/steps）的
            // 唯一数据源，查询失败时排查者会看到「0 步」，
            // 从而认为这个 Saga 根本没执行过——而实际可能有一批步骤正等着补偿。
            // 兜底仍返回空列表（回放是旁路，不该带崩页面），但必须留下线索。
            log.error("🚨 [ToolExecRepo] 查询 Saga 步骤失败，回放将显示为空"
                    + "（这与「该 Saga 无步骤」无法区分）| sagaId={} | {}", sagaId, e.getMessage());
            return List.of();
        }
    }

    /**
     * 查询 trace 下全部步骤
     */
    public List<ToolExecutionRecord> findByTrace(String traceId) {
        String sql = "SELECT * FROM sys_agent_tool_execution WHERE trace_id = ? ORDER BY step_seq";
        try {
            return jdbcTemplate.query(sql, new RecordRowMapper(), traceId);
        } catch (Exception e) {
            log.error("🚨 [ToolExecRepo] 按 traceId 查询工具执行记录失败，"
                    + "调用链追溯将显示为空 | traceId={} | {}", traceId, e.getMessage());
            return List.of();
        }
    }

    /**
     * 查询下一个可用步骤序号（同步化防并发竞争，P2-37）
     *
     * <p><b>查询失败时兜底返回 1，但必须留下日志。</b>
     * step_seq 决定 Saga 补偿的<b>逆序回滚顺序</b>
     * （{@link #findCompensableBySagaDesc} 按 step_seq DESC 排序）。
     * 兜底为 1 会让同一个 Saga 内出现重复序号，回滚顺序随之退化为不确定——
     * 后执行的步骤可能先于其依赖的前序步骤被撤销。</p>
     *
     * <p>原实现是 {@code catch (Exception e) { return 1; }}，
     * 连一行日志都没有：回滚顺序错乱后，现场没有任何线索指向这里。
     * 兜底本身是对的（登记记录不该因取序号失败而中断），
     * 但必须让它可被发现。</p>
     */
    public synchronized int nextStepSeq(String sagaId) {
        String sql = "SELECT COALESCE(MAX(step_seq), 0) + 1 FROM sys_agent_tool_execution WHERE saga_id = ?";
        try {
            Integer n = jdbcTemplate.queryForObject(sql, Integer.class, sagaId);
            return n != null ? n : 1;
        } catch (Exception e) {
            log.warn("⚠️ [ToolExecRepo] 取步骤序号失败，兜底为 1（可能造成 Saga 内序号重复，"
                    + "补偿逆序回滚顺序将不确定）| sagaId={} | {}", sagaId, e.getMessage());
            return 1;
        }
    }

    /**
     * 查询需人工介入的记录（供告警与看板）
     *
     * <p><b>查询失败返回空列表，且必须留下 ERROR 日志。</b>
     * 本方法是「需人工介入」看板的唯一数据源，而空列表在界面上
     * 与「真的没有待处理项」<b>完全无法区分</b>——运维看到
     * {@code count: 0} 会认为系统健康，而实际可能有一批补偿失败的
     * 脏数据正躺在库里无人处理。</p>
     *
     * <p>之所以仍返回空列表而不抛异常：看板是旁路能力，
     * 让它把整个页面带崩没有意义。但级别必须是 ERROR 而非静默——
     * 原实现是 {@code catch (Exception e) { return List.of(); }}，
     * 连日志都没有。</p>
     */
    public List<ToolExecutionRecord> findNeedingAttention(int limit) {
        String sql = """
            SELECT * FROM sys_agent_tool_execution
             WHERE state IN (?, ?, ?)
             ORDER BY create_time DESC
             LIMIT ?
            """;
        try {
            return jdbcTemplate.query(sql, new RecordRowMapper(),
                    ToolExecutionState.PARTIAL_SUCCESS.name(),
                    ToolExecutionState.COMPENSATION_FAILED.name(),
                    ToolExecutionState.MANUAL_INTERVENTION_REQUIRED.name(),
                    limit);
        } catch (Exception e) {
            // ERROR 而非 WARN：这条查询失败意味着「人工介入看板」失明，
            // 而失明与「没有待处理项」在界面上长得一模一样
            log.error("🚨 [ToolExecRepo] 查询需人工介入记录失败，看板将显示为空"
                    + "（这与「确实没有待处理项」无法区分）| {}", e.getMessage());
            return List.of();
        }
    }

    // ==================== RowMapper ====================

    private static class RecordRowMapper implements RowMapper<ToolExecutionRecord> {
        @Override
        public ToolExecutionRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
            ToolExecutionRecord r = new ToolExecutionRecord();
            r.setId(rs.getLong("id"));
            r.setTraceId(rs.getString("trace_id"));
            r.setSessionId(rs.getString("session_id"));
            r.setSagaId(rs.getString("saga_id"));
            r.setStepSeq(rs.getInt("step_seq"));
            r.setToolName(rs.getString("tool_name"));
            r.setRiskLevel(parseEnum(ToolRiskLevel.class, rs.getString("risk_level")));
            r.setToolArgs(rs.getString("tool_args"));
            r.setToolResult(rs.getString("tool_result"));
            r.setState(parseEnum(ToolExecutionState.class, rs.getString("state")));
            r.setFailureType(parseEnum(ToolFailureType.class, rs.getString("failure_type")));
            r.setErrorMessage(rs.getString("error_message"));
            r.setCompensable(rs.getBoolean("compensable"));
            r.setCompensationAction(rs.getString("compensation_action"));
            r.setBusinessKey(rs.getString("business_key"));
            r.setCompensatedAt(rs.getObject("compensated_at", LocalDateTime.class));
            r.setCompensationError(rs.getString("compensation_error"));
            r.setAttemptCount(rs.getInt("attempt_count"));
            int dur = rs.getInt("duration_ms");
            r.setDurationMs(rs.wasNull() ? null : dur);
            r.setCreateTime(rs.getObject("create_time", LocalDateTime.class));
            r.setUpdateTime(rs.getObject("update_time", LocalDateTime.class));
            return r;
        }

        /** 枚举安全解析：脏数据不致整行失败 */
        private <E extends Enum<E>> E parseEnum(Class<E> type, String value) {
            if (value == null || value.isBlank()) return null;
            try {
                return Enum.valueOf(type, value);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }
}