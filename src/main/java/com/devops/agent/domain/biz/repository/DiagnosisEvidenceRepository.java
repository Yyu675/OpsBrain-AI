package com.devops.agent.domain.biz.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

/**
 * sys_diagnosis_evidence 仓储（S1-5）。
 * <p>
 * 读面只有「按 traceId 回放」一个（验收 #4）——别的维度（类型/状态过滤）
 * 现在不需要就不预建（写完没人用是负债不是资产）。
 * </p>
 */
@Repository
public class DiagnosisEvidenceRepository {

    private final JdbcTemplate jdbcTemplate;

    public DiagnosisEvidenceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 落一条证据。
     *
     * @return 自增 id（落库即事实，不回滚语义）
     */
    public long save(String traceId, String agentName, String evidenceType, String status,
                     String title, String content, String sourceRef, Double relevanceScore,
                     Timestamp collectedAt) {
        String sql = """
                INSERT INTO sys_diagnosis_evidence
                    (trace_id, agent_name, evidence_type, status, title, content,
                     source_ref, relevance_score, collected_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """;
        Long id = jdbcTemplate.queryForObject(sql, Long.class,
                traceId, agentName, evidenceType, status, title, content,
                sourceRef, relevanceScore, collectedAt);
        return id == null ? -1 : id;
    }

    /** 回放：按 traceId 拉全链（按落库顺序）。 */
    public List<Map<String, Object>> findByTraceId(String traceId) {
        String sql = """
                SELECT id, trace_id, agent_name, evidence_type, status, title,
                       content, source_ref, relevance_score, collected_at
                FROM sys_diagnosis_evidence
                WHERE trace_id = ?
                ORDER BY id
                """;
        return jdbcTemplate.queryForList(sql, traceId);
    }
}
