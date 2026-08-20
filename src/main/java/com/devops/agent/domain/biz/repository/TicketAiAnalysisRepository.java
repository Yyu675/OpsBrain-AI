package com.devops.agent.domain.biz.repository;

import com.devops.agent.domain.biz.entity.TicketAiAnalysis;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 工单 AI 分析数据访问层（策略 B）
 *
 * @author OpsBrain AI
 * @since 2026-08-17
 */
@Repository
public class TicketAiAnalysisRepository {

    private static final Logger log = LoggerFactory.getLogger(TicketAiAnalysisRepository.class);

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public TicketAiAnalysisRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 插入一条分析，version 由本方法计算（同工单已有最大 version + 1）
     * <p>
     * version 在插入前查询，同工单并发分析极少（用户手动触发），
     * 万一撞车，唯一后果是两条同 version，不影响「取最新」语义（按 id DESC 兜底）。
     * </p>
     *
     * @return 回填 id 与 version 的实体
     */
    public TicketAiAnalysis insert(TicketAiAnalysis a) {
        int nextVersion = nextVersion(a.getTicketId());
        a.setVersion(nextVersion);

        String sql = """
            INSERT INTO sys_ticket_ai_analysis
                (ticket_id, version, content, reasons, commands, citations, confidence, cost_rmb, create_time)
            VALUES (?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?, ?)
            """;

        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            // 显式只返回 id 列：PostgreSQL 的 RETURN_GENERATED_KEYS 会返回全部列，
            // 导致 keyHolder.getKey() 因「多个键」抛异常（6.12 教训）
            PreparedStatement ps = connection.prepareStatement(sql, new String[]{"id"});
            ps.setString(1, a.getTicketId());
            ps.setInt(2, nextVersion);
            ps.setString(3, a.getContent());
            ps.setString(4, toJson(a.getReasons()));
            ps.setString(5, toJson(a.getCommands()));
            ps.setString(6, toJson(a.getCitations()));
            if (a.getConfidence() != null) ps.setInt(7, a.getConfidence());
            else ps.setNull(7, java.sql.Types.INTEGER);
            ps.setBigDecimal(8, a.getCostRmb() != null ? a.getCostRmb() : BigDecimal.ZERO);
            ps.setObject(9, a.getCreateTime() != null ? a.getCreateTime() : LocalDateTime.now());
            return ps;
        }, keyHolder);

        Number key = keyHolder.getKey();
        a.setId(key != null ? key.longValue() : null);
        log.info("✅ [AiAnalysisRepo] 分析已入库 | ticketId={} | version={} | id={}",
                a.getTicketId(), nextVersion, a.getId());
        return a;
    }

    /** 同工单下一个 version（当前最大 + 1，无则 1） */
    private int nextVersion(String ticketId) {
        String sql = "SELECT COALESCE(MAX(version), 0) + 1 FROM sys_ticket_ai_analysis WHERE ticket_id = ?";
        Integer v = jdbcTemplate.queryForObject(sql, Integer.class, ticketId);
        return v != null ? v : 1;
    }

    /** 取工单最新一条分析（当前结论），无则 null */
    public TicketAiAnalysis findLatest(String ticketId) {
        String sql = "SELECT * FROM sys_ticket_ai_analysis WHERE ticket_id = ? ORDER BY version DESC, id DESC LIMIT 1";
        List<TicketAiAnalysis> list = jdbcTemplate.query(sql, new AnalysisRowMapper(), ticketId);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 取工单全部分析版本（version 倒序，供历史对比） */
    public List<TicketAiAnalysis> findByTicketId(String ticketId) {
        String sql = "SELECT * FROM sys_ticket_ai_analysis WHERE ticket_id = ? ORDER BY version DESC, id DESC";
        return jdbcTemplate.query(sql, new AnalysisRowMapper(), ticketId);
    }

    /**
     * 记录用户反馈（AI 准确率数据来源）
     *
     * @return 受影响行数（0=分析不存在）
     */
    public int updateFeedback(Long id, String feedback) {
        String sql = "UPDATE sys_ticket_ai_analysis SET feedback = ?, feedback_at = ? WHERE id = ?";
        return jdbcTemplate.update(sql, feedback, LocalDateTime.now(), id);
    }

    /**
     * 准确率统计：按 feedback 聚合计数
     * <p>返回 {@code {helpful, unhelpful, total, rated}}，total 含未评价。</p>
     */
    public Map<String, Long> feedbackStats() {
        String sql = """
            SELECT
                COUNT(*) AS total,
                COUNT(*) FILTER (WHERE feedback = 'HELPFUL')   AS helpful,
                COUNT(*) FILTER (WHERE feedback = 'UNHELPFUL') AS unhelpful,
                COUNT(*) FILTER (WHERE feedback IS NOT NULL)   AS rated
            FROM sys_ticket_ai_analysis
            """;
        return jdbcTemplate.queryForObject(sql, (rs, n) -> Map.of(
                "total", rs.getLong("total"),
                "helpful", rs.getLong("helpful"),
                "unhelpful", rs.getLong("unhelpful"),
                "rated", rs.getLong("rated")
        ));
    }

    /**
     * 删除工单的全部分析
     * <p>工单物理删除时级联清理，避免孤儿数据（表无外键约束）。</p>
     */
    public int deleteByTicketId(String ticketId) {
        String sql = "DELETE FROM sys_ticket_ai_analysis WHERE ticket_id = ?";
        int rows = jdbcTemplate.update(sql, ticketId);
        if (rows > 0) {
            log.info("🗑️ [AiAnalysisRepo] 已清理工单分析 | ticketId={} | rows={}", ticketId, rows);
        }
        return rows;
    }

    // ==================== JSON 序列化 ====================

    private String toJson(List<String> list) {
        if (list == null || list.isEmpty()) return "[]";
        try {
            return objectMapper.writeValueAsString(list);
        } catch (Exception e) {
            log.warn("⚠️ [AiAnalysisRepo] JSON 序列化失败，降级为空数组 | {}", e.getMessage());
            return "[]";
        }
    }

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

    private List<String> fromJson(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, STRING_LIST);
        } catch (Exception e) {
            log.warn("⚠️ [AiAnalysisRepo] JSON 反序列化失败，降级为空数组 | {}", e.getMessage());
            return List.of();
        }
    }

    // ==================== RowMapper ====================

    private class AnalysisRowMapper implements RowMapper<TicketAiAnalysis> {
        @Override
        public TicketAiAnalysis mapRow(ResultSet rs, int rowNum) throws SQLException {
            TicketAiAnalysis a = new TicketAiAnalysis();
            a.setId(rs.getLong("id"));
            a.setTicketId(rs.getString("ticket_id"));
            a.setVersion(rs.getInt("version"));
            a.setContent(rs.getString("content"));
            a.setReasons(fromJson(rs.getString("reasons")));
            a.setCommands(fromJson(rs.getString("commands")));
            a.setCitations(fromJson(rs.getString("citations")));
            int conf = rs.getInt("confidence");
            a.setConfidence(rs.wasNull() ? null : conf);
            a.setCostRmb(rs.getBigDecimal("cost_rmb"));
            a.setFeedback(rs.getString("feedback"));
            a.setFeedbackAt(rs.getObject("feedback_at", LocalDateTime.class));
            a.setCreateTime(rs.getObject("create_time", LocalDateTime.class));
            return a;
        }
    }
}
