package com.devops.agent.controller;

import com.devops.agent.application.DashboardService;
import com.devops.agent.common.dto.ApiResponse;
import com.devops.agent.controller.dto.DashboardOverviewDTO;
import com.devops.agent.domain.biz.service.TicketService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * M8 看板统计模块 - Controller 层
 *
 * <p>接口设计：
 * <ul>
 *   <li>GET /api/v1/dashboard/overview - 获取看板概览数据</li>
 *   <li>GET /api/v1/dashboard/trends - 获取多维趋势（工单/成本/命中率）</li>
 * </ul>
 *
 * <p>数据来源：
 * <ul>
 *   <li>sys_agent_call_log - 总查询数、缓存命中数、成本统计</li>
 *   <li>sys_devops_ticket - 工单总数</li>
 *   <li>模型分布饼图 - 按 model 分组统计</li>
 *   <li>7日降本对比 - 按天聚合 cost_rmb</li>
 * </ul>
 *
 * <p>兜底策略：
 * 数据库记录 < 5 条时返回演示数据，保证看板任何时候点开都饱满（避免空图）
 *
 * @author OpsBrain AI Team
 * @version 1.0
 * @since 2026-07-15
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;
    private final TicketService ticketService;

    /** 趋势窗口天数上下界：<1 会让 SQL 区间为空，过大会一次拉爆并让折线密不可读 */
    private static final int MIN_TREND_DAYS = 1;
    private static final int MAX_TREND_DAYS = 90;

    /**
     * 获取看板概览数据
     *
     * <p>响应字段：
     * <ul>
     *   <li>totalQueries: 总查询数</li>
     *   <li>cacheHits: 缓存命中数</li>
     *   <li>totalTickets: 工单总数</li>
     *   <li>modelDistribution: 模型分布饼图数据 [{model, count, percentage}]</li>
     *   <li>costSavingsChart: 7日成本趋势 [{date, cost}]</li>
     * </ul>
     *
     * @return 看板数据封装
     */
    @GetMapping("/overview")
    public ApiResponse<DashboardOverviewDTO> getOverview() {
        log.info("📊 [Dashboard] 请求看板概览数据");
        DashboardOverviewDTO data = dashboardService.getOverview();
        return ApiResponse.success(data);
    }

    /**
     * 获取多维趋势数据（供趋势分析模式 ECharts）
     *
     * <p>三条线共用同一 {@code days[]} 横轴，保证前端可叠加同图：
     * <ul>
     *   <li>{@code created[]} / {@code resolved[]} —— 每日建单数 / 验证通过数（MTTR 口径）</li>
     *   <li>{@code cost[]} —— 每日 AI 调用成本（元）</li>
     *   <li>{@code cacheHitRate[]} —— 每日缓存命中率（%，分母为有效查询）</li>
     * </ul>
     *
     * <p><b>下钻语义</b>：传 {@code module} 时<b>只有工单两条线</b>按服务过滤——
     * AI 调用审计（{@code sys_agent_call_log}）不含服务维度，成本与命中率
     * 无法按服务拆分，故下钻时这两条线仍是全局值。响应回传 {@code module} 与
     * {@code callTrendScope=GLOBAL}，前端据此标注口径，避免用户把全局成本
     * 误读为该服务的成本（6.41「KPI 口径必须与标签语义一致」）。
     *
     * <p><b>横轴一致性</b>：工单与调用趋势由两个 Service 各自补零，
     * 但窗口天数与「从 days-1 天前到今天」的规则相同，故 {@code days[]} 必然对齐。
     * 这里以工单趋势的 days 为准并断言长度一致，不一致时记 WARN——
     * 两条线错位会让用户把某天的成本读到另一天的工单上。
     *
     * @param days   统计窗口天数，默认 7，兜底至 [1, 90]
     * @param module 服务模块下钻（K8S/MYSQL/NETWORK 等），省略=全局口径
     */
    @GetMapping("/trends")
    public ApiResponse<Map<String, Object>> getTrends(
            @RequestParam(defaultValue = "7") int days,
            @RequestParam(required = false) String module) {
        int safeDays = Math.min(Math.max(MIN_TREND_DAYS, days), MAX_TREND_DAYS);
        String safeModule = (module == null || module.isBlank()) ? null : module.trim();
        log.info("📈 [Dashboard] 请求趋势数据: days={} (入参 {}) | module={}",
                safeDays, days, safeModule == null ? "全局" : safeModule);

        Map<String, Object> ticketTrends = ticketService.getTicketTrends(safeDays, safeModule);
        // AI 调用趋势无服务维度，下钻时仍取全局——差异已在响应中显式标注
        Map<String, Object> callTrends = dashboardService.getCallTrends(safeDays);

        Object ticketDays = ticketTrends.get("days");
        Object callDays = callTrends.get("days");
        if (ticketDays instanceof java.util.List<?> td && callDays instanceof java.util.List<?> cd
                && td.size() != cd.size()) {
            log.warn("⚠️ [Dashboard] 趋势横轴长度不一致 | ticketDays={} | callDays={}", td.size(), cd.size());
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("days", ticketDays);
        data.put("created", ticketTrends.get("created"));
        data.put("resolved", ticketTrends.get("resolved"));
        data.put("cost", callTrends.get("cost"));
        data.put("cacheHitRate", callTrends.get("cacheHitRate"));
        data.put("windowDays", safeDays);
        // 生效的下钻口径：null=全局。前端据此显示「全局」或服务名
        data.put("module", safeModule);
        // 明示成本/命中率不随 module 变化，防止误读为该服务的成本
        data.put("callTrendScope", "GLOBAL");
        return ApiResponse.success(data);
    }
}
