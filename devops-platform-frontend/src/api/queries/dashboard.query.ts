import { computed, type Ref } from 'vue'
import { useQuery } from '@tanstack/vue-query'

import {
  getClosureMetrics,
  getDashboardOverview,
  getRootCauseStats,
  getTrends,
  type ClosureMetrics,
  type TrendData,
} from '@/api/dashboard'
import type { DashboardOverview } from '@/api/types'
import { dashboardKeys } from '@/config/queryKeys'

/**
 * 数据概览页的 Query 封装。
 *
 * 相比原先的 `Promise.all` + 各自 catch：每个查询有**独立的 loading/error 状态**，
 * 降级策略从「catch 里返回兜底值」变成「模板按各自的 error 分支渲染」——
 * 趋势加载失败只让图表区降级，不影响已加载成功的 KPI（6.51 契约），
 * 且失败区块能各自提供重试入口，而非只能整页刷新。
 */

/**
 * AI 调用概览（KPI 主数据）。
 *
 * 这是页面主体，失败即整页显示错误态——其余区块都是它的补充。
 */
export function useDashboardOverviewQuery() {
  return useQuery({
    queryKey: dashboardKeys.overview(),
    queryFn: () => getDashboardOverview(),
  })
}

/**
 * B5 闭环度量（MTTA / MTTM / MTTR）。
 *
 * 独立查询：闭环度量与 AI 调用概览来自不同后端端点，
 * 一方失败不该让另一方也看不到。
 */
export function useClosureMetricsQuery() {
  return useQuery({
    queryKey: dashboardKeys.closureMetrics(),
    queryFn: () => getClosureMetrics(),
  })
}

/** 根因分类聚合。加载失败时降级为空对象，模板据此隐藏该区块 */
export function useRootCauseStatsQuery() {
  const query = useQuery({
    queryKey: dashboardKeys.rootCauseStats(),
    queryFn: () => getRootCauseStats(),
  })
  return {
    ...query,
    stats: computed<Record<string, number>>(() => query.data.value ?? {}),
  }
}

/**
 * 多维趋势。
 *
 * @param days   窗口天数（进 queryKey，切换即自动重拉）
 * @param module 服务下钻维度。注意：只有工单两条线按服务过滤，
 *               成本与命中率恒为全局口径（审计日志无服务维度，见 6.53）
 */
export function useTrendsQuery(days: Ref<number>, module?: Ref<string | undefined>) {
  return useQuery({
    queryKey: computed(() => dashboardKeys.trends(days.value, module?.value)),
    queryFn: () => getTrends(days.value, module?.value),
  })
}

export type { ClosureMetrics, DashboardOverview, TrendData }
