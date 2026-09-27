package com.devops.agent.domain.healing;

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
 * {@code sys_healing_execution} 统计聚合的 DB 集成测试（Testcontainers pgvector）。
 *
 * <h3>为什么统计口径要走 DB 实证</h3>
 * 这些计数是 PRD 北极星「无需人工闭环事件占比」与「自动处置误操作率」的
 * 账本——L4 该不该放开自动执行、放开到几档，就靠这些数字说话。计数任何一处
 * FILTER 写错口径（如把「SUCCEEDED」错当「闭环」、漏了验证 PASS 条件），
 * 结果都是「一个看起来合理、实际不可信的自洽承诺」——比没有数字更危险。
 * 单测 mock 掉 SQL 验不了口径；只有真库 INSERT 后断言计数才对得上。
 */
@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = {"devops.ai.mode=MOCK"})
@DisplayName("自愈台账统计聚合（stats 口径）")
class HealingExecutionStatsIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private HealingExecutionRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<Long> seededIds = new ArrayList<>();

    @AfterEach
    void clean() {
        if (!seededIds.isEmpty()) {
            String in = String.join(",", seededIds.stream().map(String::valueOf).toList());
            jdbcTemplate.update("DELETE FROM sys_healing_execution WHERE id IN (" + in + ")");
            seededIds.clear();
        }
    }

    private long seed(String requestedBy, String status, String verifyStatus) {
        long id = repository.insert(HealingExecution.draft(
                "k8s.pod.restart", "prod", "svc-a", "{}", 1L,
                requestedBy, "AUTO_EXECUTE", null, "k8s-fabric8-restart-pod",
                status, "重启 plan", "ok", null, null, null));
        seededIds.add(id);
        if (verifyStatus != null) {
            repository.markVerified(id, verifyStatus, "{}");
        }
        return id;
    }

    @Test
    @DisplayName("计数口径：auto/闭环/验证/撤销四组 FILTER 各就各位")
    void statsCountsMatchSeed() {
        // auto：闭环 1（A）、未验证 1（B）、失败 1（D）
        seed("auto", "SUCCEEDED", "PASS");                 // auto_closed_loop
        seed("auto", "SUCCEEDED", null);                   // auto + verify_pending
        seed("manual", "SUCCEEDED", null);                 // manual，不参与 auto 口径
        seed("auto", "FAILED", null);                      // auto_total 但非闭环
        seed("manual", "REJECTED", null);                  // 拒批
        seed("manual", "PENDING_APPROVAL", null);          // 待审
        seed("manual", "UNDONE", null);                    // 撤销

        Map<String, Long> s = repository.stats();

        assertThat(s.get("total")).isEqualTo(7);
        assertThat(s.get("succeeded")).isEqualTo(3);
        assertThat(s.get("failed")).isEqualTo(1);
        assertThat(s.get("rejected")).isEqualTo(1);
        assertThat(s.get("pendingApproval")).isEqualTo(1);
        assertThat(s.get("undone")).isEqualTo(1);

        assertThat(s.get("autoTotal")).isEqualTo(3);
        assertThat(s.get("autoClosedLoop"))
                .as("闭环 = auto + SUCCEEDED + 验证 PASS——三个条件缺一不可")
                .isEqualTo(1);
        assertThat(s.get("verifyPass")).isEqualTo(1);
        assertThat(s.get("verifyPending"))
                .as("SUCCEEDED 但未验证：auto 1 + manual 1")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("空表：全 0，不抛（vacu），比率由调用方给 0")
    void emptyTableYieldsZeros() {
        Map<String, Long> s = repository.stats();
        assertThat(s.get("total")).isZero();
        assertThat(s.get("autoTotal")).isZero();
        assertThat(s.get("autoClosedLoop")).isZero();
    }

    @Test
    @DisplayName("验证 FAIL 计入 verifyFail——误操作率的分子（已回滚/已升级）")
    void verifyFailCounted() {
        seed("auto", "SUCCEEDED", "FAIL");

        Map<String, Long> s = repository.stats();
        assertThat(s.get("verifyFail")).isEqualTo(1);
        assertThat(s.get("autoClosedLoop")).isZero();
    }
}