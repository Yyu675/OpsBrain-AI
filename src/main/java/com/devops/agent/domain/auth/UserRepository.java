package com.devops.agent.domain.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 用户仓储（方向三：真实鉴权）
 *
 * <p>JdbcTemplate 直查，与项目既有 Repository 风格一致。</p>
 *
 * @author OpsBrain AI
 * @since 2026-08-20
 */
@Repository
public class UserRepository {

    private final JdbcTemplate jdbcTemplate;

    public UserRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 按登录名查询（登录校验用）。不存在返回 empty。 */
    public Optional<User> findByUsername(String username) {
        String sql = "SELECT * FROM sys_user WHERE username = ?";
        return jdbcTemplate.query(sql, new UserRowMapper(), username).stream().findFirst();
    }

    /** 按 id 查询（token 校验后取当前用户用）。 */
    public Optional<User> findById(Long id) {
        String sql = "SELECT * FROM sys_user WHERE id = ?";
        return jdbcTemplate.query(sql, new UserRowMapper(), id).stream().findFirst();
    }

    /** 用户数（种子初始化判断用） */
    public long count() {
        Long c = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sys_user", Long.class);
        return c != null ? c : 0L;
    }

    /**
     * 插入用户（种子初始化用）。password 必须已是 BCrypt 哈希。
     *
     * @return 受影响行数
     */
    public int insert(User u) {
        String sql = """
            INSERT INTO sys_user (username, password, display_name, role, status, create_time, update_time)
            VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """;
        return jdbcTemplate.update(sql, u.getUsername(), u.getPassword(),
                u.getDisplayName(), u.getRole(), u.getStatus());
    }

    /** 更新末次登录时刻 */
    public void updateLastLogin(Long id, LocalDateTime at) {
        jdbcTemplate.update(
                "UPDATE sys_user SET last_login_at = ?, update_time = CURRENT_TIMESTAMP WHERE id = ?",
                at, id);
    }

    private static class UserRowMapper implements RowMapper<User> {
        @Override
        public User mapRow(ResultSet rs, int rowNum) throws SQLException {
            User u = new User();
            u.setId(rs.getLong("id"));
            u.setUsername(rs.getString("username"));
            u.setPassword(rs.getString("password"));
            u.setDisplayName(rs.getString("display_name"));
            u.setRole(rs.getString("role"));
            u.setStatus(rs.getString("status"));
            var last = rs.getTimestamp("last_login_at");
            if (last != null) u.setLastLoginAt(last.toLocalDateTime());
            var ct = rs.getTimestamp("create_time");
            if (ct != null) u.setCreateTime(ct.toLocalDateTime());
            var ut = rs.getTimestamp("update_time");
            if (ut != null) u.setUpdateTime(ut.toLocalDateTime());
            return u;
        }
    }
}
