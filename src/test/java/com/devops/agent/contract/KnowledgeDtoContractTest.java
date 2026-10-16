package com.devops.agent.contract;

import com.devops.agent.controller.dto.KnowledgeDocDto;
import com.devops.agent.controller.dto.TicketDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 知识库响应 DTO 的字段契约（P0-2 第二步 · 知识库模块）。
 *
 * <h3>背景</h3>
 * <p>
 * 接入 springdoc 后实测 130 个端点里 70 个返回 {@code Map<String,Object>}，
 * OpenAPI 只能生成 {@code additionalProperties: true}——等于没有契约。
 * 本轮把知识库列表端点换成 record。
 * </p>
 *
 * <h3>本模块的特殊之处：字段名与工单列表不一致，且不能统一</h3>
 * <p>
 * 知识库用 {@code content}/{@code totalElements}/{@code currentPage}/{@code pageSize}，
 * 工单用 {@code tickets}/{@code total}/{@code page}/{@code size}。
 * 看起来该统一，但前端 {@code stores/knowledge.ts} 逐字段读取这四个键，
 * <b>改名不会有任何编译错误，只会让知识库列表渲染成空</b>。
 * </p>
 * <p>
 * 本测试特意加一条断言把「两套命名都冻结」这件事写死，
 * 免得后人（包括我自己）看到不一致就顺手统一——
 * 那属于破坏性变更，要前后端一起改并同时发版。
 * </p>
 *
 * @author OpsBrain AI
 * @since 2026-08-31
 */
@DisplayName("知识库响应 DTO 字段契约")
class KnowledgeDtoContractTest {

    @Nested
    @DisplayName("DocPage：与前端 stores/knowledge.ts 的冻结约定")
    class Page {

        /**
         * 前端逐字段读取的键。
         *
         * <p>来源：{@code devops-platform-frontend/src/stores/knowledge.ts}
         * 里的 {@code result.content} / {@code result.totalElements} /
         * {@code result.currentPage} / {@code result.pageSize}。</p>
         */
        private static final List<String> FROZEN_FIELDS = List.of(
                "content", "totalElements", "totalPages", "currentPage", "pageSize");

        @Test
        @DisplayName("字段名与顺序都不得改动 —— 它们是 JSON 的键")
        void fieldNamesAreFrozen() {
            List<String> actual = Arrays.stream(
                            KnowledgeDocDto.DocPage.class.getRecordComponents())
                    .map(RecordComponent::getName).toList();

            assertThat(actual)
                    .as("DocPage 的字段名就是响应 JSON 的键，前端 stores/knowledge.ts "
                            + "直接读 result.content / result.totalElements。"
                            + "改名不会有编译错误，只会让知识库列表渲染成空")
                    .containsExactlyElementsOf(FROZEN_FIELDS);
        }

        @Test
        @DisplayName("totalPages 向上取整 —— 余数页不能被丢掉")
        void totalPagesRoundsUp() {
            // 21 篇 / 每页 10 → 3 页。整数除法得 2，最后 1 篇永远翻不到
            assertThat(KnowledgeDocDto.DocPage.of(List.of(), 21, 1, 10).totalPages())
                    .as("21 篇文档每页 10 篇应为 3 页。整数除法会得 2，"
                            + "第 21 篇用户永远看不到")
                    .isEqualTo(3);
        }

        @Test
        @DisplayName("整除时不多出空页")
        void exactDivisionHasNoExtraPage() {
            // 与上一条构成分叉：无脑 +1 的实现会在这里得 3
            assertThat(KnowledgeDocDto.DocPage.of(List.of(), 20, 1, 10).totalPages())
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("零结果时页数为 0，不伪造一个空页")
        void emptyResultHasZeroPages() {
            // 返回 1 会让分页器显示「第 1/1 页」而列表是空的，
            // 用户以为加载失败
            assertThat(KnowledgeDocDto.DocPage.of(List.of(), 0, 1, 10).totalPages())
                    .isEqualTo(0);
        }

        @Test
        @DisplayName("currentPage/pageSize 原样保留 —— 它们是钳制后的生效值")
        void preservesEffectivePaging() {
            // controller 已钳制（page≥1、size≤200）。DTO 再调整会让响应回报的值
            // 与实际查询用的不一致，前端据此算偏移量就会错位
            KnowledgeDocDto.DocPage p = KnowledgeDocDto.DocPage.of(List.of(), 100, 3, 25);
            assertThat(p.currentPage()).isEqualTo(3);
            assertThat(p.pageSize()).isEqualTo(25);
            assertThat(p.totalElements()).isEqualTo(100);
        }
    }

    @Nested
    @DisplayName("跨模块：两套分页命名都已冻结，不得「顺手统一」")
    class NamingDivergence {

        @Test
        @DisplayName("知识库与工单的分页字段名刻意不同，两边都不能改")
        void twoNamingSchemesBothFrozen() {
            List<String> doc = Arrays.stream(
                            KnowledgeDocDto.DocPage.class.getRecordComponents())
                    .map(RecordComponent::getName).toList();
            List<String> ticket = Arrays.stream(
                            TicketDto.TicketPage.class.getRecordComponents())
                    .map(RecordComponent::getName).toList();

            // 这条断言的作用是「留个记号」：它一旦失败，说明有人把两套命名统一了。
            // 统一本身不是坏事，但那是破坏性变更——前端 knowledge.ts 与
            // ticket.service.ts 必须同时改并同版发布。
            // 若确实要做，请连同前端改动一起提交，并删掉这条断言。
            assertThat(doc)
                    .as("知识库分页用 content/totalElements/currentPage/pageSize，"
                            + "工单用 tickets/total/page/size。两套命名不一致是既成事实，"
                            + "统一它们属于破坏性变更（前端两处 store 逐字段读取），"
                            + "不应混在「Map 换 record」这类零行为改动里做")
                    .isNotEqualTo(ticket);

            // 各自的关键键必须还在
            assertThat(doc).contains("content", "totalElements");
            assertThat(ticket).contains("tickets", "total");
        }

        @Test
        @DisplayName("两者的 totalPages 口径一致 —— 唯一该共享的语义")
        void totalPagesSemanticsAligned() {
            // 命名可以不同，但「21 条每页 10 → 3 页」这个算法必须一致，
            // 否则同一个前端分页组件在两个页面上行为不同
            int docPages = KnowledgeDocDto.DocPage.of(List.of(), 21, 1, 10).totalPages();
            int ticketPages = TicketDto.TicketPage.of(List.of(), 21, 1, 10).totalPages();
            assertThat(docPages)
                    .as("两个模块的总页数算法必须一致，否则同一个分页组件"
                            + "在知识库与工单页表现不同")
                    .isEqualTo(ticketPages)
                    .isEqualTo(3);
        }
    }
}
