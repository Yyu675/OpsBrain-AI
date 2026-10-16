package com.devops.agent.domain.biz.repository;

import com.devops.agent.domain.biz.entity.TicketReply;
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
 * 工单回复数据访问层
 *
 * @author OpsBrain AI
 * @since 2026-08-09
 */
@Slf4j
@Repository
public class TicketReplyRepository {

    private final JdbcTemplate jdbcTemplate;

    public TicketReplyRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 插入回复，回填自增主键
     *
     * @return 生成的主键 ID
     */
    public Long insert(TicketReply reply) {
        String sql = """
            INSERT INTO sys_ticket_reply (ticket_id, role, author, author_color, content, create_time)
            VALUES (?, ?, ?, ?, ?, ?)
            """;

        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            // 显式指定只返回 id 列。
            // PostgreSQL 的 RETURN_GENERATED_KEYS 会返回全部列，
            // 导致 keyHolder.getKey() 因"多个键"抛异常。
            PreparedStatement ps = connection.prepareStatement(sql, new String[]{"id"});
            ps.setString(1, reply.getTicketId());
            ps.setString(2, reply.getRole());
            ps.setString(3, reply.getAuthor());
            ps.setString(4, reply.getAuthorColor());
            ps.setString(5, reply.getContent());
            ps.setObject(6, reply.getCreateTime() != null ? reply.getCreateTime() : LocalDateTime.now());
            return ps;
        }, keyHolder);

        Number key = keyHolder.getKey();
        Long id = key != null ? key.longValue() : null;
        log.info("✅ [ReplyRepo] 回复已入库 | ticketId={} | replyId={} | role={}",
                reply.getTicketId(), id, reply.getRole());
        return id;
    }

    /**
     * 按工单查询回复（时间正序，供时间线展示）
     */
    public List<TicketReply> findByTicketId(String ticketId) {
        String sql = "SELECT * FROM sys_ticket_reply WHERE ticket_id = ? ORDER BY create_time, id";
        return jdbcTemplate.query(sql, new ReplyRowMapper(), ticketId);
    }

    /**
     * 批量查询多个工单的回复
     * <p>供列表页一次性加载，避免 N+1 查询。</p>
     */
    public List<TicketReply> findByTicketIds(List<String> ticketIds) {
        if (ticketIds == null || ticketIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", ticketIds.stream().map(x -> "?").toList());
        String sql = "SELECT * FROM sys_ticket_reply WHERE ticket_id IN (" + placeholders
                + ") ORDER BY ticket_id, create_time, id";
        return jdbcTemplate.query(sql, new ReplyRowMapper(), ticketIds.toArray());
    }

    /**
     * 统计工单回复数
     */
    public int countByTicketId(String ticketId) {
        String sql = "SELECT COUNT(*) FROM sys_ticket_reply WHERE ticket_id = ?";
        Integer n = jdbcTemplate.queryForObject(sql, Integer.class, ticketId);
        return n != null ? n : 0;
    }

    /**
     * 删除工单的全部回复
     * <p>工单物理删除时级联清理，避免孤儿数据。</p>
     *
     * @return 删除行数
     */
    public int deleteByTicketId(String ticketId) {
        String sql = "DELETE FROM sys_ticket_reply WHERE ticket_id = ?";
        int rows = jdbcTemplate.update(sql, ticketId);
        if (rows > 0) {
            log.info("🗑️ [ReplyRepo] 已清理工单回复 | ticketId={} | rows={}", ticketId, rows);
        }
        return rows;
    }

    // ==================== RowMapper ====================

    private static class ReplyRowMapper implements RowMapper<TicketReply> {
        @Override
        public TicketReply mapRow(ResultSet rs, int rowNum) throws SQLException {
            TicketReply r = new TicketReply();
            r.setId(rs.getLong("id"));
            r.setTicketId(rs.getString("ticket_id"));
            r.setRole(rs.getString("role"));
            r.setAuthor(rs.getString("author"));
            r.setAuthorColor(rs.getString("author_color"));
            r.setContent(rs.getString("content"));
            r.setCreateTime(rs.getObject("create_time", LocalDateTime.class));
            return r;
        }
    }
}