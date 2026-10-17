/**
 * L2 指标查询 API。
 *
 * 对应后端 `MetricsController`（/api/v1/metrics/**，登录即可）。
 *
 * ── 数据从哪来 ────────────────────────────────────────────────
 * 后端**代理 Prometheus 查询，不自建时序存储**。这意味着：
 *   - Prometheus 挂了本页就没数据 —— 这是正确的失败模式，
 *     监控数据本就该以监控系统为准，而不是看一份陈旧副本以为系统正常
 *   - 错误码 50020（METRICS_UNAVAILABLE）的 retry 语义是 NEVER，
 *     页面应引导用户去「接入管理」检查，而不是让他反复刷新
 *
 * ── 为什么只传指标 ID 而不是 PromQL ───────────────────────────
 * PromQL 留在后端的 MetricsCatalog 里。任意 PromQL 等于把 Prometheus
 * 的算力开放出去，一条高基数聚合就能拖垮整个监控体系；
 * 且前端各页各写查询语句时，指标改名要改十几处，
 * 改漏的地方只表现为「图表空白」，没有任何报错。
 */
import { API_BASE } from '../config/api'
import { http, unwrapBiz } from '../utils/http'

// ==================== 类型 ====================

/** 指标单位。前端据此决定格式化方式（百分比 / 字节 / 原值） */
export type MetricUnit = 'percent' | 'bytes' | 'count' | 'seconds'

export interface MetricMeta {
  id: string
  name: string
  unit: MetricUnit
  /** 这条指标在回答什么问题——面向运维，不是实现说明 */
  describe: string
}

export interface MetricCatalog {
  metrics: MetricMeta[]
  /** 后端是否启用了 Prometheus 集成 */
  enabled: boolean
}

/**
 * 一个瞬时采样点。
 *
 * `value` 可能为 null —— Prometheus 对刚重启的实例、除零的 rate
 * 返回 NaN，后端已转成 null（JSON 没有 NaN 字面量）。
 * **调用方必须判空**，否则会渲染出字面量 "null" 或让算术结果污染成 NaN。
 */
export interface MetricSample {
  labels: Record<string, string>
  value: number | null
  timestamp: number
}

export interface InstantResult {
  metric: string
  samples: MetricSample[]
}

/** 时间线上的一个点。v 为 null 表示该时刻无数据（断点） */
export interface SeriesPoint {
  t: number
  v: number | null
}

export interface MetricSeries {
  labels: Record<string, string>
  points: SeriesPoint[]
}

export interface RangeResult {
  metric: string
  from: number
  to: number
  step: number
  /** 后端可能把超界的 hours 夹紧，这里回报的是**生效值** */
  hours: number
  series: MetricSeries[]
}

/** 总览卡片。单条失败不影响其余，用 ok/error 如实标注 */
export interface OverviewCard {
  name: string
  unit: MetricUnit
  describe: string
  ok: boolean
  error?: string
  samples: MetricSample[]
}

export interface OverviewResult {
  cards: Record<string, OverviewCard>
  timestamp: number
}

export interface Datasource {
  type: string
  name: string
  baseUrl: string
  enabled: boolean
  reachable: boolean
  latencyMs?: number
  error?: string
}

export interface DatasourceResult {
  datasources: Datasource[]
  total: number
}

// ==================== 接口 ====================

export async function fetchMetricCatalog(): Promise<MetricCatalog> {
  const payload = await http.get<unknown>(`${API_BASE}/metrics/catalog`)
  return unwrapBiz<MetricCatalog>(payload, '获取指标目录失败')
}

export async function fetchInstant(metric: string): Promise<InstantResult> {
  const payload = await http.get<unknown>(
    `${API_BASE}/metrics/instant?metric=${encodeURIComponent(metric)}`
  )
  return unwrapBiz<InstantResult>(payload, '获取指标失败')
}

export async function fetchRange(
  metric: string,
  hours = 1,
  step = 60
): Promise<RangeResult> {
  const payload = await http.get<unknown>(
    `${API_BASE}/metrics/range?metric=${encodeURIComponent(metric)}&hours=${hours}&step=${step}`
  )
  return unwrapBiz<RangeResult>(payload, '获取趋势数据失败')
}

export async function fetchOverview(): Promise<OverviewResult> {
  const payload = await http.get<unknown>(`${API_BASE}/metrics/overview`)
  return unwrapBiz<OverviewResult>(payload, '获取监控总览失败')
}

/**
 * 数据源健康检查。
 *
 * 后端刻意**不抛异常**——「连不上」正是这个端点要报告的结果。
 * 若它也返回 503，接入管理页自己就打不开了，那就本末倒置。
 */
export async function fetchDatasources(): Promise<DatasourceResult> {
  const payload = await http.get<unknown>(`${API_BASE}/metrics/datasource`)
  return unwrapBiz<DatasourceResult>(payload, '获取数据源状态失败')
}

// ==================== 格式化 ====================

/**
 * 按单位格式化指标值。
 *
 * null 统一渲染成 `—` 而非 "null"/"NaN"：无数据是正常状态
 * （实例刚重启、目标下线），显示成 null 会让用户以为系统出错。
 */
export function formatMetricValue(value: number | null, unit: MetricUnit): string {
  if (value === null || value === undefined || Number.isNaN(value)) {
    return '—'
  }
  switch (unit) {
    case 'percent':
      return `${value.toFixed(1)}%`
    case 'bytes':
      return formatBytes(value)
    case 'seconds':
      return `${value.toFixed(2)}s`
    default:
      // 负载这类小数保留两位，计数类取整
      return Number.isInteger(value) ? String(value) : value.toFixed(2)
  }
}

/** 字节速率格式化。基数用 1024，与运维工具惯例一致 */
export function formatBytes(bytes: number): string {
  const abs = Math.abs(bytes)
  if (abs < 1024) return `${bytes.toFixed(0)} B/s`
  if (abs < 1024 ** 2) return `${(bytes / 1024).toFixed(1)} KB/s`
  if (abs < 1024 ** 3) return `${(bytes / 1024 ** 2).toFixed(1)} MB/s`
  return `${(bytes / 1024 ** 3).toFixed(2)} GB/s`
}

/**
 * 按阈值判定健康档位，供 UI 着色。
 *
 * 只对 percent 类指标有意义——把 CPU 80% 和「负载 8」用同一套阈值
 * 判定是错的，后者要和核数比。所以其他单位一律返回 normal，
 * 宁可不着色，也不给出误导性的红色告警。
 */
export function severityOf(value: number | null, unit: MetricUnit): 'normal' | 'warn' | 'danger' {
  if (value === null || unit !== 'percent') return 'normal'
  if (value >= 90) return 'danger'
  if (value >= 75) return 'warn'
  return 'normal'
}

/**
 * 从标签里取一个稳定的展示名。
 *
 * 优先级 instance > device > mountpoint > job：
 * 前两个能定位到具体机器/网卡，job 只是抓取任务名，粒度太粗。
 * 全都没有时返回 '默认'——返回空串会让表格出现一列空白，
 * 用户不知道那行代表什么。
 */
export function labelOf(labels: Record<string, string>): string {
  return (
    labels?.instance ||
    labels?.device ||
    labels?.mountpoint ||
    labels?.job ||
    '默认'
  )
}
