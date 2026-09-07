package com.devops.agent.domain.biz.repository;

import com.devops.agent.domain.diagnosis.Hypothesis;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * sys_diagnosis_hypothesis 仓储（S2-2，路线图 §6.2 2-2.2/2-2.6）。
 * <p>
 * 读面只有「按 session trace 回放」（点开假设看证据的同族入口）。
 * 证据/矛盾 id 列以 JSON 数组原文存——关联键不带语义，不需要双向查询。
 * </p>
 */
@Repository
public class DiagnosisHypothesisRepository {

    private final JdbcTemplate jdbcTemplate;

    public DiagnosisHypothesisRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 落一条假设。
     *
     * @return 自增 id
     */
    public long save(String sessionTraceId, Hypothesis h) {
        String sql = """
                INSERT INTO sys_diagnosis_hypothesis
                    (session_trace_id, rank, statement, reasoning, confidence,
                     evidence_ids, contradict_ids, suggested_action, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """;
        Long id = jdbcTemplate.queryForObject(sql, Long.class,
                sessionTraceId, h.rank(), h.statement(), h.reasoning(), h.confidence(),
                idsToJson(h.evidenceIds()), idsToJson(h.contradictIds()),
                h.suggestedAction(),
                h.createdAt() == null ? null : Timestamp.from(h.createdAt()));
        return id == null ? -1 : id;
    }

    /** 按 session trace 回放（诊断详情 API 的同族入口）。 */
    public List<Map<String, Object>> findBySessionTraceId(String sessionTraceId) {
        String sql = """
                SELECT id, session_trace_id, rank, statement, reasoning, confidence,
                       evidence_ids, contradict_ids, suggested_action, created_at
                FROM sys_diagnosis_hypothesis
                WHERE session_trace_id = ?
                ORDER BY rank
                """;
        return jdbcTemplate.queryForList(sql, sessionTraceId);
    }

    /** JSON 数组原文（Long 列表 → "[1,2,3]"；空 → "[]"）。 */
    private static String idsToJson(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(ids.get(i));
        }
        return sb.append(']').toString();
    }

    /** 落一次用户反馈（2-3.5；单值最后一次为准）。
     * @return 更新行数（0 = hypothesisId 不存在，调用方必须显性处理） */
    public int updateFeedback(long id, String feedback) {
        String sql = "UPDATE sys_diagnosis_hypothesis SET feedback = ?, "
                + "feedback_at = CURRENT_TIMESTAMP WHERE id = ?";
        return jdbcTemplate.update(sql, feedback, id);
    }

    /** 观测性的计数读面（监控健康度用，不做渲染别名层）。 */
    public long countBySession(String sessionTraceId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM sys_diagnosis_hypothesis WHERE session_trace_id = ?",
                Long.class, sessionTraceId);
    }
}
