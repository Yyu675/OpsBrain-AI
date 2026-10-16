package com.devops.agent.domain.biz.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 引用串解析单测（反馈回流「后端自取引用」的入口解析）。
 *
 * <h3>为什么单独测解析</h3>
 * 引用在链路上有两处产出方：后端 {@code RetrievedChunk.citation()}（带
 * 【来源：】包裹）与前端 {@code extractCitationsFromText}（剥壳后重拼）。
 * 解析必须同时吃下两种格式——任何一侧格式漂移而解析不跟，回流会静默归零：
 * 反馈照常「成功」，boost 表毫无变化，没有任何报错会暴露它。
 *
 * <p>DB 命中行为由 {@link KnowledgeBoostResolveIntegrationTest} 覆盖；
 * 本类只测无库依赖的纯函数。</p>
 */
@DisplayName("引用解析：unwrap / split（回流入口的格式兼容）")
class KnowledgeBoostCitationParseTest {

    // ==================== unwrapCitation ====================

    @Test
    @DisplayName("带【来源：】包裹剥壳返回内层")
    void unwrapsWrappedCitation() {
        assertThat(KnowledgeBoostRepository.unwrapCitation("【来源：K8s手册 - 排查步骤】"))
                .isEqualTo("K8s手册 - 排查步骤");
    }

    @Test
    @DisplayName("无包裹原样返回（前端 extractCitationsFromText 的产出是裸串）")
    void bareStringPassesThrough() {
        assertThat(KnowledgeBoostRepository.unwrapCitation("K8s手册 - 排查步骤"))
                .isEqualTo("K8s手册 - 排查步骤");
    }

    @Test
    @DisplayName("前后空白被裁掉")
    void trimsWhitespace() {
        assertThat(KnowledgeBoostRepository.unwrapCitation("  【来源：手册A】  "))
                .isEqualTo("手册A");
    }

    @Test
    @DisplayName("null / 空串 / 退化包裹返回空串（调用方按跳过处理）")
    void degenerateInputsReturnEmpty() {
        assertThat(KnowledgeBoostRepository.unwrapCitation(null)).isEmpty();
        assertThat(KnowledgeBoostRepository.unwrapCitation("")).isEmpty();
        assertThat(KnowledgeBoostRepository.unwrapCitation("   ")).isEmpty();
        // 只有括号没有内容：不是合法包裹，原样保留（长度守卫防 substring 越界）
        assertThat(KnowledgeBoostRepository.unwrapCitation("【来源：】")).isEqualTo("【来源：】");
    }

    @Test
    @DisplayName("无章节的包裹引用剥壳后不含分隔符")
    void unwrapsSectionlessCitation() {
        assertThat(KnowledgeBoostRepository.unwrapCitation("【来源：Redis主从SOP】"))
                .isEqualTo("Redis主从SOP");
    }

    // ==================== splitCitation ====================

    @Test
    @DisplayName("首个 ' - ' 拆标题与章节——与前端 [^-】]+ 取题一致")
    void splitsTitleAndSectionAtFirstSeparator() {
        var ref = KnowledgeBoostRepository.splitCitation("K8s手册 - 排查步骤");
        assertThat(ref.title()).isEqualTo("K8s手册");
        assertThat(ref.section()).isEqualTo("排查步骤");
    }

    @Test
    @DisplayName("无分隔符：整串为标题，section=null（调用方走文档级命中）")
    void noSeparatorMeansSectionNull() {
        var ref = KnowledgeBoostRepository.splitCitation("Redis主从SOP");
        assertThat(ref.title()).isEqualTo("Redis主从SOP");
        assertThat(ref.section()).isNull();
    }

    @Test
    @DisplayName("多个分隔符只按第一个切——章节自身含 ' - ' 时保留在 section 侧")
    void multipleSeparatorsSplitAtFirstOnly() {
        // 拆成 (title=A, section=B - C)：section 整段参与 DB 归一比对，
        // 若误按最后一个 ' - ' 切，title 会变成 "A - B"，三个回退全落空
        var ref = KnowledgeBoostRepository.splitCitation("A - B - C");
        assertThat(ref.title()).isEqualTo("A");
        assertThat(ref.section()).isEqualTo("B - C");
    }

    @Test
    @DisplayName("标题开头即分隔符（idx<=0）按整串处理，不产生空标题切片")
    void leadingSeparatorDoesNotProduceEmptyTitle() {
        var ref = KnowledgeBoostRepository.splitCitation(" - 章节");
        // indexOf(" - ") == 0 → 视为无分隔：整串是标题（空标题由 queryIds 再兜底）
        assertThat(ref.title()).isEqualTo(" - 章节");
        assertThat(ref.section()).isNull();
    }
}
