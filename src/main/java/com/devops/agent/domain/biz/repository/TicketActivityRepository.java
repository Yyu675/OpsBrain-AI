package com.devops.agent.domain.biz.repository;

import com.devops.agent.domain.biz.entity.TicketActivity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 工单活动流数据访问层
 *
 * @author OpsBrain AI
 * @since 2026-08-09
 */
@Slf4j
@Repository
public class TicketActivityRepository {

    private final JdbcTemplate jdbcTemplate;

    public TicketActivityRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 插入活动记录
     * <p>
     * 活动流是审计性质的旁路数据，失败不应阻塞主业务，
     * 故此处捕获异常仅告警。
     * </p>
     *
     * @return 是否成功
     */
    public boolean insert(TicketActivity activity) {
        String sql = """
            INSERT INTO sys_ticket_activity (ticket_id, color, text, detail, user_name, highlight, create_time)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """;
        try {
            jdbcTemplate.update(sql,
                    activity.getTicketId(),
                    activity.getColor(),
                    activity.getText(),
                    activity.getDetail(),
                    activity.getUserName(),
                    Boolean.TRUE.equals(activity.getHighlight()),
                    activity.getCreateTime() != null ? activity.getCreateTime() : LocalDateTime.now());
            return true;
        } catch (Exception e) {
            log.warn("⚠️ [ActivityRepo] 活动记录写入失败（不影响主流程）| ticketId={} | text={} | {}",
                    activity.getTicketId(), activity.getText(), e.getMessage());
            return false;
        }
    }

    /**
     * 按工单查询活动流（时间<b>倒序</b>，最新操作在前）
     */
    public List<TicketActivity> findByTicketId(String ticketId) {
        String sql = "SELECT * FROM sys_ticket_activity WHERE ticket_id = ? ORDER BY create_time DESC, id DESC";
        try {
            return jdbcTemplate.query(sql, new ActivityRowMapper(), ticketId);
        } catch (Exception e) {
            log.warn("⚠️ [ActivityRepo] 查询活动流失败 | ticketId={} | {}", ticketId, e.getMessage());
            return List.of();
        }
    }

    /**
     * 批量查询多个工单的活动流
     * <p>供列表页一次性加载，避免 N+1 查询。</p>
     */
    public List<TicketActivity> findByTicketIds(List<String> ticketIds) {
        if (ticketIds == null || ticketIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", ticketIds.stream().map(x -> "?").toList());
        String sql = "SELECT * FROM sys_ticket_activity WHERE ticket_id IN (" + placeholders
                + ") ORDER BY ticket_id, create_time DESC, id DESC";
        try {
            return jdbcTemplate.query(sql, new ActivityRowMapper(), ticketIds.toArray());
        } catch (Exception e) {
            log.warn("⚠️ [ActivityRepo] 批量查询活动流失败 | {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 删除工单的全部活动记录
     * <p>工单物理删除时级联清理。</p>
     */
    public int deleteByTicketId(String ticketId) {
        String sql = "DELETE FROM sys_ticket_activity WHERE ticket_id = ?";
        try {
            int rows = jdbcTemplate.update(sql, ticketId);
            if (rows > 0) {
                log.info("🗑️ [ActivityRepo] 已清理工单活动流 | ticketId={} | rows={}", ticketId, rows);
            }
            return rows;
        } catch (Exception e) {
            log.warn("⚠️ [ActivityRepo] 清理活动流失败 | ticketId={} | {}", ticketId, e.getMessage());
            return 0;
        }
    }

    // ==================== RowMapper ====================

    private static class ActivityRowMapper implements RowMapper<TicketActivity> {
        @Override
        public TicketActivity mapRow(ResultSet rs, int rowNum) throws SQLException {
            TicketActivity a = new TicketActivity();
            a.setId(rs.getLong("id"));
            a.setTicketId(rs.getString("ticket_id"));
            a.setColor(rs.getString("color"));
            a.setText(rs.getString("text"));
            a.setDetail(rs.getString("detail"));
            a.setUserName(rs.getString("user_name"));
            a.setHighlight(rs.getBoolean("highlight"));
            a.setCreateTime(rs.getObject("create_time", LocalDateTime.class));
            return a;
        }
    }
}