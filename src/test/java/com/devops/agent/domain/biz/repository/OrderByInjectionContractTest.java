package com.devops.agent.domain.biz.repository;

import com.devops.agent.domain.biz.entity.TicketQuery;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;

/**
 * 动态 {@code ORDER BY} 的<b>防注入契约</b>。
 *
 * <h3>为什么专门给这段代码加测试</h3>
 * 排序列名<b>无法用 {@code ?} 占位符</b>——SQL 语法不允许，只能拼字符串。
 * 这使得 {@code ORDER BY} 成为全项目**仅有的两处**用户输入直达 SQL 文本的地方。
 *
 * <p>现有实现是对的：用 {@code Map} 白名单把前端字段名映射为固定列名，
 * 未命中就降级为默认排序。但它<b>一行测试都没有</b>——
 * 有人日后为了「支持更多排序字段」把它改成直接拼接，
 * CI 不会有任何反应，而那一刻 {@code /api/tickets?sortBy=id;DROP TABLE...}
 * 就成立了。</p>
 *
 * <h3>这类缺陷的特征：改错了功能反而"更好用"</h3>
 * 白名单的表现是「传了不认识的字段就不生效」，会被当成 bug 报上来。
 * 而「直接拼接」的版本能支持任意字段，看起来是修好了——
 * <b>把安全边界拆掉的改动，在功能上是正向的</b>，这正是它危险的地方。
 * 所以必须有测试写明「这里的限制是故意的」。
 *
 * @author OpsBrain AI
 * @since 2026-08-27
 */
@DisplayName("ORDER BY 防注入契约")
class OrderByInjectionContractTest {

    /**
     * 反射调用 private 的 {@code buildOrderBy}。
     *
     * <p>用 Mockito 造壳而非真实 new：仓储构造需要 JdbcTemplate，
     * 而 {@code buildOrderBy} 是不碰任何字段的纯函数。
     * {@code CALLS_REAL_METHODS} 让它走真实逻辑。</p>
     */
    private String buildOrderBy(String sortBy, boolean asc) throws Exception {
        DevOpsTicketRepository repo = mock(DevOpsTicketRepository.class,
                withSettings().defaultAnswer(org.mockito.Mockito.CALLS_REAL_METHODS));
        Method m = DevOpsTicketRepository.class
                .getDeclaredMethod("buildOrderBy", TicketQuery.class);
        m.setAccessible(true);
        TicketQuery q = new TicketQuery(null, null, null, null, null, null,
                null, null, List.of(), sortBy, asc);
        return (String) m.invoke(repo, q);
    }

    // ==================================================================
    // 注入载荷
    // ==================================================================

    @Nested
    @DisplayName("恶意输入")
    class MaliciousInput {

        @ParameterizedTest(name = "sortBy={0}")
        @ValueSource(strings = {
                "id; DROP TABLE sys_devops_ticket",
                "id) ; DELETE FROM sys_devops_ticket --",
                "1; UPDATE sys_devops_ticket SET status='VOID'",
                "id UNION SELECT password FROM sys_user",
                "(SELECT password FROM sys_user LIMIT 1)",
                "id/**/;/**/DROP/**/TABLE/**/x",
                "create_time; --",
                "id'",
                "id\"",
                "id`",
        })
        @DisplayName("注入载荷一律降级为默认排序，payload 不得出现在结果里")
        void injectionPayloadNeverReachesSql(String payload) throws Exception {
            String orderBy = buildOrderBy(payload, true);

            // 断言落在「payload 的特征片段没进 SQL」上，而不是只看「等于默认值」——
            // 后者在实现改成「拼接但也加了默认后缀」时仍会通过
            String lower = orderBy.toLowerCase(Locale.ROOT);
            assertThat(lower)
                    .as("注入载荷片段不得出现在 ORDER BY 里，实际=%s", orderBy)
                    .doesNotContain("drop").doesNotContain("delete")
                    .doesNotContain("union").doesNotContain("update")
                    .doesNotContain("select").doesNotContain(";")
                    .doesNotContain("--").doesNotContain("'")
                    .doesNotContain("\"").doesNotContain("`");
            assertThat(orderBy).isEqualTo("create_time DESC");
        }

        @Test
        @DisplayName("白名单外的普通字段名同样降级——不是只拦「看起来危险」的输入")
        void unknownColumnAlsoDegrades() throws Exception {
            // password 是真实存在的列，不含任何特殊字符。
            // 若实现改成「过滤特殊字符后拼接」而非白名单，这条会失败——
            // 而那种实现允许按任意列排序，可用于逐位推断敏感字段
            assertThat(buildOrderBy("password", true)).isEqualTo("create_time DESC");
            assertThat(buildOrderBy("secret_column", false)).isEqualTo("create_time DESC");
        }

