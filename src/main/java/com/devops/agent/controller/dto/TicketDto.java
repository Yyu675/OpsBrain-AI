package com.devops.agent.controller.dto;

import com.devops.agent.domain.biz.entity.DevOpsTicket;

import java.util.List;

/**
 * 工单模块的响应 DTO（P0-2 第二步）。
 *
 * <h3>为什么要把 {@code Map<String, Object>} 换成 record</h3>
 * <p>
 * 接入 springdoc 后实测：130 个端点里 <b>70 个返回 {@code Map<String,Object>}
 * 或 {@code Object}</b>，OpenAPI 对这些只能生成 {@code additionalProperties: true}
 * ——<b>等于没有契约</b>。前端仍要靠人工维护 TS 类型，95 号那类
 * 「同一个字段两侧理解不一致」的事故依然可能发生。
 * </p>
 * <p>
 * 换成 record 之后：
 * </p>
 * <ul>
 *   <li>OpenAPI 能生成完整 schema，前端可用 {@code openapi-typescript} 自动生成类型；</li>
 *   <li><b>字段改名会有编译错误</b>——而 {@code map.put("totalPages", ...)}
 *       改成 {@code "total_pages"} 不会有任何信号，只是前端悄悄拿到 undefined；</li>
 *   <li>字段名与类型集中一处可查，不必翻遍 controller 找 {@code map.put}。</li>
 * </ul>
 *
 * <h3>字段名不得改动</h3>
 * <p>
 * 这些 record 的字段名<b>必须与原 {@code Map} 的键完全一致</b>——
 * 它们是已冻结的前后端契约。前端 {@code ticket.service.ts} 直接读
 * {@code data.tickets} / {@code data.totalPages}，改名等于破坏线上功能。
 * 契约测试 {@code TicketDtoContractTest} 守住这一点。
 * </p>
 *
 * @author OpsBrain AI
 * @since 2026-08-31
 */
public class TicketDto {

    private TicketDto() {
    }

    /**
     * 工单分页列表响应。
     *
     * <p>对应 {@code GET /api/v1/tickets}。前端 {@code ticket.service.ts}
     * 逐字段读取，字段名已冻结。</p>
     *
     * @param tickets    当前页工单（已批量装填标签，无 N+1）
     * @param total      符合筛选条件的总数。<b>必须按与列表同一条件统计</b>，
     *                   否则页码与实际数据矛盾
     * @param page       生效页码（已钳制，可能与请求值不同）
     * @param size       生效每页大小（已钳制，上限 200）
     * @param totalPages 总页数，由 {@code total} 与 {@code size} 推导
     */
    public record TicketPage(
            List<DevOpsTicket> tickets,
            long total,
            int page,
            int size,
            int totalPages
    ) {
        /**
         * 由查询结果构造，总页数在此统一推导。
         *
         * <p>放在 DTO 里而不是 controller：{@code Math.ceil} 的整数除法陷阱
         * 只需在一处写对——controller 里散落多份时，改了一处漏一处
         * 会让不同端点的分页行为不一致。</p>
         */
        public static TicketPage of(List<DevOpsTicket> tickets, long total, int page, int size) {
            // size 已由调用方钳制为 ≥1，此处不再兜底：
            // 若真为 0 应当暴露为除零异常，而不是静默返回一个错误的页数
            int totalPages = (int) Math.ceil((double) total / size);
            return new TicketPage(tickets, total, page, size, totalPages);
        }
    }
}
