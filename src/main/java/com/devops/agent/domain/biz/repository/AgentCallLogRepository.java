package com.devops.agent.domain.biz.repository;

import com.devops.agent.domain.biz.entity.AgentCallLog;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Agent 调用日志数据访问层
 * <p>
 * MVP-4 审计增强：新增 operation_type、affected_resources、operator_id 字段
 * </p>
 *
 * @author OpsBrain AI
 * @since 2026-07-15
 */
@Repository
public class AgentCallLogRepository {

    private final JdbcTemplate jdbcTemplate;

    public AgentCallLogRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 保存调用日志
     */
    public int save(AgentCallLog log) {
        String sql = """
            INSERT INTO sys_agent_call_log (trace_id, user_query, agent_answer, model_name, is_cached, latency_ms, cost_rmb, citations, operation_type, affected_resources, operator_id, create_time)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
        return jdbcTemplate.update(sql,
                log.getTraceId(),
                log.getUserQuery(),
                log.getAgentAnswer(),
                log.getModelName(),
                log.getIsCached(),
                log.getLatencyMs(),
                log.getCostRmb(),
                log.getCitations(),
                log.getOperationType(),
                log.getAffectedResources(),
                log.getOperatorId(),
                log.getCreateTime()
        );
    }

    /**
     * 查询调用总数
     */
    public long countAll() {
        String sql = "SELECT COUNT(*) FROM sys_agent_call_log";
        Long count = jdbcTemplate.queryForObject(sql, Long.class);
        return count != null ? count : 0L;
    }

    /**
     * 查询缓存命中数
     */
    public long countCacheHits() {
        String sql = "SELECT COUNT(*) FROM sys_agent_call_log WHERE is_cached = true";
        Long count = jdbcTemplate.queryForObject(sql, Long.class);
        return count != null ? count : 0L;
    }

    /**
     * 查询平均成本
     */
    public double getAvgCost() {
        String sql = "SELECT AVG(cost_rmb) FROM sys_agent_call_log";
        Double avg = jdbcTemplate.queryForObject(sql, Double.class);
        return avg != null ? avg : 0.0;
    }

    /**
     * 查询模型分布统计
     */
    public List<ModelDistribution> getModelDistribution() {
        String sql = """
            SELECT model_name, COUNT(*) as count
            FROM sys_agent_call_log
            GROUP BY model_name
            ORDER BY count DESC
            """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            ModelDistribution dist = new ModelDistribution();
            dist.setName(rs.getString("model_name"));
            dist.setValue(rs.getLong("count"));
            return dist;
        });
    }

    /**
     * 查询最近 N 条日志
     */
    public List<AgentCallLog> findRecent(int limit) {
        String sql = "SELECT * FROM sys_agent_call_log ORDER BY create_time DESC LIMIT ?";
        return jdbcTemplate.query(sql, new LogRowMapper(), limit);
    }

    /**
     * 模型分布 DTO
     */
    public static class ModelDistribution {
        private String name;
        private Long value;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public Long getValue() {
            return value;
        }

        public void setValue(Long value) {
            this.value = value;
        }
    }

    /**
     * RowMapper 实现
     */
    private static class LogRowMapper implements RowMapper<AgentCallLog> {
        @Override
        public AgentCallLog mapRow(ResultSet rs, int rowNum) throws SQLException {
            AgentCallLog log = new AgentCallLog();
            log.setId(rs.getLong("id"));
            log.setTraceId(rs.getString("trace_id"));
            log.setUserQuery(rs.getString("user_query"));
            log.setAgentAnswer(rs.getString("agent_answer"));
            log.setModelName(rs.getString("model_name"));
            log.setIsCached(rs.getBoolean("is_cached"));
            log.setLatencyMs(rs.getInt("latency_ms"));
            log.setCostRmb(rs.getDouble("cost_rmb"));
            log.setCitations(rs.getString("citations"));
            log.setOperationType(rs.getString("operation_type"));
            log.setAffectedResources(rs.getString("affected_resources"));
            log.setOperatorId(rs.getString("operator_id"));
            log.setCreateTime(rs.getObject("create_time", LocalDateTime.class));
            return log;
        }
    }
}
