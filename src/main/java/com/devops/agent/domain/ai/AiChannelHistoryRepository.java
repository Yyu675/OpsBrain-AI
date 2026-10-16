package com.devops.agent.domain.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * AI 渠道变更历史仓储（V5）。
 *
 * <h3>职责</h3>
 * <ul>
 *   <li>{@link #snapshot}：变更前把旧行整行写入历史表（谁调用谁负责先快照）；</li>
 *   <li>{@link #findByChannel}：按渠道拉历史（新→旧，供「变更历史」弹窗）；</li>
 *   <li>{@link #findById}：回滚时按 id 定位某一历史版本。</li>
 * </ul>
 *
 * <h3>保留策略</h3>
 * 历史只增不删（审计语义）。若未来行数失控，另起生命周期任务清理，
 * 不在本仓储提供删除口——历史表的价值就是「不可改」。
 */
@Repository
public class AiChannelHistoryRepository {

    private static final Logger log = LoggerFactory.getLogger(AiChannelHistoryRepository.class);

    private final JdbcTemplate jdbcTemplate;

    public AiChannelHistoryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final RowMapper<AiChannelHistory> MAPPER = (ResultSet rs, int rowNum) ->
            new AiChannelHistory(
                    rs.getLong("id"),
                    rs.getString("channel_key"),
                    rs.getString("base_url"),
                    rs.getString("api_key_enc"),
                    rs.getString("key_masked"),
                    rs.getString("turbo_model"),
                    rs.getString("reasoner_model"),
                    rs.getString("model"),
                    (Integer) rs.getObject("dimension"),
                    rs.getString("status"),
                    rs.getString("fallback_base_url"),
                    rs.getString("fallback_model"),
                    rs.getString("fallback_api_key_enc"),
                    rs.getString("fallback_key_masked"),
                    toLocalDateTime(rs.getTimestamp("changed_at")),
                    rs.getString("changed_by"),
                    rs.getString("change_note")
            );

    /** 变更前快照旧行。返回新历史行 id（供变更说明引用，如「回滚到 #5」）。 */
    public Long snapshot(AiChannelHistory h) {
        String sql = """
                INSERT INTO sys_ai_channel_history
                    (channel_key, base_url, api_key_enc, key_masked,
                     turbo_model, reasoner_model, model, dimension, status,
                     fallback_base_url, fallback_model, fallback_api_key_enc, fallback_key_masked,
                     changed_by, change_note)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                RETURNING id
                """;
        Long id = jdbcTemplate.queryForObject(sql, Long.class,
                h.channelKey(), h.baseUrl(), h.apiKeyEnc(), h.maskedKey(),
                h.turboModel(), h.reasonerModel(), h.model(), h.dimension(), h.status(),
                h.fallbackBaseUrl(), h.fallbackModel(), h.fallbackApiKeyEnc(), h.fallbackMaskedKey(),
                h.changedBy(), h.changeNote());
        log.debug("[AiChannelHistory] 已快照渠道 {} 变更前状态 | historyId={}", h.channelKey(), id);
        return id;
    }

    /** 按渠道拉历史（新→旧）。limit 防御深分页——历史弹窗只看最近 N 条。 */
    public List<AiChannelHistory> findByChannel(String channelKey, int limit) {
        return jdbcTemplate.query(
                "SELECT * FROM sys_ai_channel_history WHERE channel_key = ? ORDER BY changed_at DESC, id DESC LIMIT ?",
                MAPPER, channelKey, limit);
    }

    /** 按 id 定位历史版本（回滚用）。 */
    public Optional<AiChannelHistory> findById(long id) {
        List<AiChannelHistory> rows = jdbcTemplate.query(
                "SELECT * FROM sys_ai_channel_history WHERE id = ?", MAPPER, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    private static LocalDateTime toLocalDateTime(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }
}
