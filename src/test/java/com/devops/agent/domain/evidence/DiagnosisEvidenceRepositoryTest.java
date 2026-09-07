package com.devops.agent.domain.evidence;

import com.devops.agent.domain.biz.repository.DiagnosisEvidenceRepository;
import com.devops.agent.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** S1-5 落库：V3 建表直证 + 同 trace 回放保序（验收 #4）。 */
@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = {"devops.ai.mode=MOCK"})
@DisplayName("S1-5 证据落库：V3 直证 + 按 traceId 回放")
class DiagnosisEvidenceRepositoryTest extends AbstractIntegrationTest {

    @Autowired
    private DiagnosisEvidenceRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTable() {
        jdbc.update("DELETE FROM sys_diagnosis_evidence");
    }

    @Test
    @DisplayName("V3 建表真实存在（Flyway 容器迁移直证）")
    void v3TableExists() {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_name='sys_diagnosis_evidence'",
                Integer.class);
        assertThat(n).isEqualTo(1);
    }

    @Test
    @DisplayName("同 trace 的证据按落库顺序回放；relevance 可空/可存")
    void saveAndReplayByTraceId() {
        Timestamp t1 = Timestamp.from(Instant.now().minusSeconds(60));
        Timestamp t2 = Timestamp.from(Instant.now());
        long id1 = repository.save("trace-42", "diagnosis-engine", "metrics", "SUCCESS",
                "指标 2 个异常", "{\"status\":\"SUCCESS\"}",
                "sum(rate(...))", 1.0, t1);
        long id2 = repository.save("trace-42", "diagnosis-engine", "changes", "NO_DATA",
                "无变更", "{\"status\":\"NO_DATA\"}",
                "sys_change_event where ...", null, t2);
        repository.save("trace-other", "eval", "logs", "FAILED", "别的链", "{}", null, null, t2);

        assertThat(id1).isPositive();
        assertThat(id2).isGreaterThan(id1);

        List<Map<String, Object>> rows = repository.findByTraceId("trace-42");
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("evidence_type")).isEqualTo("metrics");
        assertThat(rows.get(1).get("evidence_type")).isEqualTo("changes");
        assertThat(rows.get(0).get("relevance_score")).isEqualTo(1.0);
        assertThat(rows.get(1).get("relevance_score")).isNull();
        // 孤证不写 trace_id 也可落
        long orphan = repository.save(null, "eval", "metrics", "UNAVAILABLE",
                "未启用", "{}", null, null, t2);
        assertThat(orphan).isPositive();
    }
}
