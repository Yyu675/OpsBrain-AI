package com.devops.agent.domain.biz.repository;

import com.devops.agent.domain.biz.entity.TicketActionItem;
import com.devops.agent.domain.biz.entity.TicketPostmortem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 复盘归档 + 改进项数据访问层（B4）
 *
 * @author OpsBrain AI
 * @since 2026-08-18
 */
@Repository
public class TicketPostmortemRepository {

    private static final Logger log = LoggerFactory.getLogger(TicketPostmortemRepository.class);

    private final JdbcTemplate jdbcTemplate;

    public TicketPostmortemRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // ==================== 复盘正文 ====================

    private static final RowMapper<TicketPostmortem> PM_MAPPER = (rs, rowNum) -> {
        TicketPostmortem p = new TicketPostmortem();
        p.setId(rs.getLong("id"));
        p.setTicketId(rs.getString("ticket_id"));
        p.setTimeline(rs.getString("timeline"));
        p.setImpactScope(rs.getString("impact_scope"));
        int dur = rs.getInt("impact_duration");
        p.setImpactDuration(rs.wasNull() ? null : dur);
        p.setLessons(rs.getString("lessons"));
        Long docId = rs.getLong("doc_id");
        p.setDocId(rs.wasNull() ? null : docId);
        p.setAuthor(rs.getString("author"));
        p.setCreateTime(rs.getObject("create_time", LocalDateTime.class));
        p.setUpdateTime(rs.getObject("update_time", LocalDateTime.class));
        return p;
    };

