package com.devops.agent.controller;

import com.devops.agent.common.dto.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识运营统计（CLAUDE.md Step 3 / PRD §5.5：知识库运营看板的数据源）。
 *
 * <p>聚合知识库的运营指标——文档分布、切片规模、用户反馈、引用热度，
 * 供前端知识运营看板展示「知识库健康状况」：哪些文档被高频引用、
 * 用户反馈如何（好/坏知识权重）、知识库规模是否在增长。</p>
 *
 * <p>数据来源全部是现有表（sys_knowledge_doc / sys_knowledge_chunk /
 * sys_knowledge_boost），零新增表——知识库的真实运营数据本来就在，
 * 本端点只是把它们聚合成一个看板视图。</p>
 */
@RestController
@RequestMapping("/api/v1/knowledge/ops")
public class KnowledgeOpsController {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeOpsController.class);

    private final JdbcTemplate jdbcTemplate;

    public KnowledgeOpsController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 知识运营统计聚合。
     *
     * <p>返回：文档按状态分布、切片总数、反馈统计（好/坏/比率）、
     * 引用热度 Top（被反馈最多的切片对应的文档）。</p>
     */
    @GetMapping("/stats")
    public ApiResponse<Map<String, Object>> stats() {
        Map<String, Object> data = new LinkedHashMap<>();

        // 文档按状态分布
        data.put("docTotal", queryLong("SELECT COUNT(*) FROM sys_knowledge_doc"));
        data.put("docPublished", queryLong("SELECT COUNT(*) FROM sys_knowledge_doc WHERE status='PUBLISHED'"));
        data.put("docDraft", queryLong("SELECT COUNT(*) FROM sys_knowledge_doc WHERE status='DRAFT'"));
        data.put("docDeprecated", queryLong("SELECT COUNT(*) FROM sys_knowledge_doc WHERE status='DEPRECATED'"));
        // 切片总数
        data.put("chunkTotal", queryLong("SELECT COUNT(*) FROM sys_knowledge_chunk"));
        // 反馈统计
        data.put("feedbackHelpful", queryLong("SELECT COALESCE(SUM(helpful_count),0) FROM sys_knowledge_boost"));
        data.put("feedbackWrong", queryLong("SELECT COALESCE(SUM(wrong_count),0) FROM sys_knowledge_boost"));
        long helpful = (Long) data.get("feedbackHelpful");
        long wrong = (Long) data.get("feedbackWrong");
        long rated = helpful + wrong;
        data.put("helpfulRate", rated > 0 ? Math.round((double) helpful / rated * 1000) / 1000.0 : 0.0);
        // 引用热度 Top：被反馈最多的切片对应的文档标题
        data.put("topCitedChunks", topCitedChunks(10));

        log.info("[KnowledgeOps] 知识运营统计 | docTotal={} chunkTotal={} helpfulRate={}",
                data.get("docTotal"), data.get("chunkTotal"), data.get("helpfulRate"));
        return ApiResponse.success(data);
    }

    /** 引用热度 Top：按反馈次数排序的切片 + 所属文档标题。 */
    private List<Map<String, Object>> topCitedChunks(int limit) {
        String sql = """
                SELECT b.chunk_id, b.helpful_count, b.wrong_count,
                       d.title AS doc_title
                FROM sys_knowledge_boost b
                LEFT JOIN sys_knowledge_chunk c ON c.id = b.chunk_id
                LEFT JOIN sys_knowledge_doc d ON d.id = c.doc_id
                ORDER BY (b.helpful_count + b.wrong_count) DESC
                LIMIT ?
                """;
        List<Map<String, Object>> out = new ArrayList<>();
        jdbcTemplate.query(sql, rs -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("chunkId", rs.getLong("chunk_id"));
            m.put("helpfulCount", rs.getInt("helpful_count"));
            m.put("wrongCount", rs.getInt("wrong_count"));
            m.put("docTitle", rs.getString("doc_title"));
            out.add(m);
        }, limit);
        return out;
    }

    private long queryLong(String sql) {
        Long v = jdbcTemplate.queryForObject(sql, Long.class);
        return v != null ? v : 0L;
    }
}
