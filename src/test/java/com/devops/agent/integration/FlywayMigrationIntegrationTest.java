package com.devops.agent.integration;

import com.devops.agent.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Flyway 空库全量迁移链集成测试（S0-2 载体，同时为 S0-1 验收 #1 补第二重证据）。
 *
 * <h3>与 CI 现有证据的分工</h3>
 * <p>
 * CI 里的「空库建表」证据目前是 psql 桩把同一份 V1 灌进 service 库的
 * <b>间接证据</b>（内容的完备性有证，但执行者不是 Flyway）。本测试的容器
 * 每次运行都是<b>真空库</b>，且上下文启动期由 Flyway 亲自执行全量迁移——
 * 「空库 flyway migrate 一次建出全部表」在此成为<b>直接证据</b>，
 * 不依赖 ci.yml 的任何修改（机器人暂无 workflows 权限，见报告 100 §五）。
 * </p>
 *
 * <h3>为什么断言「27 张表」这个具体数字</h3>
 * <p>
 * 27 是 S0-1 基线（V1__baseline.sql）建出的业务表数量。若未来新增迁移，
 * 数字必须同步更新——这种「改迁移就要改断言」的摩擦正是刻意的：
 * 它让每次结构变更都显性经过测试评审。
 * flyway_schema_history 的版本表记录断言「恰好 {V1 基线, V2 变更表} 两条成功」，
 * （S1-2 起增量迁移加入后，集合式断言与路上契约测试的版本扫描互为双锁），
 * 三者合起来证明：基线建全了，且是 Flyway 托管地建全了。
 * </p>
 *
 * @author OpsBrain AI
 * @since 2026-09-07（S0-2）
 */
@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "devops.ai.mode=MOCK"
})
@DisplayName("Flyway：空库全量迁移链（容器真空库，启动期自动建全表）")
class FlywayMigrationIntegrationTest extends AbstractIntegrationTest {

    /**
     * sys_ 前缀业务表数量：
     * V1=27（S0-1 基线）+ V2=1（sys_change_event）+ V3=1（sys_diagnosis_evidence）
     * + V4=1（sys_diagnosis_session）+ V5=1（sys_diagnosis_hypothesis）
     * + V6=1（sys_knowledge_boost）+ V7=1（sys_healing_execution)。
     * V8 仅播种、V9 仅加列，均不新增表。改迁移需同步更新，见类注释。
     */
    private static final int EXPECTED_BASELINE_TABLE_COUNT = 33;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("空库启动即建出全部 33 张业务表（V1~V7 完备性，Flyway 亲自执行）")
    void emptyDatabaseShouldBeFullyMigratedByFlyway() {
        Integer tableCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                  FROM information_schema.tables
                 WHERE table_schema = 'public'
                   AND table_type = 'BASE TABLE'
                   AND table_name LIKE 'sys\\_%' ESCAPE '\\'
                """,
                Integer.class);
        assertThat(tableCount)
                .as("容器真空库应被 Flyway 建出 %d 张业务表（V1 基线 + V2~V7 各一表）。"
                        + "缺表说明迁移文件缺失或执行失败——这会摧毁集成测试的可信地基",
                        EXPECTED_BASELINE_TABLE_COUNT)
                .isEqualTo(EXPECTED_BASELINE_TABLE_COUNT);
    }

    @Test
    @DisplayName("flyway_schema_history 恰好 {1..11} 十一条全部成功（V1 基线 + V2~V11 增量链，托管生效证据）")
    void schemaHistoryShouldRecordBaselinePlusFirstIncrement() {
        var rows = jdbcTemplate.queryForList(
                """
                SELECT version, success
                  FROM flyway_schema_history
                 WHERE script <> '<< Flyway Baseline >>'
                 ORDER BY installed_rank
                """);
        assertThat(rows)
                .as("容器真空库按序执行 V1~V11。多出记录说明"
                        + "测试容器泄漏了别的库的脏状态，或混入了未评审的迁移文件")
                .hasSize(11);
        assertThat(rows.get(0).get("version")).as("首条为 V1 基线").isEqualTo("1");
        assertThat(rows.get(1).get("version")).as("V2 = S1-2 sys_change_event").isEqualTo("2");
        assertThat(rows.get(2).get("version")).as("V3 = S1-5 sys_diagnosis_evidence").isEqualTo("3");
        assertThat(rows.get(3).get("version")).as("V4 = S2-1 sys_diagnosis_session").isEqualTo("4");
        assertThat(rows.get(4).get("version")).as("V5 = S2-2 sys_diagnosis_hypothesis").isEqualTo("5");
        assertThat(rows.get(5).get("version")).as("V6 = S2-3 反馈列 + sys_knowledge_boost").isEqualTo("6");
        assertThat(rows.get(6).get("version")).as("V7 = S3-1 sys_healing_execution").isEqualTo("7");
        assertThat(rows.get(7).get("version")).as("V8 = S3-1 Mock 轨白名单种子").isEqualTo("8");
        assertThat(rows.get(8).get("version")).as("V9 = S3-3 执行后验证三列").isEqualTo("9");
        assertThat(rows.get(9).get("version")).as("V10 = S3-5 步骤时间线 steps_json").isEqualTo("10");
        assertThat(rows.get(10).get("version")).as("V11 = S5-4.2 慢查询静态审计两枚索引（批 42，报告 145 评审在案）").isEqualTo("11");
        assertThat(rows).allSatisfy(r ->
                assertThat(r.get("success")).as("所有迁移必须成功").isEqualTo(Boolean.TRUE));
    }
}
