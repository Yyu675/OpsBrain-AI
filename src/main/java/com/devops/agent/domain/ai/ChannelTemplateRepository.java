package com.devops.agent.domain.ai;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.util.List;

/**
 * AI 渠道模板仓储（V13 多渠道一键切换）。JdbcTemplate + RowMapper，对齐项目风格。
 * <p>模板只读（预置数据），不提供写接口——模板维护走迁移脚本，不开放运行时增删。</p>
 */
@Repository
public class ChannelTemplateRepository {

    private final JdbcTemplate jdbcTemplate;

    public ChannelTemplateRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final RowMapper<ChannelTemplate> MAPPER = (ResultSet rs, int rowNum) ->
            new ChannelTemplate(
                    rs.getLong("id"),
                    rs.getString("channel_key"),
                    rs.getString("template_name"),
                    rs.getString("provider"),
                    rs.getString("base_url"),
                    rs.getString("protocol"),
                    rs.getString("turbo_model"),
                    rs.getString("reasoner_model"),
                    rs.getString("model"),
                    (Integer) rs.getObject("dimension"),
                    rs.getString("description"),
                    rs.getInt("sort_order"));

    private static final String COLS =
            "id, channel_key, template_name, provider, base_url, protocol, turbo_model, reasoner_model,"
                    + " model, dimension, description, sort_order";

    /** 列出启用模板（可按渠道过滤；channelKey 为 null/blank 时返回全部）。 */
    public List<ChannelTemplate> findEnabled(String channelKey) {
        if (channelKey == null || channelKey.isBlank()) {
            return jdbcTemplate.query(
                    "SELECT " + COLS + " FROM sys_ai_channel_template WHERE enabled = TRUE ORDER BY channel_key, sort_order",
                    MAPPER);
        }
        return jdbcTemplate.query(
                "SELECT " + COLS + " FROM sys_ai_channel_template WHERE enabled = TRUE AND channel_key = ? ORDER BY sort_order",
                MAPPER, channelKey.trim());
    }

    /** 按 id 查单条（应用模板时取配置）。 */
    public java.util.Optional<ChannelTemplate> findById(long id) {
        List<ChannelTemplate> list = jdbcTemplate.query(
                "SELECT " + COLS + " FROM sys_ai_channel_template WHERE id = ? AND enabled = TRUE",
                MAPPER, id);
        return list.stream().findFirst();
    }
}
