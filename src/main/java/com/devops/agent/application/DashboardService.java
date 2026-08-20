package com.devops.agent.application;

import com.devops.agent.controller.dto.DashboardOverviewDTO;

import java.util.Map;

/**
 * M8 看板统计服务接口
 *
 * <p>职责：
 * <ul>
 *   <li>聚合统计数据（查询数、缓存命中、工单数）</li>
 *   <li>生成 ECharts 数据格式（模型分布饼图、成本趋势折线图）</li>
 *   <li>数据不足时返回演示数据（保证看板饱满）</li>
 * </ul>
 *
 * @author OpsBrain AI Team
 * @version 1.0
 * @since 2026-07-15
 */
public interface DashboardService {

    /**
     * 获取看板概览数据
     *
     * <p>数据源：
     * <ul>
     *   <li>sys_agent_call_log - 总查询数、缓存命中、模型分布、成本趋势</li>
     *   <li>sys_devops_ticket - 工单总数</li>
     * </ul>
     *
     * <p>兜底策略：
     * 若数据库记录 < 5 条，返回高质量演示数据
     *
     * @return 看板概览 DTO
     */
    DashboardOverviewDTO getOverview();

    /**
     * 获取 AI 调用按天趋势（供趋势分析模式 ECharts）
     *
     * <p>数据源 sys_agent_call_log，返回 {@code days} 天窗口内（含今天）的：
     * <ul>
     *   <li>{@code cost[]} —— 每日 AI 调用成本（元）</li>
     *   <li>{@code cacheHitRate[]} —— 每日缓存命中率（0~100 百分数）</li>
     * </ul>
     *
     * <p><b>命中率口径</b>：分母为「有效查询」（{@code CHAT + CACHE_HIT}），
     * 排除 {@code REJECTED_*}/{@code FAILED_*} 审计行——否则命中率被系统性低估
     * （6.41 已修正的同一口径）。当日无有效查询时该点为 0 而非 null，
     * 与「固定周期趋势必须补零」一致。
     *
     * @param days 统计窗口天数，Controller 已兜底至 [1, 90]
     * @return {@code { days[], cost[], cacheHitRate[] }} 三个等长对齐数组
     */
    Map<String, Object> getCallTrends(int days);
}
