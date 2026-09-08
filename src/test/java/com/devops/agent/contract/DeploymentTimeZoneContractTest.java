package com.devops.agent.contract;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 部署时区一致性契约。
 *
 * <h3>为什么需要把「时区」写成测试</h3>
 * <p>
 * 本项目全部业务时间列都是<b>无时区的</b> {@code TIMESTAMP}，
 * 由 {@code LocalDateTime.now()} 与数据库 {@code CURRENT_TIMESTAMP} 写入。
 * 这个方案能成立，靠的是一个跨三个文件的<b>隐式约定</b>：
 * </p>
 * <ol>
 *   <li>{@code Dockerfile} —— 应用容器 {@code /etc/localtime} 设为 Asia/Shanghai；</li>
 *   <li>{@code docker-compose.yml} —— app 与 postgres 两个服务都设 {@code TZ: Asia/Shanghai}；</li>
 *   <li>{@code devops-platform-frontend/src/utils/time.ts} —— {@code parseDate}
 *       把无时区字符串按 {@code SERVER_UTC_OFFSET = '+08:00'} 解释。</li>
 * </ol>
 * <p>
 * 三处必须表达同一个事实。<b>任一处被单独改动，系统不会报任何错</b>——
 * 只是所有时间显示悄悄偏移几个小时。这正是本轮修掉的
 * {@code AlertService.toLocalDateTime} 存 UTC 那个缺陷的同型问题：
 * 无时区列 + 不一致的写入口径 = 静默的时间错乱。
 * </p>
 *
 * <h3>这组断言的定位</h3>
 * <p>
 * 它不验证「Asia/Shanghai 是不是正确的选择」——那是部署决策。
 * 它验证的是<b>三处保持同步</b>：真要迁到别的时区，三个文件得一起改，
 * 这组测试会强制改动者意识到还有另外两处。
 * </p>
 *
 * @author OpsBrain AI
 * @since 2026-08-28
 */
@DisplayName("部署时区一致性契约")
class DeploymentTimeZoneContractTest {

    /**
     * 约定的部署时区。
     *
     * <p>迁移到其它时区时，改这一个常量，然后让测试告诉你还有哪几个文件要跟着改。</p>
     */
    private static final String EXPECTED_ZONE = "Asia/Shanghai";

    /** 与 {@link #EXPECTED_ZONE} 表达同一事实的 UTC 偏移，供前端无 tzdata 的场景使用 */
    private static final String EXPECTED_OFFSET = "+08:00";

    private static final Path DOCKERFILE = Path.of("Dockerfile");
    private static final Path COMPOSE = Path.of("docker-compose.yml");
    private static final Path FRONTEND_TIME = Path.of("devops-platform-frontend/src/utils/time.ts");

    @Test
    @DisplayName("Dockerfile 把容器时区固定为约定时区")
    void dockerfilePinsTimeZone() throws IOException {
        String s = read(DOCKERFILE);

        // 两处都要：/etc/localtime 决定 JVM 的 ZoneId.systemDefault()，
        // /etc/timezone 决定容器内命令行工具的显示。只设其一会让日志与业务时间对不上
        assertThat(s)
                .as("Dockerfile 必须把 /etc/localtime 指向 %s——"
                        + "它决定 JVM 的 ZoneId.systemDefault()，"
                        + "而全部业务时间列都由 LocalDateTime.now() 写入", EXPECTED_ZONE)
                .contains("/usr/share/zoneinfo/" + EXPECTED_ZONE);
        assertThat(s)
                .as("Dockerfile 必须写 /etc/timezone 为 %s", EXPECTED_ZONE)
                .contains("\"" + EXPECTED_ZONE + "\" > /etc/timezone");
    }

    @Test
    @DisplayName("docker-compose 里 app 与 postgres 都设了约定时区")
    void composeSetsTimeZoneForAppAndPostgres() throws IOException {
        String s = read(COMPOSE);

        // 断言落在「出现次数 ≥ 2」而非「包含一次」：
        // 只给 app 设而漏掉 postgres 时，CURRENT_TIMESTAMP 写的是 UTC，
        // 与 LocalDateTime.now() 写的本地时间在同一张表里差 8 小时——
        // 恰是本轮修掉的那个缺陷的另一种成因。
        // 只验 contains 抓不到「漏了一个服务」
        int occurrences = countOccurrences(s, "TZ: " + EXPECTED_ZONE);
        assertThat(occurrences)
                .as("docker-compose 中 app 与 postgres 两个服务都必须设 TZ: %s。"
                        + "只给 app 设会让数据库的 CURRENT_TIMESTAMP 写 UTC，"
                        + "与应用写入的本地时间在同一张表里差 8 小时，且无任何报错",
                        EXPECTED_ZONE)
                .isGreaterThanOrEqualTo(2);

        // 进一步定位到具体服务块，防止两次 TZ 都落在同一个服务上
        assertThat(serviceBlock(s, "app"))
                .as("docker-compose 的 app 服务必须设 TZ: %s", EXPECTED_ZONE)
                .contains("TZ: " + EXPECTED_ZONE);
        assertThat(serviceBlock(s, "postgres"))
                .as("docker-compose 的 postgres 服务必须设 TZ: %s——"
                        + "数据库的 CURRENT_TIMESTAMP 默认值依赖它", EXPECTED_ZONE)
                .contains("TZ: " + EXPECTED_ZONE);
    }

