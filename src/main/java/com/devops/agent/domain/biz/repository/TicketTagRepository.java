package com.devops.agent.domain.biz.repository;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工单标签数据访问层
 * <p>
 * 标签此前由前端根据 module 编造（每张工单都贴「生产环境」），
 * 本仓储使其成为真实持久化数据。
 * </p>
 *
 * @author OpsBrain AI
 * @since 2026-08-09
 */
@Slf4j
@Repository
public class TicketTagRepository {

    /** 单标签长度上限，与 DDL 的 VARCHAR(64) 对齐 */
    private static final int MAX_TAG_LENGTH = 64;

    /** 单工单标签数上限，防止无节制堆积导致 UI 崩坏 */
    private static final int MAX_TAGS_PER_TICKET = 20;

    private final JdbcTemplate jdbcTemplate;

    public TicketTagRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 全量替换工单标签
     * <p>
     * 采用「先删后插」而非差量更新：标签数量少（上限 20），
     * 差量计算的复杂度收益不及代码清晰度损失。
     * </p>
     *
     * @param ticketId 工单号
     * @param tags     标签列表，null 或空表示清空
     * @return 实际写入的标签数
     */
    public int replaceTags(String ticketId, List<String> tags) {
        deleteByTicketId(ticketId);

        List<String> normalized = normalize(tags);
        if (normalized.isEmpty()) {
            return 0;
        }

        // ON CONFLICT DO NOTHING 依赖 uk_ticket_tag 唯一索引，
        // 兜住归一化后仍可能重复的边界情况
        String sql = """
            INSERT INTO sys_ticket_tag (ticket_id, tag)
            VALUES (?, ?)
            ON CONFLICT (ticket_id, tag) DO NOTHING
            """;

        int written = 0;
        for (String tag : normalized) {
            try {
                written += jdbcTemplate.update(sql, ticketId, tag);
            } catch (Exception e) {
                log.warn("⚠️ [TagRepo] 标签写入失败，跳过 | ticketId={} | tag={} | {}",
                        ticketId, tag, e.getMessage());
            }
        }

        log.debug("🏷️ [TagRepo] 标签已替换 | ticketId={} | 写入={}/{}",
                ticketId, written, normalized.size());
        return written;
    }

    /**
     * 查询单工单标签
     */
    public List<String> findByTicketId(String ticketId) {
        String sql = "SELECT tag FROM sys_ticket_tag WHERE ticket_id = ? ORDER BY id";
        try {
            return jdbcTemplate.queryForList(sql, String.class, ticketId);
        } catch (Exception e) {
            log.warn("⚠️ [TagRepo] 查询标签失败 | ticketId={} | {}", ticketId, e.getMessage());
            return List.of();
        }
    }

    /**
     * 批量查询多工单标签
     * <p>列表页一次加载，避免 N+1 查询。</p>
     *
     * @return ticketId → 标签列表
     */
    public Map<String, List<String>> findByTicketIds(List<String> ticketIds) {
        if (ticketIds == null || ticketIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", ticketIds.stream().map(x -> "?").toList());
        String sql = "SELECT ticket_id, tag FROM sys_ticket_tag WHERE ticket_id IN ("
                + placeholders + ") ORDER BY ticket_id, id";

        Map<String, List<String>> result = new HashMap<>();
        try {
            jdbcTemplate.query(sql, rs -> {
                result.computeIfAbsent(rs.getString("ticket_id"), k -> new ArrayList<>())
                        .add(rs.getString("tag"));
            }, ticketIds.toArray());
        } catch (Exception e) {
            log.warn("⚠️ [TagRepo] 批量查询标签失败 | {}", e.getMessage());
        }
        return result;
    }

    /**
     * 查询热门标签（按使用次数降序）
     * <p>供前端输入时的历史标签建议，减少同义异形标签。</p>
     *
     * @param limit 返回数量上限
     * @return 标签 → 使用次数，按次数降序
     */
    public Map<String, Integer> findHotTags(int limit) {
        String sql = """
            SELECT tag, COUNT(*) AS cnt
              FROM sys_ticket_tag
             GROUP BY tag
             ORDER BY cnt DESC, tag
             LIMIT ?
            """;
        Map<String, Integer> result = new LinkedHashMap<>();   // 保序
        try {
            jdbcTemplate.query(sql, rs -> {
                result.put(rs.getString("tag"), rs.getInt("cnt"));
            }, limit);
        } catch (Exception e) {
            log.warn("⚠️ [TagRepo] 查询热门标签失败 | {}", e.getMessage());
        }
        return result;
    }

    /**
     * 按标签反查工单号
     * <p>供标签筛选使用。多标签为「全部匹配」语义（AND）。</p>
     *
     * @param tags 标签列表
     * @return 同时含有全部标签的工单号
     */
    public List<String> findTicketIdsByAllTags(List<String> tags) {
        List<String> normalized = normalize(tags);
        if (normalized.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", normalized.stream().map(x -> "?").toList());
        // HAVING COUNT = 标签数 实现 AND 语义：
        // 工单必须命中全部标签才计入
        String sql = "SELECT ticket_id FROM sys_ticket_tag WHERE tag IN (" + placeholders + ")"
                + " GROUP BY ticket_id HAVING COUNT(DISTINCT tag) = ?";

        Object[] args = new Object[normalized.size() + 1];
        for (int i = 0; i < normalized.size(); i++) {
            args[i] = normalized.get(i);
        }
        args[normalized.size()] = normalized.size();

        try {
            return jdbcTemplate.queryForList(sql, String.class, args);
        } catch (Exception e) {
            log.warn("⚠️ [TagRepo] 按标签查工单失败 | {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 删除工单的全部标签
     * <p>工单物理删除时级联清理。</p>
     */
    public int deleteByTicketId(String ticketId) {
        try {
            return jdbcTemplate.update("DELETE FROM sys_ticket_tag WHERE ticket_id = ?", ticketId);
        } catch (Exception e) {
            log.warn("⚠️ [TagRepo] 清理标签失败 | ticketId={} | {}", ticketId, e.getMessage());
            return 0;
        }
    }

    /**
     * 标签归一化
     * <p>
     * 去空白、去空值、截断超长、去重、限量。
     * 保留大小写——「K8s」是产品官方写法，强制小写反而失真。
     * </p>
     */
    private List<String> normalize(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String raw : tags) {
            if (raw == null) continue;
            String t = raw.trim();
            if (t.isEmpty()) continue;
            if (t.length() > MAX_TAG_LENGTH) {
                t = t.substring(0, MAX_TAG_LENGTH);
            }
            if (!out.contains(t)) {
                out.add(t);
            }
            if (out.size() >= MAX_TAGS_PER_TICKET) {
                log.debug("🏷️ [TagRepo] 标签数达上限 {}，其余忽略", MAX_TAGS_PER_TICKET);
                break;
            }
        }
        return out;
    }
}