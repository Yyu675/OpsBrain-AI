package com.devops.agent.domain.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * AI 渠道能力探测结果仓储（V5）。
 *
 * <h3>为什么单独一张表</h3>
 * 能力是「机器实测的」结果，与 {@code sys_ai_channel} 的「人配的」权威配置职责不同：
 * <ul>
 *   <li>探测是真实 API 调用（计费 + 秒级延迟），结果必须落库复用，不能每次打开页面重探；</li>
 *   <li>配置可以反复编辑但没实测过，能力是「最近一次实测时的真相」——两者天然解耦。</li>
 * </ul>
 *
 * <h3>三态语义</h3>
 * 每个能力项三态：{@code SUPPORTED}（实测支持）/ {@code UNSUPPORTED}（明确返回不支持）/
 * {@code UNKNOWN}（探测失败：超时/限流/网络，<b>不代表不支持</b>）。
 * 存储为 JSON 字符串，读写由仓储完成（业务只传/读 record）。
 */
@Repository
public class AiChannelCapabilityRepository {

    private static final Logger log = LoggerFactory.getLogger(AiChannelCapabilityRepository.class);

    private final JdbcTemplate jdbcTemplate;

    public AiChannelCapabilityRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 覆盖写：按渠道 upsert 最新探测结果。 */
    public void save(String channelKey, String capabilitiesJson) {
        jdbcTemplate.update("""
                INSERT INTO sys_ai_channel_capability (channel_key, capabilities_json, probed_at)
                VALUES (?, ?, CURRENT_TIMESTAMP)
                ON CONFLICT (channel_key) DO UPDATE SET
                    capabilities_json = EXCLUDED.capabilities_json,
                    probed_at        = EXCLUDED.probed_at
                """, channelKey, capabilitiesJson);
    }

    /** 读某渠道最近一次探测结果（无记录 = 从未实测）。 */
    public Optional<CapabilityRow> findByChannel(String channelKey) {
        var rows = jdbcTemplate.query(
                "SELECT capabilities_json, probed_at FROM sys_ai_channel_capability WHERE channel_key = ?",
                (rs, i) -> new CapabilityRow(rs.getString("capabilities_json"),
                        toLocalDateTime(rs.getTimestamp("probed_at"))),
                channelKey);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public record CapabilityRow(String capabilitiesJson, LocalDateTime probedAt) {}

    private static LocalDateTime toLocalDateTime(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }
}