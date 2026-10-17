package com.devops.agent.controller.dto;

import java.util.List;

/**
 * 仪表盘多维趋势响应（GET /api/v1/dashboard/trends）。
 *
 * <p>P0-2b 强契约化：替代弱类型 {@code Map<String, Object>}。
 * 字段严格对齐前端 {@code dashboard.ts#TrendData}。</p>
 *
 * @param days           横轴日期列表（YYYY-MM-DD）
 * @param created        每日创建工单数
 * @param resolved       每日解决工单数
 * @param cost           每日 AI 调用成本（元）
 * @param cacheHitRate   每日缓存命中率（百分比）
 * @param windowDays     统计窗口天数
 * @param module         下钻服务模块名称（null 为全局）
 * @param callTrendScope 调用趋势统计口径说明（默认 GLOBAL）
 */
public record DashboardTrendsResponse(
        List<String> days,
        List<? extends Number> created,
        List<? extends Number> resolved,
        List<? extends Number> cost,
        List<? extends Number> cacheHitRate,
        int windowDays,
        String module,
        String callTrendScope
) {
    public static DashboardTrendsResponse of(
            List<String> days,
            List<?> created,
            List<?> resolved,
            List<?> cost,
            List<?> cacheHitRate,
            int windowDays,
            String module,
            String callTrendScope
    ) {
        return new DashboardTrendsResponse(
                days,
                toNumberList(created),
                toNumberList(resolved),
                toNumberList(cost),
                toNumberList(cacheHitRate),
                windowDays,
                module,
                callTrendScope
        );
    }

    private static List<Number> toNumberList(List<?> raw) {
        if (raw == null) return List.of();
        return raw.stream()
                .map(item -> item instanceof Number n ? n : 0)
                .toList();
    }
}
