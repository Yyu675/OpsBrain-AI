package com.devops.agent.contract;

import com.devops.agent.controller.dto.AlertDto;
import com.devops.agent.domain.alert.entity.Alert;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 告警响应 DTO 的字段契约（P0-2b 第三步）。
 *
 * <h3>这组断言防什么</h3>
 * <p>
 * record 的字段名<b>就是 JSON 的键</b>，也就是已冻结的前后端契约。
 * 前端 {@code alerts.ts} 的 {@code fetchAlerts} 直接读
 * {@code data.alerts} / {@code data.totalPages}——改个名不会有编译错误
 * （后端自己编译得过），<b>只是前端悄悄拿到 undefined</b>，
 * 值班工程师看到的告警列表是空的。
 * </p>
 * <p>
 * 这正是 {@code TicketDtoContractTest} 的同款保护（P0-2 第二步引入了 record，
 * 但跨端的名字约定仍需要测试来钉）。
 * </p>
 *
 * @author OpsBrain AI
 * @since 2026-09-07
 */
@DisplayName("告警响应 DTO 字段契约")
class AlertDtoContractTest {

    @Nested
    @DisplayName("AlertPage：与前端 alerts.ts 的冻结约定")
    class Page {

        /**
         * 前端逐字段读取的键。
         *
         * <p>来源：{@code devops-platform-frontend/src/api/alerts.ts}
         * 里 {@code fetchAlerts} 的 {@code data.alerts} / {@code data.totalPages}
         * 等直接访问。</p>
         */
        private static final List<String> FROZEN_FIELDS =
                List.of("alerts", "total", "page", "size", "totalPages");

        @Test
        @DisplayName("字段名与顺序都不得改动 —— 它们是 JSON 的键")
        void fieldNamesAreFrozen() {
            RecordComponent[] components = AlertDto.AlertPage.class.getRecordComponents();
            List<String> actual = Arrays.stream(components).map(RecordComponent::getName).toList();

            assertThat(actual)
                    .as("AlertPage 的字段名就是响应 JSON 的键，前端 alerts.ts "
                            + "直接读 data.alerts / data.totalPages。改名不会有编译错误"
                            + "（后端自己编译得过），只是前端悄悄拿到 undefined，"
                            + "值班工程师看到的告警列表是空的")
                    .containsExactlyElementsOf(FROZEN_FIELDS);
        }

        @Test
        @DisplayName("totalPages 向上取整 —— 余数页不能被丢掉")
        void totalPagesRoundsUp() {
            // 21 条 / 每页 10 → 3 页。用整数除法会得 2，最后 1 条永远翻不到。
            // 这是原 Map 实现就用的公式，换 record 时不得顺手「优化」成整除
            AlertDto.AlertPage page =
                    AlertDto.AlertPage.of(List.of(), 21, 1, 10);
            assertThat(page.totalPages())
                    .as("21 条数据每页 10 条应为 3 页。整数除法会得 2，"
                            + "第 21 条告警值班工程师永远看不到")
                    .isEqualTo(3);
        }

        @Test
        @DisplayName("整除时不多出空页")
        void exactDivisionHasNoExtraPage() {
            // 与上一条构成分叉：无脑 +1 的实现会在这里得 3
            assertThat(AlertDto.AlertPage.of(List.of(), 20, 1, 10).totalPages())
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("零结果时页数为 0，不伪造一个空页")
        void emptyResultHasZeroPages() {
            // 返回 1 会让前端分页器显示「第 1/1 页」，
            // 而列表是空的——用户以为数据加载失败
            assertThat(AlertDto.AlertPage.of(List.of(), 0, 1, 10).totalPages())
                    .isEqualTo(0);
        }

        @Test
        @DisplayName("of() 原样保留传入的 page/size —— 它们是钳制后的生效值")
        void preservesEffectivePaging() {
            // controller 已把越界值钳制过（page≥1，size≤200），
            // DTO 不得再做二次调整，否则响应里回报的值与实际查询用的不一致
            AlertDto.AlertPage p = AlertDto.AlertPage.of(List.of(), 100, 3, 25);
            assertThat(p.page()).isEqualTo(3);
            assertThat(p.size()).isEqualTo(25);
            assertThat(p.total()).isEqualTo(100);
        }

        @Test
        @DisplayName("alerts 原样透传，不做过滤或排序")
        void alertsPassThrough() {
            // 排序已下沉 SQL。DTO 里再动一次会让跨页顺序前后不一致
            Alert a1 = new Alert();
            a1.setId(1L);
            Alert a2 = new Alert();
            a2.setId(2L);

            AlertDto.AlertPage p = AlertDto.AlertPage.of(List.of(a1, a2), 2, 1, 10);
            assertThat(p.alerts()).extracting(Alert::getId)
                    .containsExactly(1L, 2L);
        }
    }
}
