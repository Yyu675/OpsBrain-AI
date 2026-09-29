package com.devops.agent.domain.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * AI 渠道配置仓储（阶段A-P0）。JdbcTemplate + RowMapper，对齐项目现有风格。
 * <p>
 * P0 阶段仅承载「启动镜像」的写入与「只读展示」的查询；
 * 真正的 CRUD/热更新在 P1/P2 扩展。
 * </p>
 */
@Repository
public class AiChannelRepository {

    private static final Logger log = LoggerFactory.getLogger(AiChannelRepository.class);

    private final JdbcTemplate jdbcTemplate;

    public AiChannelRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final RowMapper<AiChannel> MAPPER = (ResultSet rs, int rowNum) ->
            new AiChannel(
                    rs.getString("channel_key"),
                    rs.getString("base_url"),
                    rs.getString("api_key_enc"),
                    rs.getString("key_masked"),
                    rs.getString("turbo_model"),
                    rs.getString("reasoner_model"),
                    rs.getString("model"),
                    (Integer) rs.getObject("dimension"),
                    rs.getString("status"),
                    toLocalDateTime(rs.getTimestamp("updated_at")),
                    rs.getString("fallback_base_url"),
                    rs.getString("fallback_model"),
                    rs.getString("fallback_api_key_enc"),
                    rs.getString("fallback_key_masked"),
                    rs.getString("protocol"),
                    rs.getString("provider")
            );

    /** 目标列清单（写入时逐列 upsert）。 */
    private static final String COLS =
            "channel_key, base_url, api_key_enc, key_masked, turbo_model, reasoner_model, model, dimension, status, updated_at,"
                    + " fallback_base_url, fallback_model, fallback_api_key_enc, fallback_key_masked, protocol, provider";

    /**
     * 幂等 upsert 单条渠道。
     * <p>
     * P1 起 DB 是权威源：本方法只用于<b>空库种子</b>
     * （{@link com.devops.agent.infrastructure.ai.AiChannelMirror} 仅在
     * 渠道不存在时调用）。已有行不得被 yml 覆盖，否则 UI 编辑会在重启后丢失。
     * </p>
     */
    public void upsert(AiChannel ch) {
        String sql = """
                INSERT INTO sys_ai_channel (channel_key, base_url, api_key_enc, key_masked,
                                            turbo_model, reasoner_model, model, dimension, status, updated_at,
                                            fallback_base_url, fallback_model, fallback_api_key_enc, fallback_key_masked,
                                            protocol, provider)
                VALUES (?,?,?,?,?,?,?,?,?, CURRENT_TIMESTAMP, ?,?,?,?,?,?)
                ON CONFLICT (channel_key) DO UPDATE SET
                    base_url             = EXCLUDED.base_url,
                    api_key_enc          = EXCLUDED.api_key_enc,
                    key_masked           = EXCLUDED.key_masked,
                    turbo_model          = EXCLUDED.turbo_model,
                    reasoner_model       = EXCLUDED.reasoner_model,
                    model                = EXCLUDED.model,
                    dimension            = EXCLUDED.dimension,
                    status               = EXCLUDED.status,
                    fallback_base_url    = EXCLUDED.fallback_base_url,
                    fallback_model       = EXCLUDED.fallback_model,
                    fallback_api_key_enc = EXCLUDED.fallback_api_key_enc,
                    fallback_key_masked  = EXCLUDED.fallback_key_masked,
                    protocol             = EXCLUDED.protocol,
                    provider             = EXCLUDED.provider,
                    updated_at           = CURRENT_TIMESTAMP
                """;
        jdbcTemplate.update(sql,
                ch.channelKey(), ch.baseUrl(), ch.apiKeyEnc(), ch.maskedKey(),
                ch.turboModel(), ch.reasonerModel(), ch.model(), ch.dimension(),
                ch.status() == null ? "ACTIVE" : ch.status(),
                ch.fallbackBaseUrl(), ch.fallbackModel(), ch.fallbackApiKeyEnc(), ch.fallbackMaskedKey(),
                ch.protocol() == null ? AiChannel.PROTOCOL_OPENAI_COMPATIBLE : ch.protocol(), ch.provider());
    }

    /** 全量（含非 ACTIVE），用于展示与镜像。 */
    public List<AiChannel> findAll() {
        return jdbcTemplate.query(
                "SELECT " + COLS + " FROM sys_ai_channel ORDER BY channel_key", MAPPER);
    }

    /** 按渠道键查单条。 */
    public Optional<AiChannel> findByKey(String channelKey) {
        List<AiChannel> list = jdbcTemplate.query(
                "SELECT " + COLS + " FROM sys_ai_channel WHERE channel_key = ?", MAPPER, channelKey);
        return list.stream().findFirst();
    }

    /**
     * 编辑更新（P1：DB 权威源）。调用方负责把 patch 与既有行合并后整行写入。
     *
     * @return 受影响行数；0 = 渠道不存在（调用方据此 404，而非静默成功）
     */
    public int updateMerged(AiChannel ch) {
        return jdbcTemplate.update("""
                UPDATE sys_ai_channel SET
                    base_url = ?, api_key_enc = ?, key_masked = ?,
                    turbo_model = ?, reasoner_model = ?, model = ?,
                    dimension = ?, status = ?,
                    fallback_base_url = ?, fallback_model = ?,
                    fallback_api_key_enc = ?, fallback_key_masked = ?,
                    protocol = ?, provider = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE channel_key = ?
                """,
                ch.baseUrl(), ch.apiKeyEnc(), ch.maskedKey(),
                ch.turboModel(), ch.reasonerModel(), ch.model(),
                ch.dimension(), ch.status() == null ? "ACTIVE" : ch.status(),
                ch.fallbackBaseUrl(), ch.fallbackModel(), ch.fallbackApiKeyEnc(), ch.fallbackMaskedKey(),
                ch.protocol() == null ? AiChannel.PROTOCOL_OPENAI_COMPATIBLE : ch.protocol(), ch.provider(),
                ch.channelKey());
    }

    /** 更新状态（启用/停用）。 */
    public void updateStatus(String channelKey, String status) {
        jdbcTemplate.update(
                "UPDATE sys_ai_channel SET status = ?, updated_at = CURRENT_TIMESTAMP WHERE channel_key = ?",
                status, channelKey);
    }

    private static java.time.LocalDateTime toLocalDateTime(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }
}
