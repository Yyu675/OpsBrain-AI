package com.devops.agent.domain.biz.repository;

import com.devops.agent.support.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 引用 → chunk 解析回查的 DB 集成测试（Testcontainers pgvector）。
 *
 * <h3>为什么必须落 DB 测</h3>
 * 解析纯函数只负责拆字符串，真正的行为在三级回退 SQL：
 * 标题+章节精确 → 整串当标题 → 标题级兜底。三级的<b>先后顺序</b>与
 * ACTIVE/生效窗口过滤任何一处写错，表现都是「回流静默归零」——
 * 反馈接口照常返回成功，boost 表没有变化，线上不会有任何报错。
 * 只测解析函数抓不到这类缺陷。
 *
 * <p>共享容器数据隔离（AbstractIntegrationTest 约束）：本测试的夹具
 * 使用专属 doc_title 前缀，Before/After 双向清理，不依赖执行顺序。</p>
 */
@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "devops.ai.mode=MOCK"
})
@DisplayName("引用回查 chunk：三级回退 + ACTIVE 过滤（反馈回流后端自取引用）")
class KnowledgeBoostResolveIntegrationTest extends AbstractIntegrationTest {

    /** 专属前缀：与容器内其他测试的数据天然隔离 */
    private static final String DOC_A = "回流解析测试手册";
    private static final String DOC_B = "回流解析测试副册";
    private static final String DOC_DASH = "回流解析 - 带横线手册";

    @Autowired
    private KnowledgeBoostRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long sectionInvestigate;
    private long sectionRollback;
    private long deprecatedChunk;
    private long plainDocChunk;
    private long dashTitleChunk;

    @BeforeEach
    void seed() {
        cleanup();
        // 非零向量：HNSW 索引插入要算距离，全零向量的余弦距离未定义
        sectionInvestigate = insertChunk(DOC_A, "## 排查步骤", "ACTIVE");
        sectionRollback = insertChunk(DOC_A, "## 回滚步骤", "ACTIVE");
        // 同标题但已废弃——任何回退路径都不该解析到它
        deprecatedChunk = insertChunk(DOC_A, "## 排查步骤", "DEPRECATED");
        plainDocChunk = insertChunk(DOC_B, null, "ACTIVE");
        dashTitleChunk = insertChunk(DOC_DASH, null, "ACTIVE");
    }

    @AfterEach
    void clean() {
        cleanup();
    }

    private void cleanup() {
        // 先断 boost 对 chunk 的引用，再删 chunk（表间无外键，顺序靠本方法自律）
        jdbcTemplate.update(
                "DELETE FROM sys_knowledge_boost WHERE chunk_id IN "
                        + "(SELECT id FROM sys_knowledge_chunk WHERE doc_title LIKE '回流解析测试%' "
                        + "   OR doc_title LIKE '回流解析 - %')");
        jdbcTemplate.update(
                "DELETE FROM sys_knowledge_chunk WHERE doc_title LIKE '回流解析测试%' "
                        + "   OR doc_title LIKE '回流解析 - %'");
    }

    private long insertChunk(String title, String section, String status) {
        StringBuilder vec = new StringBuilder("[1");
        for (int i = 1; i < 1536; i++) {
            vec.append(",0");
        }
        vec.append(']');
        Long id = jdbcTemplate.queryForObject("""
                INSERT INTO sys_knowledge_chunk
                    (doc_title, section_header, content, embedding, status)
                VALUES (?, ?, ?, (?::vector), ?)
                RETURNING id
                """, Long.class,
                title, section, "回流夹具内容-" + title + "-" + section + "-" + status, vec.toString(), status);
        return id == null ? -1 : id;
    }

    private Set<Long> resolve(String... citations) {
        return repository.resolveChunkIds(List.of(citations));
    }

    @Test
    @DisplayName("带包裹+章节：精确命中该章节切片，且不串到同标题的其他章节")
    void wrappedCitationResolvesExactSection() {
        Set<Long> hits = resolve("【来源：" + DOC_A + " - 排查步骤】");

        assertThat(hits).containsExactly(sectionInvestigate);
        assertThat(hits).doesNotContain(sectionRollback, deprecatedChunk);
    }

    @Test
    @DisplayName("裸串+章节（前端 extractCitationsFromText 的产出格式）同样命中")
    void bareCitationResolvesExactSection() {
        assertThat(resolve(DOC_A + " - 回滚步骤")).containsExactly(sectionRollback);
    }

    @Test
    @DisplayName("DB 章节带 '## ' 前缀也能对上——引用侧来自 citation() 已剥 #")
    void markdownPrefixNormalizedOnBothSides() {
        // 夹具 section_header 就是 "## 排查步骤"；上一条断言已隐含验证，
        // 这里再显式点名该行为，防止有人把 regexp_replace 删了只靠裸匹配
        Set<Long> hits = resolve("【来源：" + DOC_A + " - ## 排查步骤】");
        assertThat(hits).containsExactly(sectionInvestigate);
    }

