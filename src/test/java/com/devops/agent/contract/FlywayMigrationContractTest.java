package com.devops.agent.contract;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Flyway 迁移文件契约（S0-1，路线图 §4.1）。
 *
 * <h3>要防住什么</h3>
 * <ul>
 *   <li><b>命名违例</b>：Flyway 只识别 {@code V{版本}__描述.sql}。写错的文件
 *       （{@code V2_xxx.sql}、{@code v3__..sql}、缺描述）会被<b>静默跳过</b>——
 *       schema 永远落后一版，且没有任何报错。这是最阴险的一种死法；</li>
 *   <li><b>版本号冲突</b>：两个文件同版本号，Flyway 直接拒绝所有迁移，
 *       应用起不来——但其因常被误以为「数据库坏了」；</li>
 *   <li><b>第二真相源回潮</b>：{@code sql/} 顶层目录若再次出现建表 DDL，
 *       就回到了 S0-1 之前的双真相源状态（同一份 schema 写两处必然漂移，
 *       历史上 v24/v25 真实漂过两次，见 AGENTS §3.5）。{@code sql/} 此后只许
 *       放数据脚本（{@code mock_data.sql} 之类）；</li>
 *   <li><b>向量维度漂移</b>：基线里的 {@code VECTOR(1536)} 必须与
 *       {@code devops.ai.vector.dimension} 保持一致（AGENTS §3.3 铁律。
 *       维度变更本就要求新增迁移并同步两处，此断言是这次同步的提醒器）。</li>
 * </ul>
 *
 * <h3>为什么扫文件系统而不是跑 Flyway</h3>
 * <p>
 * 这类缺陷的破坏发生在「某台库的启动瞬间」，测试环境下 flyway 自己都
 * 是绿的，只有对文件本身的静态检查能在合并前拦住它们。
 * </p>
 *
 * @author OpsBrain AI
 * @since 2026-09-07（S0-1）
 */
class FlywayMigrationContractTest {

    private static final Path MIGRATION_DIR = Path.of("src/main/resources/db/migration");
    private static final Path SQL_DIR = Path.of("sql");
    private static final Pattern VERSIONED_NAME = Pattern.compile("^V(\\d+)__.+\\.sql$");
    private static final Pattern CREATE_OR_ALTER = Pattern.compile(
            "(?im)^\\s*(CREATE|ALTER)\\s+(OR\\s+REPLACE\\s+)?TABLE\\b");

    @Test
    @DisplayName("每个迁移文件名都符合 V{n}__描述.sql，版本号唯一，且 V1 基线存在")
    void migrationFilenamesFollowFlywayConvention() throws IOException {
        assertThat(Files.isDirectory(MIGRATION_DIR))
                .as("迁移目录必须存在：%s（schema 唯一真相源，AGENTS §3.5）", MIGRATION_DIR)
                .isTrue();

        List<Path> sqlFiles;
        try (Stream<Path> s = Files.list(MIGRATION_DIR)) {
            sqlFiles = s.filter(p -> p.getFileName().toString().endsWith(".sql")).toList();
        }
        assertThat(sqlFiles).as("迁移目录至少应有 V1__baseline.sql").isNotEmpty();

        Set<Integer> versions = new HashSet<>();
        boolean baselinePresent = false;
        for (Path f : sqlFiles) {
            String name = f.getFileName().toString();
            Matcher m = VERSIONED_NAME.matcher(name);
            assertThat(m.matches())
                    .as("迁移文件名违例：%s —— Flyway 会静默跳过不匹配 V{n}__描述.sql 的文件，"
                            + "schema 将永远缺失该版本且无报错", name)
                    .isTrue();
            int version = Integer.parseInt(m.group(1));
            assertThat(versions.add(version))
                    .as("迁移版本号冲突：V%d 出现多次（%s）——Flyway 会拒绝启动全部迁移", version, name)
                    .isTrue();
            if (version == 1) {
                baselinePresent = true;
            }
        }
        assertThat(baselinePresent)
                .as("必须存在 V1 基线（S0-1 的单文件基线 V1__baseline.sql）")
                .isTrue();
    }

    @Test
    @DisplayName("sql/ 顶层目录不出现建表 DDL（杜绝第二真相源回潮；数据脚本除外）")
    void noSchemaDdlOutsideFlywayMigrations() throws IOException {
        try (Stream<Path> s = Files.list(SQL_DIR)) {
            for (Path f : s.filter(p -> p.getFileName().toString().endsWith(".sql")
                    && Files.isRegularFile(p)).toList()) {
                String body = Files.readString(f, StandardCharsets.UTF_8);
                assertThat(CREATE_OR_ALTER.matcher(body).find())
                        .as("%s 含建表/改表 DDL —— schema 的唯一真相源是 src/main/resources/db/migration/，"
                                + "sql/ 目录只许放数据脚本（如 mock_data.sql），见 AGENTS §3.5", f)
                        .isFalse();
            }
        }
    }

    @Test
    @DisplayName("V1 基线建出 pgvector 扩展且向量列维度为 1536（AGENTS §3.3 铁律）")
    void baselineCreatesVectorExtensionWithCorrectDimension() throws IOException {
        Path v1 = MIGRATION_DIR.resolve("V1__baseline.sql");
        assertThat(v1).withFailMessage("V1__baseline.sql 必须位于 %s", MIGRATION_DIR).exists();
        String body = Files.readString(v1, StandardCharsets.UTF_8);

        assertThat(body).as("V1 基线必须启用 pgvector 扩展")
                .containsIgnoringCase("CREATE EXTENSION");
        assertThat(body).as("V1 基线的向量列必须是 VECTOR(1536) —— "
                        + "若改维度，请同步 V1__baseline.sql、向量配置与 Embedding 模型（AGENTS §3.3 三处联动）")
                .contains("VECTOR(1536)");
    }
}
