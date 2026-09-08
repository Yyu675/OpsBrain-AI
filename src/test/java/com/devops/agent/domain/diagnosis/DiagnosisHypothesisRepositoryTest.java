package com.devops.agent.domain.diagnosis;

import com.devops.agent.domain.biz.repository.DiagnosisHypothesisRepository;
import com.devops.agent.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** S2-2 假设落库：V5 建表直证 + 按 session trace 回放保序（2-2.6 验收承载）。 */
@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = {"devops.ai.mode=MOCK"})
@DisplayName("S2-2 假设落库：V5 直证 + 按 session 回放")
class DiagnosisHypothesisRepositoryTest extends AbstractIntegrationTest {

    @Autowired
    private DiagnosisHypothesisRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTable() {
        jdbc.update("DELETE FROM sys_diagnosis_hypothesis");
    }

    @Test
    @DisplayName("V5 建表真实存在（Flyway 容器迁移直证）")
    void v5TableExists() {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_name='sys_diagnosis_hypothesis'",
                Integer.class);
        assertThat(n).isEqualTo(1);
    }

    @Test
    @DisplayName("Top-3 落库 + 按 session trace 回放保序（rank 升序）")
    void saveAndReplayBySession() {
        String trace = "trace-hypo-1";
        Hypothesis h1 = new Hypothesis(1, "变更回归", "rel=0.9", 0.78,
                List.of(10L, 11L), List.of(), "回滚", Instant.now());
        Hypothesis h2 = new Hypothesis(2, "指标异常面板", "anomaly=3", 0.66,
                List.of(11L), List.of(10L), "核对依赖链", Instant.now());
        repository.save(trace, h1);
        repository.save(trace, h2);
        repository.save("trace-other", new Hypothesis(1, "别的", "r", 0.5,
                List.of(), List.of(), "s", Instant.now()));

        List<Map<String, Object>> rows = repository.findBySessionTraceId(trace);
        assertThat(rows).hasSize(2);
        assertThat(((Number) rows.get(0).get("rank")).intValue()).isEqualTo(1);
        assertThat(rows.get(0).get("statement")).isEqualTo("变更回归");
        assertThat(rows.get(0).get("evidence_ids")).isEqualTo("[10,11]");
        assertThat(rows.get(1).get("contradict_ids")).isEqualTo("[10]");
        assertThat(repository.countBySession(trace)).isEqualTo(2L);
    }
}