        @Test
        @DisplayName("空值与空白降级为默认排序，不抛异常")
        void blankIsSafe() throws Exception {
            assertThat(buildOrderBy(null, true)).isEqualTo("create_time DESC");
            assertThat(buildOrderBy("", true)).isEqualTo("create_time DESC");
            assertThat(buildOrderBy("   ", true)).isEqualTo("create_time DESC");
        }
    }

    // ==================================================================
    // 正常功能不能被防护误伤
    // ==================================================================

    @Nested
    @DisplayName("合法排序")
    class LegitimateSorting {

        @Test
        @DisplayName("白名单字段正常映射为数据库列名")
        void whitelistedFieldsWork() throws Exception {
            assertThat(buildOrderBy("title", true)).startsWith("title ASC");
            assertThat(buildOrderBy("status", false)).startsWith("status DESC");
            // 前端 camelCase → 数据库 snake_case
            assertThat(buildOrderBy("createdAt", true)).isEqualTo("create_time ASC");
            assertThat(buildOrderBy("updatedAt", false)).startsWith("update_time DESC");
            // 「服务」列展示 module 的可读标签，排序按 module
            assertThat(buildOrderBy("service", true)).startsWith("module ASC");
        }

        @Test
        @DisplayName("方向只可能是 ASC / DESC 两个常量")
        void directionIsConstant() throws Exception {
            // 方向若也来自用户输入拼接，同样是注入点。
            // 现实现由 boolean 派生，这里锁住它不退化为字符串透传
            assertThat(buildOrderBy("title", true)).contains("ASC").doesNotContain("DESC ,");
            assertThat(buildOrderBy("title", false)).contains("DESC");
        }

        @Test
        @DisplayName("优先级按业务权重排，不按字典序")
        void priorityUsesBusinessWeight() throws Exception {
            // priority 存的是 P0/P1/P2/P3，字典序恰好正确；
            // 但旧数据里还有 HIGH/MEDIUM/LOW，字典序会得到 HIGH→LOW→MEDIUM
            // （"L" < "M"），与业务语义相反
            String orderBy = buildOrderBy("priority", true);
            assertThat(orderBy).contains("CASE priority");
            assertThat(orderBy).contains("'P0'").contains("'HIGH'");
        }

        @Test
        @DisplayName("非 create_time 主排序时追加稳定二级排序")
        void secondarySortForStability() throws Exception {
            // 主排序字段有大量相同值时（状态只有 5 种），
            // 无稳定二级排序会让同一条记录在不同页重复出现或消失
            assertThat(buildOrderBy("status", true)).endsWith(", create_time DESC");
            // create_time 自身作为主排序时不重复追加
            assertThat(buildOrderBy("createdAt", true)).isEqualTo("create_time ASC");
        }
    }

    // ==================================================================
    // 源码级：防止实现被改成直接拼接
    // ==================================================================

    @Nested
    @DisplayName("实现方式约束")
    class ImplementationConstraint {

        private static final Path REPO = Path.of(
                "src/main/java/com/devops/agent/domain/biz/repository/DevOpsTicketRepository.java");
        private static final Path KNOWLEDGE_REPO = Path.of(
                "src/main/java/com/devops/agent/infrastructure/persistence/repo/KnowledgeDocRepository.java");

        private String codeOf(Path p) {
            try {
                return Files.readAllLines(p, StandardCharsets.UTF_8).stream()
                        .map(String::trim)
                        .filter(l -> !l.startsWith("//") && !l.startsWith("*") && !l.startsWith("/*"))
                        .reduce("", (a, b) -> a + "\n" + b);
            } catch (IOException e) {
                throw new IllegalStateException("读取失败: " + p, e);
            }
        }

        @Test
        @DisplayName("排序必须走白名单 Map，而不是过滤/转义后拼接")
        void mustUseWhitelistMap() {
            // 行为断言（上面那些）只能证明「当前输入被挡住了」。
            // 若有人改成正则过滤特殊字符后拼接，那些用例可能仍然通过，
            // 但攻击面已经从「零」变成「取决于正则写得多严」。
            // 这条锁住实现方式本身
            String code = codeOf(REPO);
            assertThat(code)
                    .as("排序列名必须由白名单映射得到——过滤式防护迟早会被绕过")
                    .contains("SORTABLE_COLUMNS");
            assertThat(code)
                    .as("白名单查表结果为 null 时必须降级，不得回落到用户输入")
                    .contains("SORTABLE_COLUMNS.get(");
        }

        @Test
        @DisplayName("知识库排序用 switch 常量分支，不接受任意字段")
        void knowledgeSortUsesConstantBranches() {
            // 另一处动态 ORDER BY。它用 switch 枚举固定几种排序，
            // default 落到 update_time——同样是白名单思路
            String code = codeOf(KNOWLEDGE_REPO);
            assertThat(code).contains("normalizedSort");
            assertThat(code)
                    .as("必须有 default 分支兜底，否则未知取值会让 ORDER BY 缺失")
                    .containsPattern("default\\s*->");
        }
    }
}
