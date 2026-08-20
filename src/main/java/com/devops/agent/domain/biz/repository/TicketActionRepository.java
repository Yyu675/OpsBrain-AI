package com.devops.agent.domain.biz.repository;

import com.devops.agent.domain.biz.entity.TicketAction;
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
import java.time.LocalDateTime;
import java.util.List;

/**
 * 工单处置动作数据访问层（B2）
 *
 * @author OpsBrain AI
 * @since 2026-08-18
 */
@Repository
public class TicketActionRepository {

    private static final Logger log = LoggerFactory.getLogger(TicketActionRepository.class);

    private final JdbcTemplate jdbcTemplate;

    public TicketActionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final RowMapper<TicketAction> MAPPER = (rs, rowNum) -> {
        TicketAction a = new TicketAction();
        a.setId(rs.getLong("id"));
        a.setTicketId(rs.getString("ticket_id"));
        a.setActionType(rs.getString("action_type"));
        a.setSummary(rs.getString("summary"));
        a.setDetail(rs.getString("detail"));
        a.setOperator(rs.getString("operator"));
        boolean eff = rs.getBoolean("effective");
        a.setEffective(rs.wasNull() ? null : eff);
        a.setStartedAt(rs.getObject("started_at", LocalDateTime.class));
        a.setFinishedAt(rs.getObject("finished_at", LocalDateTime.class));
        a.setCreateTime(rs.getObject("create_time", LocalDateTime.class));
        return a;
    };

    /**
     * 插入处置动作，回填自增主键
     */
    public Long insert(TicketAction action) {
        String sql = """
            INSERT INTO sys_ticket_action (ticket_id, action_type, summary, detail, operator, effective, started_at, finished_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """;

        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            // 显式指定只返回 id 列（6.12 教训：RETURN_GENERATED_KEYS 返回全部列导致 getKey 抛异常）
            PreparedStatement ps = connection.prepareStatement(sql, new String[]{"id"});
            ps.setString(1, action.getTicketId());
            ps.setString(2, action.getActionType());
            ps.setString(3, action.getSummary());
            ps.setString(4, action.getDetail());
            ps.setString(5, action.getOperator());
            if (action.getEffective() != null) {
                ps.setBoolean(6, action.getEffective());
            } else {
                ps.setNull(6, java.sql.Types.BOOLEAN);
            }
            ps.setObject(7, action.getStartedAt());
            ps.setObject(8, action.getFinishedAt());
            return ps;
        }, keyHolder);

        Number key = keyHolder.getKey();
        Long id = key != null ? key.longValue() : null;
        log.info("✅ [ActionRepo] 处置动作已入库 | ticketId={} | type={} | effective={} | id={}",
                action.getTicketId(), action.getActionType(), action.getEffective(), id);
        return id;
    }

    /**
     * 按工单查询处置动作（时间正序，供时间线展示）
     */
    public List<TicketAction> findByTicketId(String ticketId) {
        String sql = "SELECT * FROM sys_ticket_action WHERE ticket_id = ? ORDER BY create_time, id";
        return jdbcTemplate.query(sql, MAPPER, ticketId);
    }

    /**
     * 工单物理删除时级联清理
     */
    public int deleteByTicketId(String ticketId) {
        String sql = "DELETE FROM sys_ticket_action WHERE ticket_id = ?";
        int rows = jdbcTemplate.update(sql, ticketId);
        if (rows > 0) {
            log.info("🗑️ [ActionRepo] 已清理处置动作 | ticketId={} | rows={}", ticketId, rows);
        }
        return rows;
    }
}
