package com.devops.agent.domain.biz.repository;

import com.devops.agent.domain.biz.entity.TicketAttachment;
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
 * 工单附件元数据数据访问层
 *
 * @author OpsBrain AI
 * @since 2026-08-09
 */
@Slf4j
@Repository
public class TicketAttachmentRepository {

    private final JdbcTemplate jdbcTemplate;

    public TicketAttachmentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 插入附件元数据，回填自增主键
     */
    public Long insert(TicketAttachment a) {
        String sql = """
            INSERT INTO sys_ticket_attachment
                (ticket_id, bucket, object_key, original_name, content_type,
                 size_bytes, sha256, uploader, create_time)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            // 显式指定只返回 id：PostgreSQL 的 RETURN_GENERATED_KEYS
            // 会返回全部列，使 getKey() 因"多个键"抛异常
            PreparedStatement ps = connection.prepareStatement(sql, new String[]{"id"});
            ps.setString(1, a.getTicketId());
            ps.setString(2, a.getBucket());
            ps.setString(3, a.getObjectKey());
            ps.setString(4, a.getOriginalName());
            ps.setString(5, a.getContentType());
            ps.setLong(6, a.getSizeBytes() != null ? a.getSizeBytes() : 0L);
            ps.setString(7, a.getSha256());
            ps.setString(8, a.getUploader());
            ps.setObject(9, a.getCreateTime() != null ? a.getCreateTime() : LocalDateTime.now());
            return ps;
        }, keyHolder);

        Number key = keyHolder.getKey();
        Long id = key != null ? key.longValue() : null;
        log.info("✅ [AttachRepo] 附件元数据已入库 | ticketId={} | id={} | name={} | size={}",
                a.getTicketId(), id, a.getOriginalName(), a.getSizeBytes());
        return id;
    }

    /**
     * 按工单查附件（上传时间正序）
     */
    public List<TicketAttachment> findByTicketId(String ticketId) {
        String sql = "SELECT * FROM sys_ticket_attachment WHERE ticket_id = ? ORDER BY create_time, id";
        try {
            return jdbcTemplate.query(sql, new AttachmentRowMapper(), ticketId);
        } catch (Exception e) {
            log.warn("⚠️ [AttachRepo] 查询附件失败 | ticketId={} | {}", ticketId, e.getMessage());
            return List.of();
        }
    }

    /**
     * 按主键查询
     */
    public TicketAttachment findById(Long id) {
        String sql = "SELECT * FROM sys_ticket_attachment WHERE id = ?";
        try {
            List<TicketAttachment> list = jdbcTemplate.query(sql, new AttachmentRowMapper(), id);
            return list.isEmpty() ? null : list.get(0);
        } catch (Exception e) {
            log.warn("⚠️ [AttachRepo] 查询附件失败 | id={} | {}", id, e.getMessage());
            return null;
        }
    }

    /**
     * 统计工单附件数
     * <p>供上限校验使用。</p>
     */
    public int countByTicketId(String ticketId) {
        String sql = "SELECT COUNT(*) FROM sys_ticket_attachment WHERE ticket_id = ?";
        try {
            Integer n = jdbcTemplate.queryForObject(sql, Integer.class, ticketId);
            return n != null ? n : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 按内容哈希查重（同工单内）
     * <p>避免同一文件被重复上传占用存储。</p>
     */
    public TicketAttachment findByTicketIdAndSha256(String ticketId, String sha256) {
        if (sha256 == null || sha256.isBlank()) return null;
        String sql = "SELECT * FROM sys_ticket_attachment WHERE ticket_id = ? AND sha256 = ? LIMIT 1";
        try {
            List<TicketAttachment> list = jdbcTemplate.query(sql, new AttachmentRowMapper(), ticketId, sha256);
            return list.isEmpty() ? null : list.get(0);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 删除单个附件元数据
     */
    public int deleteById(Long id) {
        try {
            return jdbcTemplate.update("DELETE FROM sys_ticket_attachment WHERE id = ?", id);
        } catch (Exception e) {
            log.warn("⚠️ [AttachRepo] 删除附件元数据失败 | id={} | {}", id, e.getMessage());
            return 0;
        }
    }

    /**
     * 删除工单全部附件元数据
     * <p>返回被删记录，供调用方清理 MinIO 中的对象。</p>
     */
    public List<TicketAttachment> deleteByTicketId(String ticketId) {
        List<TicketAttachment> existing = findByTicketId(ticketId);
        if (existing.isEmpty()) return List.of();
        try {
            jdbcTemplate.update("DELETE FROM sys_ticket_attachment WHERE ticket_id = ?", ticketId);
            log.info("🗑️ [AttachRepo] 已清理工单附件元数据 | ticketId={} | count={}",
                    ticketId, existing.size());
        } catch (Exception e) {
            log.warn("⚠️ [AttachRepo] 清理附件元数据失败 | ticketId={} | {}", ticketId, e.getMessage());
            return List.of();
        }
        return existing;
    }

    // ==================== RowMapper ====================

    private static class AttachmentRowMapper implements RowMapper<TicketAttachment> {
        @Override
        public TicketAttachment mapRow(ResultSet rs, int rowNum) throws SQLException {
            TicketAttachment a = new TicketAttachment();
            a.setId(rs.getLong("id"));
            a.setTicketId(rs.getString("ticket_id"));
            a.setBucket(rs.getString("bucket"));
            a.setObjectKey(rs.getString("object_key"));
            a.setOriginalName(rs.getString("original_name"));
            a.setContentType(rs.getString("content_type"));
            a.setSizeBytes(rs.getLong("size_bytes"));
            a.setSha256(rs.getString("sha256"));
            a.setUploader(rs.getString("uploader"));
            a.setCreateTime(rs.getObject("create_time", LocalDateTime.class));
            return a;
        }
    }
}