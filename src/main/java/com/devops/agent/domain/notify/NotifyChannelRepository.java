package com.devops.agent.domain.notify;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 通知渠道配置仓储（批89 · V16 sys_notify_channel）。
 *
 * <p>只做存取，不做语义判定——「行存在即 DB 权威、无行回落 yml」
 * 的裁决在 {@link NotifyChannelService#effective} 一处。</p>
 */
@Repository
public class NotifyChannelRepository {

    private static final String COLS = "channel_key, enabled, webhook_enc, secret_enc, updated_by, update_time";
    private static final String FIND_BY_KEY =
            "SELECT " + COLS + " FROM sys_notify_channel WHERE channel_key = ?";
    private static final String UPSERT = """
            INSERT INTO sys_notify_channel (channel_key, enabled, webhook_enc, secret_enc, updated_by, update_time)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (channel_key) DO UPDATE SET
              enabled = EXCLUDED.enabled,
              webhook_enc = EXCLUDED.webhook_enc,
              secret_enc = EXCLUDED.secret_enc,
              updated_by = EXCLUDED.updated_by,
              update_time = EXCLUDED.update_time
            """;

    /** 渠道配置行（密文原样——解密是 Service 的职责）。 */
    public record NotifyChannelRow(
            String channelKey, boolean enabled, String webhookEnc, String secretEnc,
            String updatedBy, LocalDateTime updateTime) {}

    private final JdbcTemplate jdbc;

    public NotifyChannelRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<NotifyChannelRow> findByKey(String channelKey) {
        var rows = jdbc.query(FIND_BY_KEY, (rs, i) -> new NotifyChannelRow(
                rs.getString("channel_key"),
                rs.getBoolean("enabled"),
                rs.getString("webhook_enc"),
                rs.getString("secret_enc"),
                rs.getString("updated_by"),
                toLocalDateTime(rs.getTimestamp("update_time"))),
                channelKey);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** upsert 整行（语义：调用即「该渠道转入 DB 管理」，行存在覆盖 yml/env）。 */
    public void upsert(String channelKey, boolean enabled, String webhookEnc,
                       String secretEnc, String updatedBy) {
        jdbc.update(UPSERT, channelKey, enabled,
                webhookEnc == null ? "" : webhookEnc,
                secretEnc == null ? "" : secretEnc,
                updatedBy, Timestamp.valueOf(LocalDateTime.now()));
    }

    private static LocalDateTime toLocalDateTime(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }
}
