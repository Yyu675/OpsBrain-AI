package com.devops.agent.infrastructure.persistence.repo;

import com.devops.agent.support.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 按源工单聚合反馈计数（#7 徽标数据源）的 DB 集成测试。
 *
 * <h3>为什么走 DB 实证</h3>
 * 这条 JOIN（doc → chunk → boost）的语义是「这篇文档的反馈票数」：
 * 若 JOIN 条件写错方向、或 SUM 漏了 COALESCE（无 boost 的文档直接消失/变 null），
 * 结果就是徽标旁的数字静默错掉——「有帮助 N」的 N 是不可信的。mock 掉 SQL 验不了。
 */
@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = {"devops.ai.mode=MOCK"})
@DisplayName("按源工单反馈计数聚合")
class KnowledgeFeedbackStatsIntegrationTest extends AbstractIntegrationTest {

    private static final String TICKET = "TKT-FBSTATS-0001";

    @Autowired
    private KnowledgeDocRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<Long> seededDocIds = new ArrayList<>();

    @AfterEach
    void clean() {
        if (!seededDocIds.isEmpty()) {
            String in = String.join(",", seededDocIds.stream().map(String::valueOf).toList());
            jdbcTemplate.update("DELETE FROM sys_knowledge_boost WHERE chunk_id IN "
                    + "(SELECT id FROM sys_knowledge_chunk WHERE doc_id IN (" + in + "))");
            jdbcTemplate.update("DELETE FROM sys_knowledge_chunk WHERE doc_id IN (" + in + ")");
            jdbcTemplate.update("DELETE FROM sys_knowledge_doc WHERE id IN (" + in + ")");
            seededDocIds.clear();
        }
    }

    private long seedDoc(String title) {
        Long docId = jdbcTemplate.queryForObject("""
                INSERT INTO sys_knowledge_doc (title, content, status, index_status, source_ticket_id, source_type)
                VALUES (?, ?, 'PUBLISHED', 'INDEXED', ?, 'TICKET') RETURNING id
                """, Long.class, title, "内容-" + title, TICKET);
        seededDocIds.add(docId);
        return docId;
    }

    private long seedChunk(long docId) {
        StringBuilder vec = new StringBuilder("[1");
        for (int i = 1; i < 1536; i++) vec.append(",0");
        vec.append(']');
        Long chunkId = jdbcTemplate.queryForObject("""
                INSERT INTO sys_knowledge_chunk (doc_id, doc_title, content, embedding, status)
                VALUES (?, ?, ?, (?::vector), 'ACTIVE') RETURNING id
                """, Long.class, docId, "t", "chunk-" + docId + "-" + System.nanoTime(), vec.toString());
        return chunkId;
    }

    private void boost(long chunkId, int helpful, int wrong) {
        jdbcTemplate.update("""
                INSERT INTO sys_knowledge_boost (chunk_id, helpful_count, wrong_count)
                VALUES (?, ?, ?)
                """, chunkId, helpful, wrong);
    }

    @Test
    @DisplayName("按源工单聚合：跨 chunk 求和的反馈票数落到对应文档")
    void aggregatesFeedbackBySourceTicket() {
        long docA = seedDoc("文档A");
        long chunkA1 = seedChunk(docA);
        long chunkA2 = seedChunk(docA);
        boost(chunkA1, 3, 1);
        boost(chunkA2, 1, 0);

        long docB = seedDoc("文档B");  // 同一工单的另一篇，无任何反馈
        seedChunk(docB);

        var stats = repository.feedbackStatsBySourceTicket(TICKET);

        assertThat(stats).hasSize(2);
        // 文档A：3+1=4 有帮助，1+0=1 无用
        var a = stats.stream().filter(s -> s.docId().equals(docA)).findFirst().orElseThrow();
        assertThat(a.helpfulCount()).isEqualTo(4);
        assertThat(a.wrongCount()).isEqualTo(1);
        // 文档B：无 boost → 计数 0，但仍在结果里（沉淀了但没收到反馈是正常态）
        var b = stats.stream().filter(s -> s.docId().equals(docB)).findFirst().orElseThrow();
        assertThat(b.helpfulCount()).isZero();
        assertThat(b.wrongCount()).isZero();
    }

    @Test
    @DisplayName("无回链工单 → 空结果（不是 null）")
    void noDocsReturnsEmpty() {
        assertThat(repository.feedbackStatsBySourceTicket("TKT-NONE")).isEmpty();
    }
}