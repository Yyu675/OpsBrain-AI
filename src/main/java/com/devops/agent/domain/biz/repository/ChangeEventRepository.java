package com.devops.agent.domain.biz.repository;

import com.devops.agent.domain.biz.entity.ChangeEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * sys_change_event 仓储（S1-2）。沿用全库 JdbcTemplate 谱系（与 AgentCallLogRepository 同风）。
 * <p>
 * 幂等写：INSERT ... ON CONFLICT (source, external_id) DO NOTHING，
 * 返回受影响行数——0 表示外部系统重发/重试的同一条事件（不是错误）。
 * external_id 为 NULL 的手工录入不参与唯一约束（PG 语义：NULL 互不相等）。
 * </p>
 */
@Repository
public class ChangeEventRepository {

    private static final RowMapper<ChangeEvent> MAPPER = (rs, n) -> new ChangeEvent(
            rs.getLong("id"),
            rs.getString("service_name"),
            rs.getString("change_type"),
            rs.getString("operator"),
            rs.getString("summary"),
            rs.getTimestamp("change_time").toLocalDateTime(),
            rs.getTimestamp("reported_at").toLocalDateTime(),
            rs.getString("source"),
            rs.getString("external_id"),
            null);

    private final JdbcTemplate jdbcTemplate;

    public ChangeEventRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 幂等写入。
     *
     * @return 1=新入库；0=重复事件被唯一约束吞掉（CI 重发场景属正常）
     */
    public int save(ChangeEvent e) {
        String sql = """
                INSERT INTO sys_change_event
                    (service_name, change_type, operator, summary, change_time, source, external_id)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (source, external_id) DO NOTHING
                """;
        return jdbcTemplate.update(sql,
                e.serviceName(), e.changeType(), e.operator(), e.summary(),
                java.sql.Timestamp.valueOf(e.changeTime()), e.source(), e.externalId());
    }

    /** 时间窗内某服务的变更，按发生时刻倒序（离现在越近越靠前）。 */
    public List<ChangeEvent> findRecent(String serviceName, LocalDateTime since, int limit) {
        String sql = """
                SELECT id, service_name, change_type, operator, summary,
                       change_time, reported_at, source, external_id
                FROM sys_change_event
                WHERE service_name = ? AND change_time >= ?
                ORDER BY change_time DESC
                LIMIT ?
                """;
        return jdbcTemplate.query(sql, MAPPER,
                serviceName, java.sql.Timestamp.valueOf(since), limit);
    }

    /** 测试/对接核对用：按幂等键查单行（不存在的返回空列表）。 */
    public List<ChangeEvent> findByExternalId(String source, String externalId) {
        String sql = """
                SELECT id, service_name, change_type, operator, summary,
                       change_time, reported_at, source, external_id
                FROM sys_change_event
                WHERE source = ? AND external_id = ?
                """;
        return jdbcTemplate.query(sql, MAPPER, source, externalId);
    }
}
