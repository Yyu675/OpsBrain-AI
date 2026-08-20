/**
 * 告警 API（L2 实时监测 Stage 3）
 *
 * 告警列表分页查询 + 人工确认 / 标记恢复。
 * 与 WebSocket 推送（useAlertNotifications）互补——WS 负责秒级实时事件，
 * 本模块负责权威列表（服务端分页/筛选）与人工处置写操作。
 */

import { API_ENDPOINTS } from '../config/api'
import { http, unwrapBiz, HttpError } from '../utils/http'
import type { Alert, AlertsResponse } from './types'

export interface AlertQuery {
  page?: number
  size?: number
  status?: string
  level?: string
}

/**
 * 分页查询告警列表（服务端分页 + 状态/级别筛选）
 */
export async function fetchAlerts(query: AlertQuery = {}): Promise<AlertsResponse> {
  const params = new URLSearchParams()
  params.set('page', String(query.page ?? 1))
  params.set('size', String(query.size ?? 10))
  if (query.status) params.set('status', query.status)
  if (query.level) params.set('level', query.level)

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
 * 查询单个告警详情（告警详情页 /alerts/:id）
 * <p>三态语义（6.18 契约）：不存在返回 null（不抛），网络/服务异常抛异常。</p>
 */
export async function fetchAlertById(id: number | string): Promise<Alert | null> {
  try {
    const payload = await http.get<unknown>(API_ENDPOINTS.ALERTS_BY_ID(Number(id)))
    return unwrapBiz<Alert>(payload, '查询告警详情失败')
  } catch (e) {
    if (e instanceof HttpError && (e.status === 404 || e.bizCode === 40004)) {
      return null
    }
    throw e instanceof Error ? e : new Error('查询告警详情失败')
  }
}

/**
 * 人工确认告警（FIRING/ACKNOWLEDGED → ACKNOWLEDGED，幂等）
 */
export async function acknowledgeAlert(id: number): Promise<Alert> {
  const payload = await http.post<unknown>(API_ENDPOINTS.ALERTS_BY_ID(id) + '/acknowledge')
  return unwrapBiz<Alert>(payload, '确认告警失败')
}

/**
 * 标记告警已恢复（任意非终态 → RESOLVED，幂等）
 */
export async function resolveAlert(id: number): Promise<Alert> {
  const payload = await http.post<unknown>(API_ENDPOINTS.ALERTS_BY_ID(id) + '/resolve')
  return unwrapBiz<Alert>(payload, '标记恢复失败')
}
