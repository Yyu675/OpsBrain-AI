package com.devops.agent.application.impl;

import com.devops.agent.application.DashboardService;
import com.devops.agent.controller.dto.DashboardOverviewDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * M8 看板统计服务实现（P0-2 + P1-6 + P1-8 修复）
 *
 * <p>核心逻辑：
 * <ul>
 *   <li>使用 JdbcTemplate 聚合查询（性能优于 JPA）</li>
 *   <li>数据不足时返回真实 0 值（移除演示数据兜底，P1-6）</li>
 *   <li>补充 cacheHitRate / avgCostRmb 字段（P1-8 契约对齐）</li>
 *   <li>异常向上抛，禁止吞异常返回假数据（P1-6）</li>
 * </ul>
 *
 * @author OpsBrain AI Team
 * @version 2.0
 * @since 2026-08-12
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DashboardServiceImpl implements DashboardService {

    private final JdbcTemplate jdbcTemplate;

    /**
     * 「有效查询」口径：只有 CHAT（调模型）与 CACHE_HIT（命中缓存）是用户真正发起并被
     * 服务的查询。sys_agent_call_log 还含 REJECTED_*（注入/预算/配额拦截）与 FAILED_*
     * （流式/系统异常）审计行（6.6 铁律要求所有终止路径落库）——这些不是「查询」，
     * 用它们做分母会把缓存命中率、模型分布百分比系统性稀释。
     */
    private static final String SERVED_QUERY_FILTER =
            "operation_type IN ('CHAT', 'CACHE_HIT')";

    @Override
    public DashboardOverviewDTO getOverview() {
        log.info("📊 [Dashboard] 开始查询看板概览数据");

        // 1. 统计有效查询数（CHAT + CACHE_HIT，剔除拒绝/失败审计行）
        Long totalQueries = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_agent_call_log WHERE " + SERVED_QUERY_FILTER,
                Long.class
        );
        long queries = (totalQueries != null ? totalQueries : 0L);

        // 2. 统计缓存命中数（operation_type=CACHE_HIT，与有效查询口径一致）
        Long cacheHits = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_agent_call_log WHERE operation_type = 'CACHE_HIT'",
                Long.class
        );
        long hits = (cacheHits != null ? cacheHits : 0L);

        // 3. 计算缓存命中率（百分比，保留 2 位小数）。
        //    分母为有效查询（命中 + 未命中调模型），不含拒绝/失败行
        double cacheHitRate = queries > 0 ? Math.round((hits * 100.0 / queries) * 100.0) / 100.0 : 0.0;

        // 4. 统计平均成本（元/次）。只对付费调用（cost_rmb>0）求均值——
        //    缓存命中成本为 0，计入会把均值拉低失真
        Double avgCostObj = jdbcTemplate.queryForObject(
                "SELECT AVG(cost_rmb) FROM sys_agent_call_log WHERE cost_rmb > 0",
                Double.class
        );
        double avgCostRmb = avgCostObj != null ? Math.round(avgCostObj * 10000.0) / 10000.0 : 0.0;

        // 5. 统计工单总数
        Long totalTickets = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_devops_ticket",
                Long.class
        );

        // 6. 统计模型分布
        List<DashboardOverviewDTO.ModelDistribution> modelDistribution = buildModelDistribution();

        // 7. 统计 7 日成本趋势
        List<DashboardOverviewDTO.CostTrend> costTrends = buildCostTrends();

        DashboardOverviewDTO result = DashboardOverviewDTO.builder()
                .totalQueries(queries)
                .cacheHits(hits)
                .cacheHitRate(cacheHitRate)
                .avgCostRmb(avgCostRmb)
                .totalTickets(totalTickets != null ? totalTickets : 0L)
                .modelDistribution(modelDistribution)
                .costSavingsChart(costTrends)
                .build();

        log.info("✅ [Dashboard] 看板数据查询成功 | 有效查询={} | 命中={} | 命中率={}% | 平均成本={}元 | 工单={}",
                queries, hits, cacheHitRate, avgCostRmb, result.getTotalTickets());

        return result;
    }

    /**
     * 构建模型分布饼图数据
     *
     * <p>列名是 model_name（表中无 model 列），用别名 AS model 保持 DTO 字段不变。
     *
     * <p>百分比分母用<b>各模型计数之和</b>（即有 model_name 的调用总数），而非全表行数——
     * 否则百分比之和会因缓存命中/拒绝/失败行（无 model_name）而小于 100%，进度条永远填不满。
     *
     * @return 模型分布列表
     */
    private List<DashboardOverviewDTO.ModelDistribution> buildModelDistribution() {
        List<DashboardOverviewDTO.ModelDistribution> result = new ArrayList<>();

        String sql = "SELECT model_name AS model, COUNT(*) as count FROM sys_agent_call_log "
                + "WHERE model_name IS NOT NULL GROUP BY model_name ORDER BY count DESC";

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);

        // 分母 = 有模型归属的调用总数，保证百分比之和 = 100%
        long modelTotal = 0L;
        for (Map<String, Object> row : rows) {
            modelTotal += ((Number) row.get("count")).longValue();
        }

        for (Map<String, Object> row : rows) {
            String model = (String) row.get("model");
            Long count = ((Number) row.get("count")).longValue();
            double percentage = modelTotal > 0 ? (count * 100.0 / modelTotal) : 0.0;

            result.add(DashboardOverviewDTO.ModelDistribution.builder()
                    .model(model)
                    .count(count)
                    .percentage(Math.round(percentage * 100.0) / 100.0) // 保留 2 位小数
                    .build());
        }

        return result;
    }

    /**
     * 构建 7 日成本趋势数据
     *
     * <p>按天聚合后<b>补零填充</b>为连续 7 天：无调用的日期返回 cost=0，而非缺失。
     * 否则「7 日趋势」可能只渲染 2 个点，用户看不出哪几天无活动，也与「7 日」标题不符。
     *
     * @return 从 6 天前到今天、固定 7 个点的成本趋势
     */
    private List<DashboardOverviewDTO.CostTrend> buildCostTrends() {
        String sql = """
                SELECT DATE(create_time) as date, SUM(cost_rmb) as cost
                FROM sys_agent_call_log
                WHERE create_time >= NOW() - INTERVAL '7 days'
                GROUP BY DATE(create_time)
                """;

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);

        // 先把有数据的日期归入 map，便于补零时按日期查
        Map<LocalDate, Double> costByDate = new java.util.HashMap<>();
        for (Map<String, Object> row : rows) {
            LocalDate date = ((java.sql.Date) row.get("date")).toLocalDate();
            Double cost = row.get("cost") != null ? ((Number) row.get("cost")).doubleValue() : 0.0;
            costByDate.put(date, cost);
        }

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        LocalDate today = LocalDate.now();
        List<DashboardOverviewDTO.CostTrend> result = new ArrayList<>();

        // 连续 7 天：从 6 天前到今天，无数据的日期补 0
        for (int i = 6; i >= 0; i--) {
            LocalDate day = today.minusDays(i);
            double cost = costByDate.getOrDefault(day, 0.0);
            result.add(DashboardOverviewDTO.CostTrend.builder()
                    .date(day.format(formatter))
                    .cost(Math.round(cost * 10000.0) / 10000.0)
                    .build());
        }

        return result;
    }

    /**
     * AI 调用按天趋势（成本 + 缓存命中率）
     *
     * <p>一次查询取回三个量（有效查询数、命中数、成本），避免为三条线各查一遍。
     * 命中率分母沿用 {@link #SERVED_QUERY_FILTER}——与概览 KPI 同口径，
     * 两处若各写一套必然漂移（6.20「同一事实只允许一处定义」）。
     */
    @Override
    public Map<String, Object> getCallTrends(int days) {
        // 按天聚合：served=有效查询数，hits=命中数，cost=当日总成本
        // 成本对全部行求和（含失败调用——失败也可能已产生 token 费用），
        // 命中率只对有效查询算，二者口径不同是有意为之。
        String sql = """
                SELECT DATE(create_time) AS day,
                       COUNT(*) FILTER (WHERE operation_type IN ('CHAT', 'CACHE_HIT')) AS served,
                       COUNT(*) FILTER (WHERE operation_type = 'CACHE_HIT')            AS hits,
                       COALESCE(SUM(cost_rmb), 0)                                      AS cost
                  FROM sys_agent_call_log
                 WHERE create_time >= (CURRENT_DATE - CAST(? AS INTEGER))
                 GROUP BY DATE(create_time)
                """;

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, days - 1);

        Map<LocalDate, Double> costByDay = new java.util.HashMap<>();
        Map<LocalDate, Long> servedByDay = new java.util.HashMap<>();
        Map<LocalDate, Long> hitsByDay = new java.util.HashMap<>();

        for (Map<String, Object> row : rows) {
            LocalDate day = ((java.sql.Date) row.get("day")).toLocalDate();
            Object cost = row.get("cost");
            costByDay.put(day, cost != null ? ((Number) cost).doubleValue() : 0.0);
            Object served = row.get("served");
            servedByDay.put(day, served != null ? ((Number) served).longValue() : 0L);
            Object hits = row.get("hits");
            hitsByDay.put(day, hits != null ? ((Number) hits).longValue() : 0L);
        }

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        LocalDate today = LocalDate.now();

        List<String> dayLabels = new ArrayList<>();
        List<Double> costSeries = new ArrayList<>();
        List<Double> hitRateSeries = new ArrayList<>();

        // 连续 N 天补零：无调用的日期成本 0、命中率 0，
        // 否则折线断档，用户看不出哪几天无活动（6.41 契约）
        for (int i = days - 1; i >= 0; i--) {
            LocalDate day = today.minusDays(i);
            dayLabels.add(day.format(formatter));

            double cost = costByDay.getOrDefault(day, 0.0);
            costSeries.add(Math.round(cost * 10000.0) / 10000.0);

            long served = servedByDay.getOrDefault(day, 0L);
            long hits = hitsByDay.getOrDefault(day, 0L);
            double rate = served > 0 ? (hits * 100.0 / served) : 0.0;
            hitRateSeries.add(Math.round(rate * 100.0) / 100.0);
        }

        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("days", dayLabels);
        data.put("cost", costSeries);
        data.put("cacheHitRate", hitRateSeries);
        return data;
    }
}
