package com.devops.agent.domain.evidence;

import com.devops.agent.domain.biz.entity.ChangeEvent;
import com.devops.agent.domain.biz.repository.ChangeEventRepository;
import com.devops.agent.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S1-2 变更取证：容器基座全流程测试（真实 PG + Flyway V2 建表——
 * V2.jsontotable 缺表会在此暴露，零 mock）。
 */
@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = {"devops.ai.mode=MOCK"})
@DisplayName("S1-2 变更取证：幂等写入 + NO_DATA/FAILED/SUCCESS 三态 + 相关性排序")
class ChangesEvidenceCollectorTest extends AbstractIntegrationTest {

    @Autowired
    private ChangeEventRepository repository;

    @Autowired
    private ChangesEvidenceCollector collector;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTable() {
        jdbc.update("DELETE FROM sys_change_event");
    }

    @Test
    @DisplayName("V2 建表真实存在（Flyway 容器迁移的直证）")
    void v2TableExists() {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_name='sys_change_event'",
                Integer.class);
        assertThat(n).isEqualTo(1);
    }

    @Test
    @DisplayName("幂等写：同一 (source, external_id) 重复写返回 0 且不产生第二行")
    void idempotentInsertSwallowsDuplicates() {
        ChangeEvent e = ChangeEvent.of("order-service", "deploy", "jenkins-bot",
                "1.4.2→1.4.3", LocalDateTime.now().minusMinutes(10),
                "ci-callback", "jenkins-891");
        assertThat(repository.save(e)).isEqualTo(1);
        assertThat(repository.save(e)).isEqualTo(0);
        List<ChangeEvent> rows = repository.findByExternalId("ci-callback", "jenkins-891");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).relevanceScore()).isNull(); // 原行不带查询侧分数
    }

    @Test
    @DisplayName("空表查询 → NO_DATA（有效排除证据），sourceRef 为可读谓词")
    void emptyTableYieldsNoData() {
        var ev = collector.collect("order-service", "2h");
        assertThat(ev.status()).isEqualTo(Evidence.EvidenceStatus.NO_DATA);
        assertThat(ev.sourceRef()).contains("sys_change_event");
    }

    @Test
    @DisplayName("有变更 → SUCCESS：近变更排前、相关性分档正确、载荷 JSON 键稳定")
    void rowsYieldSuccessSortedWithScores() throws Exception {
        repository.save(ChangeEvent.of("order-service", "deploy", "a",
                "老发布", LocalDateTime.now().minusMinutes(100), "ci-callback", "old-1"));
        repository.save(ChangeEvent.of("order-service", "config", "b",
                "新配置", LocalDateTime.now().minusMinutes(3), "ci-callback", "new-1"));
        repository.save(ChangeEvent.of("other-service", "deploy", "c",
                "别家服务", LocalDateTime.now().minusMinutes(1), "ci-callback", "other-1"));

        var ev = collector.collect("order-service", "2h");
        assertThat(ev.status()).isEqualTo(Evidence.EvidenceStatus.SUCCESS);
        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(ev.toToolPayload(), Map.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> changes =
                (List<Map<String, Object>>) ((Map<String, Object>) parsed.get("content")).get("changes");
        assertThat(changes).hasSize(2);
        assertThat(changes.get(0).get("summary")).isEqualTo("新配置");
        assertThat(((Number) changes.get(0).get("relevanceScore")).doubleValue()).isEqualTo(1.0);
        assertThat(((Number) changes.get(1).get("relevanceScore")).doubleValue())
                .isEqualTo(0.4); // 100 分钟 > 1h → 0.4 档
        assertThat((Number) ev.relevanceScore()).isNotNull();
        assertThat(ev.relevanceScore()).isEqualTo(1.0); // 顶层分 = 最近一条
    }

    @Test
    @DisplayName("越窗变更不可见（2h 窗接不到 3h 前的发布） → NO_DATA")
    void outOfWindowIsInvisible() {
        repository.save(ChangeEvent.of("order-service", "deploy", "a",
                "三小时前的发布", LocalDateTime.now().minusHours(3), "ci-callback", "old-3h"));
        var ev = collector.collect("order-service", "2h");
        assertThat(ev.status()).isEqualTo(Evidence.EvidenceStatus.NO_DATA);
    }
}
