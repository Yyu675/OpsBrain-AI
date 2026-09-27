package com.devops.agent.domain.biz.repository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBoostRepository.class);

    /** 单次反馈解析出的 chunk 上限：一条分析的引用不该扇出成百条 boost 写 */
    static final int MAX_RESOLVE_IDS = 200;

    /** 单条引用命中的 chunk 上限（父段落 + 相邻子片的量级远低于此） */
    private static final int MAX_IDS_PER_CITATION = 50;

    /**
     * boost 地板/天花板（2026-09-24）。
     * <ul>
     *   <li><b>地板 0.2</b>：boost 直接乘在检索 score 上，无下限时一条误判的
     *       WRONG 就能把 chunk 打到 0 或负——score 被清零则该 chunk 永不浮现，
     *       且无法靠后续 HELPFUL 自愈式回升到可见面。地板保留「仍可被检索、
     *       但明显靠后」的诚实位置，坏反馈不再等于永久埋没；</li>
     *   <li><b>天花板 5.0</b>：防「一篇高频点赞的文档无限霸榜」，少量正反馈
     *       掩盖更相关的冷门文档——检索排序被历史热度劫持。</li>
     * </ul>
     */
    private static final double BOOST_FLOOR = 0.2;
    private static final double BOOST_CEIL = 5.0;

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
            out.put(id, computeBoost(helpful, wrong));
        }
        return out;
    }

    /**
     * boost 公式（纯函数，供单测与上方 boostBatch 共用）：
     * {@code clamp(1 + ln(1+helpful) − 0.5×wrong, [0.2, 5.0])}。
     * 原始路线图公式无上下限，见类顶部 {@link #BOOST_FLOOR}/{@link #BOOST_CEIL}。
     */
    static double computeBoost(int helpfulCount, int wrongCount) {
        double boost = 1.0 + Math.log1p(helpfulCount) - 0.5 * wrongCount;
        return Math.max(BOOST_FLOOR, Math.min(BOOST_CEIL, boost));
    }

    /**
     * 记一条反馈（upsert 语义：有即增量累加否则创建行）。
     *
     * @param chunkId 影响用的 chunk_id
     * @param verdict HELPFUL → helpful+1；WRONG / UNHELPFUL → wrong+1；
     *                PARTIAL → 两列都不动（「部分正确」不是对被引片段的质量判决，
     *                计为负反馈会误伤，忽略则维持原值）；其余值拒绝——
     *                此前未知判定会静默写入 (0,0) 空行，调用方以为回流成功
     * @throws IllegalArgumentException 未知判定值
     */
    public void recordFeedback(Long chunkId, String verdict) {
        int helpful;
        int wrong;
        switch (verdict == null ? "" : verdict) {
            case "HELPFUL" -> { helpful = 1; wrong = 0; }
            case "WRONG", "UNHELPFUL" -> { helpful = 0; wrong = 1; }
            case "PARTIAL" -> { helpful = 0; wrong = 0; }
            default -> throw new IllegalArgumentException(
                    "未知反馈判定（HELPFUL/WRONG/UNHELPFUL/PARTIAL 之外）: " + verdict);
        }
        String sql = """
                INSERT INTO sys_knowledge_boost (chunk_id, helpful_count, wrong_count)
                VALUES (?, ?, ?)
                ON CONFLICT (chunk_id) DO UPDATE
                SET helpful_count = sys_knowledge_boost.helpful_count + excluded.helpful_count,
                    wrong_count = sys_knowledge_boost.wrong_count + excluded.wrong_count,
                    updated_at = CURRENT_TIMESTAMP
                """;
        jdbcTemplate.update(sql, chunkId, helpful, wrong);
    }

    /**
     * 文档级反馈健康度（知识库治理出口）。
     * <p>
     * boost 表按 chunk 记反馈，治理要看的是文档——点踩多于点赞的文档
     * 是「负资产」，需要人复核或下架。按文档聚合后按净反馈（wrong - helpful）
     * 降序，最该复核的排最前。
     * </p>
     *
     * @return [{docId, title, helpful, wrong, net}]，只含有过反馈的文档
     */
    public List<Map<String, Object>> docHealthReport() {
        String sql = """
                SELECT d.id AS "docId", d.title,
                       SUM(b.helpful_count) AS helpful, SUM(b.wrong_count) AS wrong
                  FROM sys_knowledge_boost b
                  JOIN sys_knowledge_chunk c ON c.id = b.chunk_id
                  JOIN sys_knowledge_doc d ON d.id = c.doc_id
                 GROUP BY d.id, d.title
                 ORDER BY (SUM(b.wrong_count) - SUM(b.helpful_count)) DESC, d.id
                """;
        return jdbcTemplate.queryForList(sql);
    }

    /**
     * 按引用串回查被引 chunk 的 id（反馈回流「后端自取引用」入口）。
     * <p>
     * 背景（2026-09-24）：引用在全链路只以字符串形态流转
     * （SSE citations / 分析 citations 列都是【来源：标题 - 章节】），
     * chunk id 从未随行。诊断链不消费知识库，没有可回填的引用；
     * 真正带知识上下文的反馈点是工单 AI 分析（citations 落在分析行上）——
     * 反馈时由后端把引用解析回 chunk，不再依赖前端传 id。
     * </p>
     * <p>
     * 解析兼容三种既有格式（与前端 extractCitationsFromText、后端
     * RetrievedChunk.citation() 两处产出对齐）：带【来源：】包裹、不带包裹、
     * 有/无章节段。每条引用按三段回退命中：
     * <ol>
     *   <li>标题+章节（章节比对前剥掉 DB 侧的 {@code ## } 前缀）；</li>
     *   <li>整串当标题（覆盖「无章节」与「标题本身含 ' - '」两种情形）；</li>
     *   <li>拆出的标题做文档级命中（章节漂移/文档改版后引用仍落到该文档）。</li>
     * </ol>
     * 只解析 {@code ACTIVE} 且在生效窗口内的切片——检索读不到的切片
     * 给了 boost 也只会污染公式计数。
     * </p>
     *
     * @return 去重后的 chunk id（保持引用顺序）；无命中为空集
     */
    public Set<Long> resolveChunkIds(Collection<String> citations) {
        LinkedHashSet<Long> out = new LinkedHashSet<>();
        if (citations == null || citations.isEmpty()) {
            return out;
        }
        for (String raw : citations) {
            if (out.size() >= MAX_RESOLVE_IDS) {
                log.warn("[Boost] 引用解析达到上限 {}，剩余引用跳过 | 已解析 {}", MAX_RESOLVE_IDS, out.size());
                break;
            }
            resolveOne(raw, out);
        }
        return out;
    }

    private void resolveOne(String raw, LinkedHashSet<Long> out) {
        String inner = unwrapCitation(raw);
        if (inner.isEmpty()) {
            return;
        }
        CitationRef ref = splitCitation(inner);
        // 1) 标题+章节精确命中
        if (ref.section() != null && !ref.section().isEmpty()) {
            List<Long> hits = queryIds(ref.title(), ref.section());
            if (!hits.isEmpty()) {
                out.addAll(limit(hits));
                return;
            }
        }
        // 2) 整串当标题（无章节，或标题含 ' - ' 时整串才是真标题）
        List<Long> hits = queryIds(inner, null);
        if (!hits.isEmpty()) {
            out.addAll(limit(hits));
            return;
        }
        // 3) 章节对不上（漂移/改版）→ 退到文档级
        if (ref.section() != null && !ref.section().isEmpty()) {
            hits = queryIds(ref.title(), null);
            out.addAll(limit(hits));
        }
    }

    /** 剥【来源：】包裹；其余格式原样返回。 */
    static String unwrapCitation(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        if (s.startsWith("【来源：") && s.endsWith("】") && s.length() > "【来源：】".length()) {
            s = s.substring("【来源：".length(), s.length() - "】".length()).trim();
        }
        return s;
    }

    /**
     * 标题/章节拆分：以<b>第一个</b> " - " 为界（与前端
     * {@code extractCitationsFromText} 的 {@code [^-】]+} 取题一致——
     * 两处拆法不一致会让同一引用在两端解析出不同章节）。
     * 无分隔符时 section 为 null。
     */
    static CitationRef splitCitation(String inner) {
        int idx = inner.indexOf(" - ");
        if (idx <= 0) {
            return new CitationRef(inner, null);
        }
        return new CitationRef(inner.substring(0, idx).trim(), inner.substring(idx + 3).trim());
    }

    /** 解析结果：title 必有；section 无分隔段时为 null。 */
    record CitationRef(String title, String section) {}

    private List<Long> queryIds(String title, String section) {
        if (title == null || title.isBlank()) {
            return List.of();
        }
        String sql;
        if (section == null || section.isBlank()) {
            sql = """
                    SELECT id FROM sys_knowledge_chunk
                    WHERE status = 'ACTIVE'
                      AND (effective_at IS NULL OR effective_at <= CURRENT_TIMESTAMP)
                      AND (expired_at IS NULL OR expired_at > CURRENT_TIMESTAMP)
                      AND doc_title = ?
                    LIMIT %d
                    """.formatted(MAX_IDS_PER_CITATION);
            return jdbcTemplate.queryForList(sql, Long.class, title);
        }
        // 章节比对双向归一：DB 存 "## 排查步骤"，引用侧来自 citation() 已剥 # 前缀
        sql = """
                SELECT id FROM sys_knowledge_chunk
                WHERE status = 'ACTIVE'
                  AND (effective_at IS NULL OR effective_at <= CURRENT_TIMESTAMP)
                  AND (expired_at IS NULL OR expired_at > CURRENT_TIMESTAMP)
                  AND doc_title = ?
                  AND regexp_replace(COALESCE(section_header, ''), '^[#+\\s]+', '')
                      = regexp_replace(?, '^[#+\\s]+', '')
                LIMIT %d
                """.formatted(MAX_IDS_PER_CITATION);
        return jdbcTemplate.queryForList(sql, Long.class, title, section);
    }

    private static List<Long> limit(List<Long> ids) {
        return ids.size() > MAX_RESOLVE_IDS ? ids.subList(0, MAX_RESOLVE_IDS) : ids;
    }
}