    /** 按工单查复盘（一张工单一份，UNIQUE 约束保证） */
    public TicketPostmortem findByTicketId(String ticketId) {
        String sql = "SELECT * FROM sys_ticket_postmortem WHERE ticket_id = ?";
        List<TicketPostmortem> list = jdbcTemplate.query(sql, PM_MAPPER, ticketId);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 新建复盘 */
    public Long insert(TicketPostmortem pm) {
        String sql = """
            INSERT INTO sys_ticket_postmortem (ticket_id, timeline, impact_scope, impact_duration, lessons, author)
            VALUES (?, ?, ?, ?, ?, ?)
            """;
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(conn -> {
            PreparedStatement ps = conn.prepareStatement(sql, new String[]{"id"});
            ps.setString(1, pm.getTicketId());
            ps.setString(2, pm.getTimeline());
            ps.setString(3, pm.getImpactScope());
            if (pm.getImpactDuration() != null) ps.setInt(4, pm.getImpactDuration());
            else ps.setNull(4, java.sql.Types.INTEGER);
            ps.setString(5, pm.getLessons());
            ps.setString(6, pm.getAuthor());
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        Long id = key != null ? key.longValue() : null;
        log.info("✅ [PostmortemRepo] 复盘已创建 | ticketId={} | id={}", pm.getTicketId(), id);
        return id;
    }

    /** 更新复盘（不带 version，复盘是附属文档，无并发编辑冲突风险） */
    public int update(TicketPostmortem pm) {
        String sql = """
            UPDATE sys_ticket_postmortem
               SET timeline = ?, impact_scope = ?, impact_duration = ?, lessons = ?,
                   doc_id = ?, author = ?, update_time = CURRENT_TIMESTAMP
             WHERE ticket_id = ?
            """;
        return jdbcTemplate.update(sql,
                pm.getTimeline(), pm.getImpactScope(),
                pm.getImpactDuration(), pm.getLessons(),
                pm.getDocId(), pm.getAuthor(), pm.getTicketId());
    }

    /** 工单删除时级联清理 */
    public int deleteByTicketId(String ticketId) {
        // 先删改进项（需 postmortem_id 关联）
        String findPmId = "SELECT id FROM sys_ticket_postmortem WHERE ticket_id = ?";
        List<Long> pmIds = jdbcTemplate.queryForList(findPmId, Long.class, ticketId);
        int items = 0;
        if (!pmIds.isEmpty()) {
            String placeholders = String.join(",", pmIds.stream().map(x -> "?").toList());
            items = jdbcTemplate.update(
                    "DELETE FROM sys_postmortem_action_item WHERE postmortem_id IN (" + placeholders + ")",
                    pmIds.toArray());
        }
        // 也按 ticket_id 冗余列清理（防遗漏）
        jdbcTemplate.update("DELETE FROM sys_postmortem_action_item WHERE ticket_id = ?", ticketId);
        int pm = jdbcTemplate.update("DELETE FROM sys_ticket_postmortem WHERE ticket_id = ?", ticketId);
        if (pm + items > 0) {
            log.info("🗑️ [PostmortemRepo] 级联清理 | ticketId={} | postmortem={} items={}", ticketId, pm, items);
        }
        return pm + items;
    }

    // ==================== 改进项 ====================

    private static final RowMapper<TicketActionItem> ITEM_MAPPER = (rs, rowNum) -> {
        TicketActionItem a = new TicketActionItem();
        a.setId(rs.getLong("id"));
        a.setPostmortemId(rs.getLong("postmortem_id"));
        a.setTicketId(rs.getString("ticket_id"));
        a.setContent(rs.getString("content"));
        a.setOwner(rs.getString("owner"));
        java.sql.Date dd = rs.getDate("due_date");
        a.setDueDate(dd != null ? dd.toLocalDate() : null);
        a.setStatus(rs.getString("status"));
        a.setCreateTime(rs.getObject("create_time", LocalDateTime.class));
        a.setUpdateTime(rs.getObject("update_time", LocalDateTime.class));
        return a;
    };

    /** 按复盘 ID 查全部改进项 */
    public List<TicketActionItem> findActionItemsByPostmortemId(long postmortemId) {
        String sql = "SELECT * FROM sys_postmortem_action_item WHERE postmortem_id = ? ORDER BY create_time, id";
        return jdbcTemplate.query(sql, ITEM_MAPPER, postmortemId);
    }

    /**
     * 查询改进项清单（支持按状态/责任人/逾期筛选）
     *
     * @param status    null=全部；OPEN/DOING/DONE/DROPPED
     * @param owner     null=全部
     * @param overdue   true=只看已逾期且未完成
     */
    public List<TicketActionItem> findActionItems(String status, String owner, boolean overdue) {
        StringBuilder sql = new StringBuilder("SELECT * FROM sys_postmortem_action_item WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = ?");
            params.add(status.trim().toUpperCase());
        }
        if (owner != null && !owner.isBlank()) {
            sql.append(" AND owner = ?");
            params.add(owner.trim());
        }
        if (overdue) {
            sql.append(" AND status IN ('OPEN','DOING') AND due_date IS NOT NULL AND due_date < CURRENT_DATE");
        }
        sql.append(" ORDER BY due_date NULLS LAST, create_time");
        return jdbcTemplate.query(sql.toString(), ITEM_MAPPER, params.toArray());
    }

    /** 新建改进项 */
    public Long insertActionItem(TicketActionItem item) {
        String sql = """
            INSERT INTO sys_postmortem_action_item (postmortem_id, ticket_id, content, owner, due_date, status)
            VALUES (?, ?, ?, ?, ?, ?)
            """;
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(conn -> {
            PreparedStatement ps = conn.prepareStatement(sql, new String[]{"id"});
            ps.setLong(1, item.getPostmortemId());
            ps.setString(2, item.getTicketId());
            ps.setString(3, item.getContent());
            ps.setString(4, item.getOwner());
            if (item.getDueDate() != null) ps.setDate(5, java.sql.Date.valueOf(item.getDueDate()));
            else ps.setNull(5, java.sql.Types.DATE);
            ps.setString(6, item.getStatus() != null ? item.getStatus() : "OPEN");
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key != null ? key.longValue() : null;
    }

    /** 更新改进项状态 */
    public int updateActionItemStatus(long id, String status) {
        String sql = """
            UPDATE sys_postmortem_action_item
               SET status = ?, update_time = CURRENT_TIMESTAMP
             WHERE id = ?
            """;
        return jdbcTemplate.update(sql, status, id);
    }
}
