/**
 * 看板 API - 统计数据接口
 * 对应后端: GET /api/v1/dashboard/overview
 */

import { API_ENDPOINTS } from '../config/api'
import { http, unwrapBiz } from '../utils/http'
import type { DashboardOverview } from './types'

/**
 * 获取看板聚合统计数据
 */
export async function getDashboardOverview(): Promise<DashboardOverview> {
  const payload = await http.get<unknown>(API_ENDPOINTS.DASHBOARD_OVERVIEW)
  return unwrapBiz<DashboardOverview>(payload, '获取看板数据失败')
}

/**
 * 闭环度量（B5）：MTTA/MTTM/MTTR + 各阶段完成率 + 跳过验证率
 */
export interface ClosureMetrics {
  total: number
  firstResponded: number
  mitigated: number
  rootCauseConfirmed: number
  verified: number
  verifySkipped: number
  mttaMinutes: number | null
  mttmMinutes: number | null
  mttrMinutes: number | null
  skipRate: number | null
}

/**
 * 获取工单闭环度量
 */
export async function getClosureMetrics(): Promise<ClosureMetrics> {
  const url = `${API_ENDPOINTS.TICKETS}/metrics/closure`
  const payload = await http.get<unknown>(url)
  return unwrapBiz<ClosureMetrics>(payload, '获取闭环度量失败')
}

/**
 * 根因分类聚合（B5）：哪类根因最多
 */
export async function getRootCauseStats(): Promise<Record<string, number>> {
  const url = `${API_ENDPOINTS.TICKETS}/root-cause/stats`
  const payload = await http.get<unknown>(url)
  return unwrapBiz<Record<string, number>>(payload, '获取根因统计失败')
}

/**
 * 多维趋势数据（L2 趋势分析）
 *
 * 五个数组共用同一 `days` 横轴，长度必然相等（后端按同一补零规则生成）。
 * 无活动的日期为 0 而非缺失——否则折线断档，用户看不出哪几天无活动。
 */
export interface TrendData {
  /** 日期标签 yyyy-MM-dd，从 windowDays-1 天前到今天 */
  days: string[]
  /** 每日建单数 */
  created: number[]
  /** 每日验证通过数（MTTR 口径，跳过验证的工单不计入） */
  resolved: number[]
  /** 每日 AI 调用成本（元） */
  cost: number[]
  /** 每日缓存命中率（0~100 百分数，分母为有效查询 CHAT+CACHE_HIT） */
  cacheHitRate: number[]
  /** 实际生效的窗口天数（后端已兜底至 [1,90]，可能与请求值不同） */
  windowDays: number
  /** 生效的下钻服务模块；null=全局口径 */
  module: string | null
  /**
   * AI 调用趋势（成本/命中率）的口径
   *
   * 恒为 'GLOBAL'——审计日志 sys_agent_call_log 不含服务维度，
   * 即使工单按 module 下钻，成本与命中率仍是全局值。
   * 前端必须据此标注，否则用户会把全局成本误读为该服务的成本。
   */
  callTrendScope: string
}

/**
 * 获取多维趋势（工单 / 成本 / 缓存命中率）
 *
 * @param days   统计窗口天数，后端兜底至 [1, 90]
 * @param module 服务模块下钻（K8S/MYSQL/NETWORK 等）；省略=全局。
 *               注意只有工单两条线会被过滤，成本/命中率恒为全局。
 */
export async function getTrends(days = 7, module?: string | null): Promise<TrendData> {
  const params = new URLSearchParams()
  params.set('days', String(days))
  if (module) params.set('module', module)
  const url = `${API_ENDPOINTS.DASHBOARD_TRENDS}?${params.toString()}`
  const payload = await http.get<unknown>(url)
  const data = unwrapBiz<Partial<TrendData>>(payload, '获取趋势数据失败')
  // 后端任一数组缺失时退化为空数组：图表渲染空数据比整页崩掉好，
  // 且调用方可据 days.length===0 判定「无数据」而非误当作全 0
  return {
    days: Array.isArray(data?.days) ? data.days : [],
    created: Array.isArray(data?.created) ? data.created : [],
    resolved: Array.isArray(data?.resolved) ? data.resolved : [],
    cost: Array.isArray(data?.cost) ? data.cost : [],
    cacheHitRate: Array.isArray(data?.cacheHitRate) ? data.cacheHitRate : [],
    windowDays: data?.windowDays ?? days,
    module: data?.module ?? null,
    callTrendScope: data?.callTrendScope ?? 'GLOBAL'
  }
}
