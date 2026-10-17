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
        List<Long> created,
        List<Long> resolved,
        List<Double> cost,
        List<Double> cacheHitRate,
        int windowDays,
        String module,
        String callTrendScope
) {
}
