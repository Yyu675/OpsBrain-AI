/**
 * Agent 会话执行轨迹 API。
 *
 * 对应后端 `AgentTraceController`（/api/v1/agent/traces/**）。
 *
 * 与 `auditLogs.ts` 的 `fetchTraceDetail` 是两条互补的线：
 * - 那条查的是**落库的**审计记录（AI 调用、成本、问答全文），长期保留；
 * - 本条查的是**内存里的**状态机轨迹（走过哪些环节、每段多久、卡在哪），
 *   30 分钟空闲即被清理。
 *
 * 换言之：审计回答「这次调用花了多少钱、说了什么」，
 * 轨迹回答「它到底走到哪一步、为什么没走完」。排障时后者更直接。
 */
import { API_BASE } from '../config/api'
import { http, unwrapBiz } from '../utils/http'

// ==================== 类型 ====================

/** 一次状态迁移记录 */
export interface AgentTransitionItem {
  id: string | null
  sessionId: string | null
  /** 来源状态机器码，如 EVIDENCE_READY */
  fromState: string | null
  /** 来源状态中文名，如「证据就绪」 */
  fromStateText: string | null
  toState: string | null
  toStateText: string | null
  /** 触发器类型，如 TOOL_STARTED */
  triggerType: string | null
  /** 触发详情，如「工具调用：createDevOpsTicket」 */
  triggerDetail: string | null
  operator: string | null
  timestamp: string | null
  /** 本段耗时（距上一次迁移），首段为 0 */
  durationMs: number
  metadata: string | null
}

/** 轨迹查询结果 */
export interface AgentTraceDetail {
  traceId: string
  /**
   * 会话是否存在。
   *
   * ⚠️ 这个字段不能省。`found=false`（会话已被清理或 traceId 有误）
   * 与 `found=true` 但 transitions 为空（流程真的卡在最开始）
   * 都表现为空列表，含义却完全相反——后者是真实故障信号。
   * 前端必须据此给出不同提示，否则会把人引向错误的排查方向。
   */
  found: boolean
  currentState: string | null
  currentStateText: string | null
  /** 是否已脱离自动流程（含补偿中/人工升级，它们非终态但已不再自动推进） */
  settled: boolean
  /** 是否为不可再迁移的终态（SUCCESS/FAILED/CLOSED） */
  terminal: boolean
  transitions: AgentTransitionItem[]
  transitionCount: number
  totalDurationMs: number
  /** 仅在 found=false 或零迁移时返回，说明「为什么看不到东西」 */
  message?: string
}

/** 状态管理器容量统计 */
export interface AgentTraceStats {
  activeSessions: number
  idleTimeoutMinutes: number
}

// ==================== 请求 ====================

/**
 * 查询单次会话的完整状态迁移轨迹。
 *
 * 后端对「查不到」不报错，而是返回 `found=false` + message，
 * 因此这里不需要 try/catch 兜 404——调用方读 `found` 即可。
 */
export async function fetchAgentTrace(traceId: string): Promise<AgentTraceDetail> {
  const payload = await http.get<unknown>(
    `${API_BASE}/agent/traces/${encodeURIComponent(traceId)}`
  )
  return unwrapBiz<AgentTraceDetail>(payload, '获取执行轨迹失败')
}

/**
 * 查询当前驻留会话数。
 *
 * 这个数字只涨不跌就说明空闲清理失效了，是内存泄漏的早期信号。
 */
export async function fetchAgentTraceStats(): Promise<AgentTraceStats> {
  const payload = await http.get<unknown>(`${API_BASE}/agent/traces/stats`)
  return unwrapBiz<AgentTraceStats>(payload, '获取会话统计失败')
}

// ==================== 展示辅助 ====================

/**
 * 状态对应的展示色。
 *
 * 归类依据是「运维看到它该不该紧张」，而不是状态机的拓扑位置：
 * - danger：出事了，需要人介入；
 * - warning：还没出事但已脱离自动流程；
 * - success：正常收尾；
 * - primary：进行中。
 */
export function agentStateColor(state: string | null): 'success' | 'danger' | 'warning' | 'primary' | 'gray' {
  if (!state) return 'gray'
  switch (state) {
    case 'SUCCESS':
      return 'success'
    case 'FAILED':
      return 'danger'
    // 补偿失败需人工清理脏数据，比单纯失败更需要被看见
    case 'MANUAL_ESCALATED':
      return 'danger'
    case 'COMPENSATING':
    case 'WAITING_APPROVAL':
      return 'warning'
    case 'CLOSED':
      return 'gray'
    default:
      return 'primary'
  }
}

/**
 * 耗时的人类可读格式。
 *
 * 注意 0 是有效读数（首段迁移耗时就是 0），不能用 `if (!ms)` 判空，
 * 否则「瞬间完成」会被显示成「-」，看起来像是没采到数据。
 */
export function formatDuration(ms: number | null | undefined): string {
  if (ms === null || ms === undefined) return '-'
  if (ms < 1000) return `${ms} ms`
  if (ms < 60_000) return `${(ms / 1000).toFixed(1)} s`
  const min = Math.floor(ms / 60_000)
  const sec = Math.round((ms % 60_000) / 1000)
  return `${min} 分 ${sec} 秒`
}

/**
 * 找出耗时最长的一段，用于「慢在哪」的快速定位。
 *
 * 返回 null 表示无迁移记录。刻意不返回一个「零值对象」——
 * 那会让调用方误以为存在一段耗时 0 的迁移。
 */
export function slowestTransition(
  transitions: AgentTransitionItem[]
): AgentTransitionItem | null {
  if (!transitions.length) return null
  return transitions.reduce((max, t) => (t.durationMs > max.durationMs ? t : max))
}
