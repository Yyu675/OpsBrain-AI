package com.devops.agent.controller.dto;

import com.devops.agent.domain.alert.entity.Alert;

import java.util.List;

/**
 * 告警模块的响应 DTO（P0-2b 第三步）。
 *
 * <h3>为什么把 {@code Map<String, Object>} 换成 record</h3>
 * <p>
 * 与工单/知识库两轮（T8/T9）同一理由：OpenAPI 对 {@code Map} 只能生成
 * {@code additionalProperties: true}——<b>等于没有契约</b>；换成 record 后
 * 字段改名有编译错误兜底，前端可用 {@code openapi-typescript} 自动生成类型。
 * </p>
 *
 * <h3>字段名不得改动</h3>
 * <p>
 * record 的字段名<b>必须与原 {@code Map} 的键完全一致</b>——它们是已冻结的
 * 前后端契约。前端 {@code alerts.ts} 的 {@code fetchAlerts} 直接读
 * {@code data.alerts} / {@code data.totalPages}，改名不会有编译错误，
 * 只是前端悄悄拿到 undefined，列表渲染成空。
 * 契约测试 {@code AlertDtoContractTest} 守住这一点。
 * </p>
 *
 * <h3>为什么这个 DTO 在 controller.dto 而治理的在 domain</h3>
 * <p>
 * 分页装配位置不同：告警的 page/totalPages 由<b>控制器</b>拼装
 * （service 拆成 find/count 两个调用，与 {@code TicketController} 同款），
 * 所以 record 可以放在 controller 层；治理的 Map 是由 repository/service
 * 直接组装的，record 必须部署在 domain 层（六层依赖方向不允许
 * domain import controller）。两处的代价是一样的反模式——
 * 「record 放在哪」由「谁负责组装」决定，而不是先看包名顺眼不顺眼。
 * </p>
 *
 * @author OpsBrain AI
 * @since 2026-09-07
 */
public class AlertDto {

    private AlertDto() {
    }

    /**
     * 告警分页列表响应。
     *
     * <p>对应 {@code GET /api/v1/alerts}。前端 {@code alerts.ts}
     * 逐字段读取，字段名已冻结。</p>
     *
     * @param alerts     当前页告警
     * @param total      符合筛选条件的总数。<b>必须按与列表同一条件统计</b>，
     *                   否则页码与实际数据矛盾
     * @param page       生效页码（已钳制，可能与请求值不同）
     * @param size       生效每页大小（已钳制，上限 200）
     * @param totalPages 总页数，由 {@code total} 与 {@code size} 推导
     */
    public record AlertPage(
            List<Alert> alerts,
            long total,
            int page,
            int size,
            int totalPages
    ) {
        /**
         * 由查询结果构造，总页数在此统一推导。
         *
         * <p>与 {@code TicketDto.TicketPage.of} 同一理由：{@code Math.ceil} 的
         * 整数除法陷阱只需在一处写对。原实现（AlertQueryService 的 Map 版）
         * 用的正是这条公式，这里只是原位搬过来，不得顺手「优化」。</p>
         */
        public static AlertPage of(List<Alert> alerts, long total, int page, int size) {
            // size 已由控制器钳制为 ≥1，此处不再兜底：
            // 若真为 0 应当暴露为除零异常，而不是静默返回一个错误的页数
            int totalPages = (int) (total / size); // G-1 注入探针：整除截断
            return new AlertPage(alerts, total, page, size, totalPages);
        }
    }
}