    @Test
    @DisplayName("无章节引用：按整串标题命中（只含 ACTIVE 切片）")
    void sectionlessCitationMatchesByTitle() {
        Set<Long> hits = resolve("【来源：" + DOC_B + "】");

        assertThat(hits).containsExactly(plainDocChunk);
    }

    @Test
    @DisplayName("章节漂移（引用的章节在库中不存在）：退到文档级，但 DEPRECATED 切片不参与")
    void sectionDriftFallsBackToTitleAndSkipsDeprecated() {
        Set<Long> hits = resolve("【来源：" + DOC_A + " - 不存在的章节】");

        assertThat(hits)
                .as("三级回退第 3 级：章节对不上时整文档命中，但非 ACTIVE 一律排除")
                .containsExactlyInAnyOrder(sectionInvestigate, sectionRollback)
                .doesNotContain(deprecatedChunk);
    }

    @Test
    @DisplayName("标题本身含 ' - '（无章节）：第 1 级误拆失败后由整串回退兜住")
    void dashInsideTitleRecoveredByWholeStringFallback() {
        // inner="回流解析 - 带横线手册" 被首个 ' - ' 拆成 (回流解析, 带横线手册)，
        // 第 1 级查不到；第 2 级整串当标题必须命中——否则含横线标题的文档永远回流不到
        Set<Long> hits = resolve("【来源：" + DOC_DASH + "】");

        assertThat(hits).containsExactly(dashTitleChunk);
    }

    @Test
    @DisplayName("未知标题：空集（不是 null——调用方 for-each 不 NPE）")
    void unknownTitleYieldsEmptySet() {
        assertThat(resolve("【来源：压根不存在的手册 - 某章节】")).isEmpty();
    }

    @Test
    @DisplayName("同一引用重复出现：去重后只算一次（重复不放大 boost 计数）")
    void duplicateCitationsDeduplicated() {
        Set<Long> hits = resolve(
                "【来源：" + DOC_A + " - 排查步骤】",
                DOC_A + " - 排查步骤",
                "【来源：" + DOC_A + " - 排查步骤】");

        assertThat(hits).containsExactly(sectionInvestigate);
    }

    @Test
    @DisplayName("已过期切片（expired_at 已过）不参与回退——检索读不到的不记账")
    void expiredChunkExcluded() {
        long expired = insertChunk(DOC_B, "## 过期章节", "ACTIVE");
        jdbcTemplate.update(
                "UPDATE sys_knowledge_chunk SET expired_at = CURRENT_TIMESTAMP - INTERVAL '1 day' WHERE id = ?",
                expired);

        assertThat(resolve("【来源：" + DOC_B + " - 过期章节】")).isEmpty();
    }

    // ==================== verdict 写入语义（同仓储的另一侧） ====================

    @Test
    @DisplayName("UNHELPFUL 计 wrong+1：错误反馈能把 boost 打到 0.5")
    void unhelpfulIncrementsWrongCount() {
        repository.recordFeedback(sectionInvestigate, "UNHELPFUL");

        Double boost = repository.boostBatch(List.of(sectionInvestigate)).get(sectionInvestigate);
        assertThat(boost)
                .as("1 + ln(1+0) - 0.5×1 = 0.5：UNHELPFUL 必须真的降权")
                .isEqualTo(0.5);
    }

    @Test
    @DisplayName("连续 3 条 WRONG 后 boost 被夹到地板 0.2——不再归零/为负")
    void repeatedWrongClampsToFloor() {
        for (int i = 0; i < 3; i++) {
            repository.recordFeedback(sectionInvestigate, "WRONG");
        }

        Double boost = repository.boostBatch(List.of(sectionInvestigate)).get(sectionInvestigate);
        assertThat(boost)
                .as("3×(−0.5)=−1.5 → 原始 1−1.5=−0.5，必须被地板夹为 0.2 而非负数")
                .isEqualTo(0.2);
    }

    @Test
    @DisplayName("PARTIAL 不动计数：部分正确不是对被引片段的质量判决")
    void partialDoesNotMoveCounts() {
        repository.recordFeedback(sectionInvestigate, "PARTIAL");

        assertThat(repository.boostBatch(List.of(sectionInvestigate))
                .getOrDefault(sectionInvestigate, 1.0)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("未知判定值拒绝而非静默写 (0,0)——静默空行会让回流假成功")
    void unknownVerdictRejected() {
        assertThatThrownBy(() -> repository.recordFeedback(sectionInvestigate, "MAYBE"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MAYBE");
    }
}
