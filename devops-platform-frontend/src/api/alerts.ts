/**
 * 告警 API（L2 实时监测 Stage 3）
 *
 * 告警列表分页查询 + 人工确认 / 标记恢复。
 * 与 WebSocket 推送（useAlertNotifications）互补——WS 负责秒级实时事件，
 * 本模块负责权威列表（服务端分页/筛选）与人工处置写操作。
 */

import { API_ENDPOINTS } from '../config/api'
import { http, unwrapBiz, HttpError } from '../utils/http'
import { BizCode } from '../constants/bizCode'
import type { Alert, AlertsResponse } from './types'

interface AlertQuery {
  page?: number
  size?: number
  status?: string
  level?: string
  /** 来源系统筛选（V9 起；/webhook/{system} 注入的值） */
  system?: string
  /** 只看观察中（FR-3.1：活跃+未建单+观察级+未超窗） */
  observing?: boolean
}

/**
 * 分页查询告警列表（服务端分页 + 状态/级别/系统筛选 + 观察中过滤）
 */
export async function fetchAlerts(query: AlertQuery = {}): Promise<AlertsResponse> {
  const params = new URLSearchParams()
  params.set('page', String(query.page ?? 1))
  params.set('size', String(query.size ?? 10))
  if (query.status) params.set('status', query.status)
  if (query.level) params.set('level', query.level)
  if (query.system) params.set('system', query.system)
  if (query.observing) params.set('observing', 'true')

  const payload = await http.get<unknown>(`${API_ENDPOINTS.ALERTS}?${params.toString()}`)
  const data = unwrapBiz<AlertsResponse>(payload, '查询告警列表失败')
  return {
    alerts: Array.isArray(data?.alerts) ? data.alerts : [],
    total: data?.total ?? 0,
    page: data?.page ?? 1,
    size: data?.size ?? 10,
    totalPages: data?.totalPages ?? 0
  }
}

/**
 * 全部来源系统去重列表（system 筛选下拉的数据源）。
 * 拉取失败降级为空数组——筛选器少一个维度不是致命错误。
 */
export async function fetchAlertSystems(): Promise<string[]> {
  try {
    const payload = await http.get<unknown>(`${API_ENDPOINTS.ALERTS}/systems`)
    const data = unwrapBiz<string[]>(payload, '查询来源系统失败')
    return Array.isArray(data) ? data : []
  } catch {
    return []
  }
}

/**
 * 查询单个告警详情（告警详情页 /alerts/:id）
 * <p>三态语义（6.18 契约）：不存在返回 null（不抛），网络/服务异常抛异常。</p>
 */
export async function fetchAlertById(id: number | string): Promise<Alert | null> {
  try {
    const payload = await http.get<unknown>(API_ENDPOINTS.ALERTS_BY_ID(Number(id)))
    return unwrapBiz<Alert>(payload, '查询告警详情失败')
  } catch (e) {
    if (e instanceof HttpError && (e.status === 404 || e.bizCode === BizCode.NOT_FOUND)) {
      return null
    }
    throw e instanceof Error ? e : new Error('查询告警详情失败')
  }
}

/**
 * 同事件告警联动（建议3）：同 system + service，首次发生时间 ±window 分钟窗内。
 * 拉取失败降级为空数组——联动列表是增强信息，拿不到不阻塞详情页主内容。
 */
export async function fetchRelatedAlerts(id: number, window = 10): Promise<Alert[]> {
  const safeWindow = Math.min(Math.max(1, window), 60)
  try {
    const payload = await http.get<unknown>(
      `${API_ENDPOINTS.ALERTS_BY_ID(id)}/related?window=${safeWindow}`)
    const data = unwrapBiz<Alert[]>(payload, '查询同事件告警失败')
    return Array.isArray(data) ? data : []
  } catch {
    return []
  }
}

/**
 * 人工确认告警（FIRING/ACKNOWLEDGED → ACKNOWLEDGED，幂等）
 */
