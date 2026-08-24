/**
 * 审计与 AI 调用日志 API。
 *
 * 对应后端 `AuditLogController`（/api/v1/audit/**，限 ADMIN）。
 */
import { API_BASE } from '../config/api'
import { http, unwrapBiz } from '../utils/http'

// ==================== 类型 ====================

/** 操作审计一条记录（对齐 sys_operation_audit 列） */
export interface OperationAuditItem {
  id: number
  trace_id: string | null
  actor_id: string | null
  actor_name: string | null
  action: string
  target_type: string | null
  target_id: string | null
  http_method: string | null
  http_path: string | null
  status_code: number
  success: boolean
  biz_code: number | null
  error_message: string | null
  client_ip: string | null
  duration_ms: number
  create_time: string
}

/** AI 调用日志一条记录（列表态，不含问答全文） */
export interface AiCallLogItem {
  id: number
  trace_id: string | null
  model_name: string | null
  is_cached: boolean
  latency_ms: number | null
  cost_rmb: number | null
  operation_type: string | null
  operator_id: string | null
  affected_resources: string | null
  /** 提问前 120 字预览。全文需按 traceId 下钻 */
  query_preview: string
  create_time: string
}

/** 当前筛选条件下的汇总，与列表同源 */
export interface AiCallStats {
  totalCalls: number
  cacheHits: number
  cacheHitRate: number
  totalCost: number
  avgLatencyMs: number
}

export interface PagedResult<T> {
  items: T[]
  total: number
  page: number
  size: number
  totalPages: number
}

export interface AiCallLogPage extends PagedResult<AiCallLogItem> {
  stats: AiCallStats
}

/** 链路详情：一次请求留下的 AI 调用 + 操作审计 */
export interface TraceDetail {
  traceId: string
  aiCall: {
    id: number
    trace_id: string
    user_query: string | null
    agent_answer: string | null
    model_name: string | null
    is_cached: boolean
    latency_ms: number | null
    cost_rmb: number | null
    citations: string | null
    operation_type: string | null
    affected_resources: string | null
    operator_id: string | null
    create_time: string
  } | null
  operations: OperationAuditItem[]
}

export interface FilterOptions {
  models: string[]
  operationTypes: string[]
  actions: string[]
  targetTypes: string[]
}

// ==================== 查询参数 ====================

export interface OperationAuditQuery {
  actorId?: string
  /** 前缀匹配，如 `ticket.` 查全部工单操作 */
  action?: string
  targetType?: string
  success?: boolean
  from?: string
  to?: string
  page?: number
  size?: number
}

export interface AiCallLogQuery {
  modelName?: string
  operationType?: string
  cached?: boolean
  /** 最小耗时（ms），用于筛慢调用 */
  minLatencyMs?: number
  from?: string
  to?: string
  page?: number
  size?: number
}

/** 只把有值的参数拼进 query，避免 `?actorId=&action=` 这类空参数 */
const toQuery = (params: Record<string, unknown>): string => {
  const sp = new URLSearchParams()
  for (const [k, v] of Object.entries(params)) {
    if (v === undefined || v === null || v === '') continue
    sp.set(k, String(v))
  }
  const s = sp.toString()
  return s ? `?${s}` : ''
}

// ==================== 接口 ====================

export async function fetchOperationAudit(
  q: OperationAuditQuery = {}
): Promise<PagedResult<OperationAuditItem>> {
  const payload = await http.get<unknown>(`${API_BASE}/audit/operations${toQuery({ ...q })}`)
  return unwrapBiz<PagedResult<OperationAuditItem>>(payload, '获取操作审计失败')
}

export async function fetchAiCallLogs(q: AiCallLogQuery = {}): Promise<AiCallLogPage> {
  const payload = await http.get<unknown>(`${API_BASE}/audit/ai-calls${toQuery({ ...q })}`)
  return unwrapBiz<AiCallLogPage>(payload, '获取 AI 调用日志失败')
}

/**
 * 按 traceId 下钻完整链路。
 *
 * 后端在无记录时返回 40004，unwrapBiz 会抛 HttpError——
 * 调用方据此展示「该链路无记录，可能已过保留期」，而不是显示空白面板。
 */
export async function fetchTraceDetail(traceId: string): Promise<TraceDetail> {
  const payload = await http.get<unknown>(
    `${API_BASE}/audit/trace/${encodeURIComponent(traceId)}`
  )
  return unwrapBiz<TraceDetail>(payload, '获取链路详情失败')
}

export async function fetchAuditFilterOptions(): Promise<FilterOptions> {
  const payload = await http.get<unknown>(`${API_BASE}/audit/filter-options`)
  return unwrapBiz<FilterOptions>(payload, '获取筛选选项失败')
}
