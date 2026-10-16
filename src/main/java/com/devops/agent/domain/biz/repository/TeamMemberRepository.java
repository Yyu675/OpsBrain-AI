package com.devops.agent.domain.biz.repository;

import com.devops.agent.domain.biz.entity.TeamMember;
import com.devops.agent.domain.biz.entity.TicketEnums;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 运维团队成员数据访问层
 * <p>
 * 工单负责人（assignee）名录的唯一来源，供 {@code GET /api/v1/users} 下发。
 * </p>
 *
 * @author OpsBrain AI
 * @since 2026-08-17
 */
@Repository
public class TeamMemberRepository {

    private static final Logger log = LoggerFactory.getLogger(TeamMemberRepository.class);

    private final JdbcTemplate jdbcTemplate;

    public TeamMemberRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final RowMapper<TeamMember> ROW_MAPPER = (ResultSet rs, int rowNum) -> {
        TeamMember m = new TeamMember();
        m.setId(rs.getLong("id"));
        m.setName(rs.getString("name"));
        m.setEmail(rs.getString("email"));
        m.setRole(rs.getString("role"));
        m.setTitle(rs.getString("title"));
        m.setStatus(rs.getString("status"));
        m.setSortOrder(rs.getInt("sort_order"));
        if (rs.getTimestamp("create_time") != null) {
            m.setCreateTime(rs.getTimestamp("create_time").toLocalDateTime());
        }
        if (rs.getTimestamp("update_time") != null) {
            m.setUpdateTime(rs.getTimestamp("update_time").toLocalDateTime());
        }
        return m;
    };

    /**
     * 查询成员名录
     *
     * @param includeDisabled 是否包含已停用成员。false 时只返回 ACTIVE
     * @return 按 sort_order、id 排序的成员列表
     */
    public List<TeamMember> findAll(boolean includeDisabled) {
        String sql = includeDisabled
                ? "SELECT * FROM sys_team_member ORDER BY sort_order ASC, id ASC"
                : "SELECT * FROM sys_team_member WHERE status = 'ACTIVE' ORDER BY sort_order ASC, id ASC";
        try {
            return jdbcTemplate.query(sql, ROW_MAPPER);
        } catch (Exception e) {
            log.error("❌ [TeamMemberRepository] 查询成员名录失败: {}", e.getMessage(), e);
            throw e;
        }
    }

    /**
     * 统计各成员当前进行中的工单数（PENDING / PROCESSING）
     * <p>
     * 一次聚合查询解决 N+1：按 assignee 分组返回，调用方按姓名装填。
     * 已解决/已关闭/已作废不计入负载——它们不再占用处理人精力。
     * </p>
     *
     * @return assignee 姓名 → 进行中工单数
     */
    public Map<String, Integer> countActiveTicketsByAssignee() {
        String sql = """
            SELECT TRIM(assignee) AS assignee, COUNT(*) AS cnt
              FROM sys_devops_ticket
             WHERE assignee IS NOT NULL AND TRIM(assignee) <> ''
               AND status IN (?, ?)
             GROUP BY TRIM(assignee)
            """;
        Map<String, Integer> result = new HashMap<>();
        try {
            jdbcTemplate.query(sql,
                    rs -> {
                        result.put(rs.getString("assignee"), rs.getInt("cnt"));
                    },
                    TicketEnums.Status.PENDING, TicketEnums.Status.PROCESSING);
        } catch (Exception e) {
            // 负载是选人时的辅助信息，统计失败不应让整个名录接口不可用
            log.warn("⚠️ [TeamMemberRepository] 统计成员工单负载失败，降级为不展示负载: {}", e.getMessage());
        }
        return result;
    }

    /**
     * 查询工单表中存在但名录里没有的负责人姓名
     * <p>
     * 历史工单可能指派给已从名录移除（或从未入册）的人。若选人列表不含这些姓名，
     * 前端下拉框会选不中当前负责人，显示为空——用户会以为工单未指派。
     * 故名录接口需把这些「历史负责人」一并下发。
     * </p>
     *
     * @return 仅存在于工单表的负责人姓名（已排除「待分配」哨兵值）
     */
    public List<String> findLegacyAssigneeNames() {
        String sql = """
            SELECT MIN(TRIM(t.assignee)) AS name
              FROM sys_devops_ticket t
             WHERE t.assignee IS NOT NULL
               AND TRIM(t.assignee) <> ''
               AND TRIM(t.assignee) <> '待分配'
               AND NOT EXISTS (
                   SELECT 1 FROM sys_team_member m
                    WHERE LOWER(m.name) = LOWER(TRIM(t.assignee))
               )
             GROUP BY LOWER(TRIM(t.assignee))
             ORDER BY 1 ASC
            """;
        try {
            return jdbcTemplate.queryForList(sql, String.class);
        } catch (Exception e) {
            log.warn("⚠️ [TeamMemberRepository] 查询历史负责人失败，降级为仅返回名录: {}", e.getMessage());
            return new ArrayList<>();
        }
    }
}
