package com.devops.agent.contract;

import com.devops.agent.controller.dto.TicketDto;
import com.devops.agent.domain.biz.entity.DevOpsTicket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工单响应 DTO 的字段契约（P0-2 第二步）。
 *
 * <h3>背景：为什么把 Map 换成 record</h3>
 * <p>
 * 接入 springdoc 后实测，130 个端点里 70 个返回 {@code Map<String,Object>}，
 * OpenAPI 对这些只能生成 {@code additionalProperties: true}——<b>等于没有契约</b>。
 * 换成 record 才能让前端用 {@code openapi-typescript} 自动生成类型。
 * </p>
 *
 * <h3>这组断言防什么</h3>
 * <p>
 * record 的字段名<b>就是 JSON 的键</b>，也就是已冻结的前后端契约。
 * 前端 {@code ticket.service.ts} 直接读 {@code data.tickets} 与
 * {@code data.totalPages}——改个名不会有编译错误（后端自己编译得过），
 * <b>只是前端悄悄拿到 undefined</b>，列表渲染成空、分页失效。
 * </p>
 * <p>
 * 这正是换 record 想解决的问题的另一面：record 让<b>后端内部</b>
 * 改名有编译信号，但<b>跨端</b>的名字约定仍需要测试来钉。
 * </p>
 *
 * @author OpsBrain AI
 * @since 2026-08-31
 */
@DisplayName("工单响应 DTO 字段契约")
class TicketDtoContractTest {

    @Nested
    @DisplayName("TicketPage：与前端 ticket.service.ts 的冻结约定")
    class Page {

        /**
         * 前端逐字段读取的键。
         *
         * <p>来源：{@code devops-platform-frontend/src/api/services/ticket.service.ts}
         * 里的 {@code data.tickets} / {@code data.totalPages} 等直接访问。</p>
         */
        private static final List<String> FROZEN_FIELDS =
                List.of("tickets", "total", "page", "size", "totalPages");

        @Test
        @DisplayName("字段名与顺序都不得改动 —— 它们是 JSON 的键")
        void fieldNamesAreFrozen() {
            RecordComponent[] components = TicketDto.TicketPage.class.getRecordComponents();
            List<String> actual = Arrays.stream(components).map(RecordComponent::getName).toList();

            assertThat(actual)
                    .as("TicketPage 的字段名就是响应 JSON 的键，前端 ticket.service.ts "
                            + "直接读 data.tickets / data.totalPages。改名不会有编译错误"
                            + "（后端自己编译得过），只是前端悄悄拿到 undefined，"
                            + "列表渲染成空、分页失效")
                    .containsExactlyElementsOf(FROZEN_FIELDS);
        }

        @Test
        @DisplayName("totalPages 向上取整 —— 余数页不能被丢掉")
        void totalPagesRoundsUp() {
            // 21 条 / 每页 10 → 3 页。用整数除法会得 2，最后 1 条永远翻不到
            TicketDto.TicketPage page =
                    TicketDto.TicketPage.of(List.of(), 21, 1, 10);
            assertThat(page.total_pages())
                    .as("21 条数据每页 10 条应为 3 页。整数除法会得 2，"
                            + "第 21 条工单用户永远看不到")
                    .isEqualTo(3);
        }

        @Test
        @DisplayName("整除时不多出空页")
        void exactDivisionHasNoExtraPage() {
            // 与上一条构成分叉：无脑 +1 的实现会在这里得 3
            assertThat(TicketDto.TicketPage.of(List.of(), 20, 1, 10).total_pages())
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("零结果时页数为 0，不伪造一个空页")
        void emptyResultHasZeroPages() {
            // 返回 1 会让前端分页器显示「第 1/1 页」，
            // 而列表是空的——用户以为数据加载失败
            assertThat(TicketDto.TicketPage.of(List.of(), 0, 1, 10).total_pages())
                    .isEqualTo(0);
        }

        @Test
        @DisplayName("of() 原样保留传入的 page/size —— 它们是钳制后的生效值")
        void preservesEffectivePaging() {
            // controller 已把越界值钳制过（page≥1，size≤200），
            // DTO 不得再做二次调整，否则响应里回报的值与实际查询用的不一致
            TicketDto.TicketPage p = TicketDto.TicketPage.of(List.of(), 100, 3, 25);
            assertThat(p.page()).isEqualTo(3);
            assertThat(p.size()).isEqualTo(25);
            assertThat(p.total()).isEqualTo(100);
        }

        @Test
        @DisplayName("tickets 原样透传，不做过滤或排序")
        void ticketsPassThrough() {
            // 排序与筛选已下沉 SQL（见 TicketController 注释）。
            // DTO 里再动一次会让「按优先级排序」在跨页时前后不一致
            DevOpsTicket t1 = new DevOpsTicket();
            t1.setId("TKT-1");
            DevOpsTicket t2 = new DevOpsTicket();
            t2.setId("TKT-2");

            TicketDto.TicketPage p = TicketDto.TicketPage.of(List.of(t1, t2), 2, 1, 10);
            assertThat(p.tickets()).extracting(DevOpsTicket::getId)
                    .containsExactly("TKT-1", "TKT-2");
        }
    }
}
