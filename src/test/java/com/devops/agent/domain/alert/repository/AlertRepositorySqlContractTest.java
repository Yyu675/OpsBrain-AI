package com.devops.agent.domain.alert.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code AlertRepository.insertOrIncrement} SQL 形状契约（2026-10-02，缺陷类钉子）。
 *
 * <h3>为什么要有</h3>
 * 方案①（冲突刷源真值）落库时把 {@code ON CONFLICT (dedup_key)} 前缀插重了一次，
 * SQL 变成双 {@code ON CONFLICT} 从句 → <b>语法错误，所有真实告警入库持续失败</b>，
 * 被逐条护身 catch 吞成 200，Alertmanager 对 200 按成功处理不再重投——告警静默丢失。
 * 单元测试 mock 了 {@code JdbcTemplate}，<b>永远执行不到真实 SQL</b>，
 * 66 个绿测试拦不住一条语法错误；真机钻 E 第一发才撞出来。
 * </p>
 * <p>本测试扫源码字符串拼接区：{@code "ON CONFLICT} 字面量恰好 1 次——
 * 与 SilentCatch/KnowledgeWriteGuard 同族：源文件即测试对象，防复发不防表演。</p>
 */
@DisplayName("告警 upsert SQL 形状（双 ON CONFLICT = 所有告警入库语法失败）")
class AlertRepositorySqlContractTest {

    private static final Path REPO_SRC = Path.of(
            "src/main/java/com/devops/agent/domain/alert/repository/AlertRepository.java");

    @Test
    void insertOrIncrementHasSingleOnConflict() throws IOException {
        assertThat(Files.exists(REPO_SRC))
                .as("AlertRepository 源文件不可达——路径挪动时本测试必须失败而非静默跳过")
                .isTrue();

        String src = Files.readString(REPO_SRC, StandardCharsets.UTF_8);

        // 字符串字面量形式的 ON CONFLICT（带前导双引号）= 真正进 SQL 的片段。
        // 注释里的 "ON CONFLICT ... 谓词" 不带前导引号，不计入
        int literalCount = countOccurrences(src, "\"ON CONFLICT");
        assertThat(literalCount)
                .as("insertOrIncrement 的 SQL 字面量里 \"ON CONFLICT 只能出现 1 次——"
                        + "出现 2 次 = 部分索引谓词推断与 DO UPDATE 前缀被插重，"
                        + "SQL 语法错误会让所有告警入库静默失败（2026-10-02 实锤）")
                .isEqualTo(1);

        // 配套断言：冲突目标与谓词必须连续——它们之间再插任何东西都是语法错
        assertThat(src)
                .contains("ON CONFLICT (dedup_key) WHERE status IN ('FIRING','ACKNOWLEDGED') DO UPDATE SET");
    }

    private static int countOccurrences(String haystack, String needle) {
        int n = 0;
        int idx = haystack.indexOf(needle);
        while (idx >= 0) {
            n++;
            idx = haystack.indexOf(needle, idx + needle.length());
        }
        return n;
    }
}
