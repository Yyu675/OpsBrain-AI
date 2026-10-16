package com.devops.agent.integration;

import com.devops.agent.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.util.List;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Flyway 空库全量迁移链集成测试（S0-2 载体，同时为 S0-1 验收 #1 补第二重证据）。
 *
 * <h3>与 CI 现有证据的分工</h3>
 * <p>
 * CI 里的「空库建表」证据目前是 psql 桩把同一份 V1 灌进 service 库的
 * <b>间接证据</b>（内容的完备性有证，但执行者不是 Flyway）。本测试的容器
 * 每次运行都是<b>真空库</b>，且上下文启动期由 Flyway 亲自执行全量迁移——
 * 「空库 flyway migrate 一次建出全部表」在此成为<b>直接证据</b>。
 * </p>
 *
 * <h3>为什么断言具体表数与具体迁移集合</h3>
 * <p>
 * 表数常量是「迁移链当前形态」的指纹：V1 单基线 33 表（批 69 折叠：原 V1 27 表
 * 与 V2~V7 各 1 表内联）+ 增量 8 表（V2/V3/V5×2/V7/V8/V13/V16）= 41。
 * 历史表断言则与 classpath 上的迁移文件<b>逐一对应</b>：
 * 少一条 = 新迁移文件没被执行；多一条 = 有文件缺失、或历史表里混入了无文件覆盖的记录。
 * 这份「改迁移就要改断言」的摩擦是刻意的——它让每次结构变更都显性经过测试评审。
 * </p>
 *
 * <h3>2026-10-14 修正（rebase 到 main 后合并两版修法）</h3>
 * <p>
 * 本类的表数断言历史上漂移过两次：先是停在批 69 的「V1 单基线 33 表」快照，
 * 批77/89 迁移重入历史后又写成 34（漏算 V2~V16 里的 6 张增量表——当时真实值是 40，
 * 批93 的 V16/V17 之后是 41）。两次漂移都没被门禁拦住，因为 CI 后端腿的
 * {@code ./mvnw verify 2>&1 | tee} 在默认 shell（{@code bash -e {0}}，无 pipefail）下
 * 取 tee 的退出码，长期假绿（根因与修复见 ADR §11.7.1）。门禁修复后本类改为：
 * </p>
 * <ul>
 *   <li>表数断言取「表清单」而非 COUNT(*)，失败信息直接打印清单——红的时候一眼看出
 *       是多了一张（谁建的）还是少了一张（哪段迁移没跑到），不必回来加打印再烧一轮 CI；</li>
 *   <li>历史断言不再写死条数，改为扫 {@code classpath*:db/migration/V*.sql} 的版本集合，
 *       与 {@code flyway_schema_history} 逐一对应（{@code containsExactlyInAnyOrderElementsOf}）。</li>
 * </ul>
 * <p>
 * 另：库里的 sys_ 表可能多一张 {@code sys_model_fingerprint}——它由
 * {@code ModelFingerprintGuard} 运行时自建（不在迁移文件里），且出现时机依赖
 * 用例执行顺序，计入会让本断言随机红，故显式排除。
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
     * sys_ 前缀业务表数量（容器真空库、Flyway 全量执行后）：
     * V1 单基线 33 表（批 69 折叠：原 V1 27 表 + V2~V7 各 1 表内联）
     * + 增量 8 表：V2 知识库 / V3 AI 渠道 / V5 渠道历史与能力（×2）/ V7 服务责任人 /
     *   V8 效能快照 / V13 AI 渠道模板 / V16 通知渠道 = 合计 41。
     * V17/V18 只加列不建表（false_positive 相关）。
     * 改迁移需同步更新本常量——这是「刻意摩擦」，别删（见类注释）。
     */
    private static final int EXPECTED_BUSINESS_TABLE_COUNT = 41;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("空库启动即建出全部业务表（V1 基线 + 折叠后增量的完备性，Flyway 亲自执行）")
    void emptyDatabaseShouldBeFullyMigratedByFlyway() {
        // 取「表清单」而非 COUNT(*)：红的时候把清单打出来，一眼能看出是多了一张
        // （谁建的）还是少了一张（哪段迁移没跑到）——只断言数字的话失败信息只有
        // 「expected 41 but was 42」，还得回来加打印再烧一轮 CI。
        //
        // 排除 sys_model_fingerprint：它由 ModelFingerprintGuard.verifyOrRecord()
        // 运行时自建（REAL 模式启动期即建），不在任何迁移文件里。dev profile 的测试
        // 容器里当前没有这张表，但排除是刻意的——「有没有人跑过指纹校验」不该影响
        // 本断言的结果，契约表计数必须只由迁移文件决定。
        //
        // 口径提醒：ci.yml 的「空库全量建账表数」提示步数的是 public 下全部表
        // （契约表 + flyway_schema_history），与本断言的 sys_ 契约表口径差 1——
        // 别把提示步的数字直接当期望值（2026-10-13 曾据此误判，见 ADR §11.7.3）。
        List<String> tables = jdbcTemplate.queryForList(
                """
                SELECT table_name
                  FROM information_schema.tables
                 WHERE table_schema = 'public'
                   AND table_type = 'BASE TABLE'
                   AND table_name LIKE 'sys\\_%' ESCAPE '\\'
                   AND table_name <> 'sys_model_fingerprint'
                 ORDER BY table_name
                """,
                String.class);
        assertThat(tables)
                .as("容器真空库应被 Flyway 建出 %d 张业务表（V1 基线 33 表 + 增量 8 表）；"
                        + "实际清单=%s（少表=迁移没跑到，多表=有代码绕过迁移链建表）",
                        EXPECTED_BUSINESS_TABLE_COUNT, tables)
                .hasSize(EXPECTED_BUSINESS_TABLE_COUNT);
    }

    @Test
    @DisplayName("flyway_schema_history 与 classpath 迁移文件逐一对应（不多不少、全部成功）")
    void schemaHistoryShouldMatchMigrationFiles() throws IOException {
        var rows = jdbcTemplate.queryForList(
                """
                SELECT version, success
                  FROM flyway_schema_history
                 WHERE script <> '<< Flyway Baseline >>'
                 ORDER BY installed_rank
                """);
        List<String> applied = rows.stream()
                .map(r -> String.valueOf(r.get("version")))
                .toList();

        // 期望集合来自 classpath 上的真实迁移文件（而非硬编码），
        // 这样「新增迁移文件但 CI 没跑到」与「文件被删但历史残留」都会立刻红。
        var resolver = new PathMatchingResourcePatternResolver();
        var expected = new TreeSet<String>();
        for (var resource : resolver.getResources("classpath*:db/migration/V*.sql")) {
            String name = resource.getFilename();   // V12__ai_channel_protocol_provider.sql
            expected.add(name.substring(1, name.indexOf("__")));
        }

        assertThat(expected)
                .as("classpath 上应能找到迁移文件——找不到说明扫描口径失效，本断言会失去意义")
                .isNotEmpty();
        assertThat(applied)
                .as("历史表应与迁移文件集合逐一对应：少一条=新迁移未执行，多一条=文件缺失或手工记录")
                .containsExactlyInAnyOrderElementsOf(expected);
        assertThat(rows).allSatisfy(r ->
                assertThat(r.get("success")).as("所有迁移必须成功").isEqualTo(Boolean.TRUE));
    }
}