export async function acknowledgeAlert(id: number): Promise<Alert> {
  const payload = await http.post<unknown>(API_ENDPOINTS.ALERTS_BY_ID(id) + '/acknowledge')
  return unwrapBiz<Alert>(payload, '确认告警失败')
}

/** 管道心跳状态（看门狗告警的可视面） */
export interface PipelineHeartbeat {
  /** 看门狗最近一次送达时间（ISO 本地时间）；从未收到为 null */
  lastSeenAt: string | null
  /** 是否超阈值静默——true 意味着告警可能正在丢失 */
  silent: boolean
  silenceMinutes: number
}

/**
 * 查询管道心跳。拉取失败按「未知」降级（null）——心跳是提示不是主流程，
 * 绝不能因为一个提示接口挂了就让告警列表不可用。
 */
export async function fetchPipelineHeartbeat(): Promise<PipelineHeartbeat | null> {
  try {
    const payload = await http.get<unknown>(`${API_ENDPOINTS.ALERTS}/heartbeat`)
    return unwrapBiz<PipelineHeartbeat>(payload, '查询管道心跳失败')
  } catch {
    return null
  }
}

/** 日志管道心跳（效能大盘「日志管道」卡用） */
export interface LogsHeartbeat {
  freshestLogAt: string | null
  silent: boolean
  silenceMinutes: number
  /** false = 还没探过（启动宽限内），显示「未知」而非误报静默 */
  probed: boolean
  lokiEnabled: boolean
}

/** 自愈观察窗统计（FR-3.1 可视面，效能大盘 ObservationStatsPanel 读它） */
export interface ObservationStats {
  /** 观察窗是否开启——false 时前端隐藏区块而非显示一排 0 */
  enabled: boolean
  windowMinutes: number
  levels: string[]
  observingNow: number
  selfHealed30d: number
  escalated30d: number
}

/**
 * 查询自愈观察窗统计。拉取失败降级为 null（它是看板读数不是主流程，
 * 不能因一个统计接口挂了让大盘其他区块也画不出来）。
 */
export async function fetchObservationStats(): Promise<ObservationStats | null> {
  try {
    const payload = await http.get<unknown>(`${API_ENDPOINTS.ALERTS}/observation-stats`)
    return unwrapBiz<ObservationStats>(payload, '查询观察窗统计失败')
  } catch {
    return null
  }
}

export async function fetchLogsHeartbeat(): Promise<LogsHeartbeat | null> {
  try {
    const payload = await http.get<unknown>(`${API_ENDPOINTS.ALERTS}/logs-heartbeat`)
    return unwrapBiz<LogsHeartbeat>(payload, '查询日志管道心跳失败')
  } catch {
    return null
  }
}

/** 全局风暴模式状态（FR-2.5 可视面，告警列表横幅读它） */
export interface StormStatus {
  /** 风暴模式是否开启——false 时前端隐藏横幅位 */
  enabled: boolean
  /** 当前是否处于风暴模式 */
  active: boolean
  /** 近 60 秒 firing 告警数 */
  ratePerMin: number
  enterRatePerMin: number
  exitRatePerMin: number
}

/**
 * 查询风暴模式状态。拉取失败降级为 null——风暴横幅是提示不是主流程，
 * 绝不能因为状态接口挂了让告警列表不可用。
 */
export async function fetchStormStatus(): Promise<StormStatus | null> {
  try {
    const payload = await http.get<unknown>(`${API_ENDPOINTS.ALERTS}/storm-status`)
    return unwrapBiz<StormStatus>(payload, '查询风暴状态失败')
  } catch {
    return null
  }
}

/**
 * 标记告警已恢复（任意非终态 → RESOLVED，幂等）
 */
export async function resolveAlert(id: number): Promise<Alert> {
  const payload = await http.post<unknown>(API_ENDPOINTS.ALERTS_BY_ID(id) + '/resolve')
  return unwrapBiz<Alert>(payload, '标记恢复失败')
}
