package com.devops.agent.controller.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 看板概览数据传输对象（P0-2 + P1-8 契约对齐）
 *
 * <p>对应接口：GET /api/v1/dashboard/overview
 *
 * @author OpsBrain AI Team
 * @version 2.0
 * @since 2026-08-12
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DashboardOverviewDTO implements Serializable {

    /**
     * 总查询数
     */
    private Long totalQueries;

    /**
     * 缓存命中数
     */
    private Long cacheHits;

    /**
     * 缓存命中率（百分比，P1-8 补充）
     */
    private Double cacheHitRate;

    /**
     * 平均成本（元/次，P1-8 补充）
     */
    private Double avgCostRmb;

    /**
     * 工单总数
     */
    private Long totalTickets;

    /**
     * 模型分布饼图数据
     */
    private List<ModelDistribution> modelDistribution;

    /**
     * 7日成本趋势数据
     */
    private List<CostTrend> costSavingsChart;

    /**
     * 模型分布项
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ModelDistribution implements Serializable {
        /**
         * 模型名称（如 deepseek-chat, deepseek-reasoner）
         */
        private String model;

        /**
         * 调用次数
         */
        private Long count;

        /**
         * 占比百分比（0-100）
         */
        private Double percentage;
    }

    /**
     * 成本趋势项
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CostTrend implements Serializable {
        /**
         * 日期（格式：yyyy-MM-dd）
         */
        private String date;

        /**
         * 当日总成本（人民币元）
         */
        private Double cost;
    }
}
