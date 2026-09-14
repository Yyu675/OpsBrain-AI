import { computed, type Ref } from 'vue'
import { useQuery } from '@tanstack/vue-query'

import {
  getClosureMetrics,
  getDashboardOverview,
  getDiagnosisBoard,
  getRootCauseStats,
  getTrends,
  type ClosureMetrics,
  type DiagnosisBoard,
  type TrendData,
} from '@/api/dashboard'
import type { DashboardOverview } from '@/api/types'
import { fetchAiAnalysisStats } from '@/api/ticketAiAnalysis'
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
 *
 * **缓存策略**（P2-3.1 优化）：
 * - `staleTime: 30_000` — 30 秒内复用缓存，避免快速切换页面时重复请求
 * - `gcTime: 5 * 60_000` — 5 分钟后清理缓存
 * - Dashboard 数据实时性要求不高（30 秒延迟可接受），缓存可显著提升体验
 */
export function useDashboardOverviewQuery() {
  return useQuery({
    queryKey: dashboardKeys.overview(),
    queryFn: () => getDashboardOverview(),
    staleTime: 30_000, // 30 秒内视为新鲜数据
    gcTime: 5 * 60_000, // 5 分钟垃圾回收
  })
}

/**
 * B5 闭环度量（MTTA / MTTM / MTTR）。
 *
 * 独立查询：闭环度量与 AI 调用概览来自不同后端端点，
 * 一方失败不该让另一方也看不到。
 *
 * **缓存策略**：同 Overview，30 秒缓存窗口。
 */
export function useClosureMetricsQuery() {
  return useQuery({
    queryKey: dashboardKeys.closureMetrics(),
    queryFn: () => getClosureMetrics(),
    staleTime: 30_000,
    gcTime: 5 * 60_000,
  })
}

/** 根因分类聚合。加载失败时降级为空对象，模板据此隐藏该区块。缓存 30 秒。 */
export function useRootCauseStatsQuery() {
  const query = useQuery({
    queryKey: dashboardKeys.rootCauseStats(),
    queryFn: () => getRootCauseStats(),
    staleTime: 30_000,
    gcTime: 5 * 60_000,
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
 *
 * **缓存策略**：趋势数据按 (days, module) 维度独立缓存，每个组合 30 秒有效期。
 */
export function useTrendsQuery(days: Ref<number>, module?: Ref<string | undefined>) {
  return useQuery({
    queryKey: computed(() => dashboardKeys.trends(days.value, module?.value)),
    queryFn: () => getTrends(days.value, module?.value),
    staleTime: 30_000,
    gcTime: 5 * 60_000,
  })
}

/**
 * 诊断区看板（S4-4.2）。
 *
 * @param days 窗口天数（进 queryKey，切换即自动重拉）
 *
 * **缓存策略**：按 days 维度独立缓存，30 秒有效期。
 */
export function useDiagnosisBoardQuery(days: Ref<number>) {
  return useQuery({
    queryKey: computed(() => dashboardKeys.diagnosisBoard(days.value)),
    queryFn: () => getDiagnosisBoard(days.value),
    staleTime: 30_000,
    gcTime: 5 * 60_000,
  })
}

/** @public knip 假阳存证：re-export 给视图直接 import type 用（5.88.1 对该形态解析盲区，报告 128 §三）——版本收敛后删行复查 */
export type { ClosureMetrics, DashboardOverview, DiagnosisBoard, TrendData }

/**
 * AI 效果区（S4-4.1 半部先行）：根因分析反馈统计。
 * 幻觉率/证据不足率持 EVAL_LLM 窗数据再入区——本 hook 只装配既有反馈闭环。
 * 注：本 hook 不得插回 @public 注释与上方 re-export 行之间（批 29 案卷：
 * JSDoc 豁免必须与声明相邻，隔断即豁免失效、门禁出警）。
 *
 * **缓存策略**：AI 分析统计变化不频繁，30 秒缓存窗口。
 */
export function useAiAnalysisStatsQuery() {
  return useQuery({
    queryKey: dashboardKeys.aiEffectStats(),
    queryFn: () => fetchAiAnalysisStats(),
    staleTime: 30_000,
    gcTime: 5 * 60_000,
  })
}
