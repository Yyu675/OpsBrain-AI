package com.devops.agent.domain.biz.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * sys_knowledge_boost 仓储（S2-3 反馈回流，路线图 §6.3 2-3.6）。
 * <p>
 * boost 公式（路线图原文）：{@code boost = 1 + log(1 + helpfulCount) - 0.5 × wrongCount}。
 * <ul>
 *   <li>boost=1.0 是「未反馈/刚入库 chunk」的中性值；&gt;1 提权，&lt;1 降权；</li>
 *   <li>WRONG 的负增长力度为每条 −0.5：让错误反馈快速到达别的检索面，
 *       但又不让第一条坏反馈就把一个 chunk 打成永久负资产。</li>
 * </ul>
 * </p>
 */
@Repository
public class KnowledgeBoostRepository {

    private final JdbcTemplate jdbcTemplate;

    public KnowledgeBoostRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 按 chunk_id 批量取 boost 系数（检索引用的光环入口）。
     *
     * @param chunkIds 要管引用的 chunk_id 集合
     * @return 有值只有 boost；未入档的 chunk_id 不在 Map 中视为 boost=1.0
     */
    public Map<Long, Double> boostBatch(List<Long> chunkIds) {
        Map<Long, Double> out = new HashMap<>();
        if (chunkIds == null || chunkIds.isEmpty()) {
            return out;
        }
        String placeholders = String.join(",", chunkIds.stream().map(id -> "?").toList());
        String sql = "SELECT chunk_id, helpful_count, wrong_count FROM sys_knowledge_boost WHERE chunk_id IN (" + placeholders + ")";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, chunkIds.toArray());
        for (Map<String, Object> row : rows) {
            long id = ((Number) row.get("chunk_id")).longValue();
            int helpful = ((Number) row.get("helpful_count")).intValue();
            int wrong = ((Number) row.get("wrong_count")).intValue();
            double boost = 1.0 + log1p(helpful) - 0.5 * wrong;
            out.put(id, boost);
        }
        return out;
    }

    /**
     * 记一条反馈（upsert 语义：有即增量累加否则创建行）。
     *
     * @param chunkId 影响用的 chunk_id
     * @param verdict HELPFUL → +1；WRONG → +wrong_count
     */
    public void recordFeedback(Long chunkId, String verdict) {
        String sql = """
                INSERT INTO sys_knowledge_boost (chunk_id, helpful_count, wrong_count)
                VALUES (?, ?, ?)
                ON CONFLICT (chunk_id) DO UPDATE
                SET helpful_count = sys_knowledge_boost.helpful_count + excluded.helpful_count,
                    wrong_count = sys_knowledge_boost.wrong_count + excluded.wrong_count,
                    updated_at = CURRENT_TIMESTAMP
                """;
        int helpful = "HELPFUL".equals(verdict) ? 1 : 0;
        int wrong = "WRONG".equals(verdict) ? 1 : 0;
        jdbcTemplate.update(sql, chunkId, helpful, wrong);
    }

    /**
     * 公式内部（Natural Log，非 log10——`log(1+n)` 的量级更合预期）。
     */
    private static double log1p(double n) {
        return Math.log(n + 1);
    }
}
