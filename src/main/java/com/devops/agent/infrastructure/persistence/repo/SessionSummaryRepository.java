package com.devops.agent.infrastructure.persistence.repo;

import com.devops.agent.domain.memory.KeyFacts;
import com.devops.agent.domain.memory.SessionSummary;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 温记忆数据访问层
 * <p>
 * 操作 {@code sys_agent_session_summary}，支持 UPSERT（同会话多轮累积更新）。
 * </p>
 *
 * @author OpsBrain AI
 * @since 2026-08-08
 */
@Slf4j
@Repository
public class SessionSummaryRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public SessionSummaryRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * UPSERT 会话摘要
     * <p>
     * 同一 session_id 多轮对话时，累积 turn_count / tokens / cost，
     * 覆盖 summary / key_facts / final_state（以最新蒸馏结果为准）。
     * </p>
     *
     * @return 受影响行数
     */
    public int upsert(SessionSummary s) {
        String keyFactsJson = serializeKeyFacts(s.getKeyFacts());

        String sql = """
            INSERT INTO sys_agent_session_summary
                (session_id, trace_id, tenant_id, summary, key_facts,
                 turn_count, total_tokens, total_cost_rmb, final_state,
                 related_tickets, create_time, update_time)
            VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            ON CONFLICT (session_id) DO UPDATE SET
                trace_id        = EXCLUDED.trace_id,
                summary         = EXCLUDED.summary,
                -- 本轮事实序列化失败为 NULL 时保留原值，不得清空历史事实（P2-30）
                key_facts       = COALESCE(EXCLUDED.key_facts, sys_agent_session_summary.key_facts),
                turn_count      = sys_agent_session_summary.turn_count + EXCLUDED.turn_count,
                total_tokens    = sys_agent_session_summary.total_tokens + EXCLUDED.total_tokens,
                total_cost_rmb  = sys_agent_session_summary.total_cost_rmb + EXCLUDED.total_cost_rmb,
                final_state     = EXCLUDED.final_state,
                related_tickets = EXCLUDED.related_tickets,
                update_time     = CURRENT_TIMESTAMP
            """;

        return jdbcTemplate.update(sql,
                s.getSessionId(),
                s.getTraceId(),
                s.getTenantId() != null ? s.getTenantId() : "default",
                s.getSummary(),
                keyFactsJson,
                s.getTurnCount() != null ? s.getTurnCount() : 0,
                s.getTotalTokens() != null ? s.getTotalTokens() : 0,
                s.getTotalCostRmb() != null ? s.getTotalCostRmb() : 0.0,
                s.getFinalState(),
                s.getRelatedTickets()
        );
    }

    /**
     * 按会话 ID 查询（续聊时加载）
     */
    public SessionSummary findBySessionId(String sessionId) {
        String sql = "SELECT * FROM sys_agent_session_summary WHERE session_id = ? LIMIT 1";
        List<SessionSummary> list = jdbcTemplate.query(sql, new SummaryRowMapper(), sessionId);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 分页查询最近会话（供前端「历史会话」列表）
     *
     * @param tenantId 租户标识
     * @param limit    条数
     * @param offset   偏移
     */
    public List<SessionSummary> findRecent(String tenantId, int limit, int offset) {
        String sql = """
            SELECT * FROM sys_agent_session_summary
             WHERE tenant_id = ?
             ORDER BY update_time DESC
             LIMIT ? OFFSET ?
            """;
        return jdbcTemplate.query(sql, new SummaryRowMapper(), tenantId, limit, offset);
    }

    /**
     * 统计会话总数
     */
    public long countByTenant(String tenantId) {
        String sql = "SELECT COUNT(*) FROM sys_agent_session_summary WHERE tenant_id = ?";
        Long c = jdbcTemplate.queryForObject(sql, Long.class, tenantId);
        return c != null ? c : 0L;
    }

    // ==================== 冷记忆归档（B-2） ====================

    /**
     * 查询待归档的会话（超过保留期且未归档）
     * <p>
     * 命中索引 {@code idx_summary_archive_scan (archived, create_time)}——
     * 该索引自建表起就为归档扫描预留，此前无代码使用。
     * </p>
     * <p>
     * 按 {@code create_time} 而非 {@code update_time} 判定：会话可能被反复续聊，
     * 用 update_time 会让长期活跃的老会话永不归档；create_time 反映会话真实年龄。
     * </p>
     *
     * @param retentionDays 保留天数，超过此天数的会话进入归档候选
     * @param limit         单批上限，防长事务
     * @return 待归档会话列表，最旧优先（先归档最该归档的）
     */
    public List<SessionSummary> findArchiveCandidates(int retentionDays, int limit) {
        String sql = """
            SELECT * FROM sys_agent_session_summary
             WHERE COALESCE(archived, FALSE) = FALSE
               AND create_time < (CURRENT_DATE - CAST(? AS INTEGER))
             ORDER BY create_time
             LIMIT ?
            """;
        return jdbcTemplate.query(sql, new SummaryRowMapper(), retentionDays, limit);
    }

    /**
     * 标记会话已归档并记录冷存储路径
     * <p>
     * 条件带 {@code archived = FALSE}：并发或重跑时不覆盖已有 archive_path，
     * 否则第二次归档会把指针改向新对象而旧对象成为孤儿（无人能删）。
     * </p>
     *
     * @return 受影响行数（0 表示已被其他执行归档，属正常竞态非错误）
     */
    public int markArchived(Long id, String archivePath) {
        String sql = """
            UPDATE sys_agent_session_summary
               SET archived = TRUE, archive_path = ?, update_time = CURRENT_TIMESTAMP
             WHERE id = ? AND COALESCE(archived, FALSE) = FALSE
            """;
        return jdbcTemplate.update(sql, archivePath, id);
    }

    /**
     * 统计待归档数量（供运维观察积压）
     */
    public long countArchiveCandidates(int retentionDays) {
        String sql = """
            SELECT COUNT(*) FROM sys_agent_session_summary
             WHERE COALESCE(archived, FALSE) = FALSE
               AND create_time < (CURRENT_DATE - CAST(? AS INTEGER))
            """;
        Long c = jdbcTemplate.queryForObject(sql, Long.class, retentionDays);
        return c != null ? c : 0L;
    }

    // ==================== JSON 序列化 ====================

    private String serializeKeyFacts(KeyFacts facts) {
        if (facts == null) return null;
        try {
            return objectMapper.writeValueAsString(facts);
        } catch (Exception e) {
            log.warn("⚠️ [SessionSummaryRepo] KeyFacts 序列化失败: {}", e.getMessage());
            return null;
        }
    }

    private KeyFacts deserializeKeyFacts(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, KeyFacts.class);
        } catch (Exception e) {
            log.warn("⚠️ [SessionSummaryRepo] KeyFacts 反序列化失败: {}", e.getMessage());
            return null;
        }
    }

    // ==================== RowMapper ====================

    private class SummaryRowMapper implements RowMapper<SessionSummary> {
        @Override
        public SessionSummary mapRow(ResultSet rs, int rowNum) throws SQLException {
            SessionSummary s = new SessionSummary();
            s.setId(rs.getLong("id"));
            s.setSessionId(rs.getString("session_id"));
            s.setTraceId(rs.getString("trace_id"));
            s.setTenantId(rs.getString("tenant_id"));
            s.setSummary(rs.getString("summary"));
            s.setKeyFacts(deserializeKeyFacts(rs.getString("key_facts")));
            s.setTurnCount(rs.getInt("turn_count"));
            s.setTotalTokens(rs.getInt("total_tokens"));
            s.setTotalCostRmb(rs.getDouble("total_cost_rmb"));
            s.setFinalState(rs.getString("final_state"));
            s.setRelatedTickets(rs.getString("related_tickets"));
            s.setArchived(rs.getBoolean("archived"));
            s.setArchivePath(rs.getString("archive_path"));
            s.setCreateTime(rs.getObject("create_time", LocalDateTime.class));
            s.setUpdateTime(rs.getObject("update_time", LocalDateTime.class));
            return s;
        }
    }
}