    @Test
    @DisplayName("前端 time.ts 的服务器时区常量与后端部署时区表达同一事实")
    void frontendConstantsMatchDeploymentZone() throws IOException {
        String s = read(FRONTEND_TIME);

        assertThat(s)
                .as("前端 parseDate 用 SERVER_UTC_OFFSET 解释后端下发的无时区时间串，"
                        + "它必须等于部署时区 %s 的偏移 %s。不一致会让页面上所有时间"
                        + "整体偏移，而没有任何报错", EXPECTED_ZONE, EXPECTED_OFFSET)
                .contains("SERVER_UTC_OFFSET = '" + EXPECTED_OFFSET + "'");
        assertThat(s)
                .as("前端 SERVER_TIME_ZONE 必须与部署时区一致")
                .contains("SERVER_TIME_ZONE = '" + EXPECTED_ZONE + "'");
    }

    @Test
    @DisplayName("告警时间转换不得硬编码 UTC —— 钉住本轮修复，防回退")
    void alertServiceDoesNotConvertToUtc() throws IOException {
        Path alertService = Path.of(
                "src/main/java/com/devops/agent/domain/alert/service/AlertService.java");
        String s = read(alertService);

        // 只看代码行，不看注释——本轮的修复注释里正引用了错误写法说明成因，
        // 不排除注释会让扫描全中（静默 catch 扫描器踩过这个坑）
        String code = stripComments(s);

        assertThat(code)
                .as("AlertService 不得把 Alertmanager 时间转成 UTC 存："
                        + "first_occurred_at 是无时区 TIMESTAMP，同表其它列都是本地时间，"
                        + "转 UTC 会让同一行里两列差 8 小时，详情页显示「刚触发的告警已持续 8 小时」")
                .doesNotContain("atZoneSameInstant(ZoneOffset.UTC)")
                .doesNotContain("atZoneSameInstant(java.time.ZoneOffset.UTC)");

        assertThat(code)
                .as("必须用 systemDefault 跟随部署时区，而非硬编码某个偏移——"
                        + "硬编码会在部署到其它时区时重新制造同一偏差，且更隐蔽")
                .contains("ZoneId.systemDefault()");
    }

    // ==================== 辅助 ====================

    private static String read(Path p) throws IOException {
        assertThat(Files.exists(p))
                .as("契约文件 %s 不存在——它被移动或删除时本测试必须失败，"
                        + "而不是静默跳过检查", p)
                .isTrue();
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    private static int countOccurrences(String haystack, String needle) {
        int n = 0;
        int i = haystack.indexOf(needle);
        while (i >= 0) {
            n++;
            i = haystack.indexOf(needle, i + needle.length());
        }
        return n;
    }

    /**
     * 截取 docker-compose 里某个服务的定义块。
     *
     * <p>从 {@code "^  <name>:"} 起，到下一个同级键（两空格缩进）为止。
     * 不做完整 YAML 解析——测试不该引入新依赖，而这个结构足够规整。</p>
     */
    private static String serviceBlock(String compose, String service) {
        Matcher m = Pattern.compile("(?m)^  " + Pattern.quote(service) + ":\\s*$")
                .matcher(compose);
        assertThat(m.find())
                .as("docker-compose 中找不到服务 %s——服务被重命名时本测试必须失败", service)
                .isTrue();
        int start = m.end();
        Matcher next = Pattern.compile("(?m)^  [A-Za-z_][\\w-]*:\\s*$").matcher(compose);
        int end = compose.length();
        if (next.find(start)) {
            end = next.start();
        }
        return compose.substring(start, end);
    }

    /**
     * 去掉 Java 注释，只留代码。
     *
     * <p>必要性：本轮修复的 javadoc 里明确引用了错误写法
     * {@code atZoneSameInstant(ZoneOffset.UTC)} 来说明成因。
     * 不剥注释，防回退断言会被自己的说明文字打中，永远失败。</p>
     */
    private static String stripComments(String src) {
        // 先块注释（含 javadoc），再行注释。顺序不能反：
        // 行注释里可能出现 "/*"，先删行注释会破坏块注释的配对
        String noBlock = src.replaceAll("(?s)/\\*.*?\\*/", "");
        return noBlock.replaceAll("(?m)//.*$", "");
    }
}
