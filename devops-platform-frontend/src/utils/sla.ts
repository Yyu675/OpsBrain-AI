/**
 * SLA 展示派生工具。
 *
 * 时限数与超时判定全部由后端计算（6.15 契约：派生字段在后端算，
 * 避免各端实现不一致）。前端只负责把分钟数转成可读文案。
 *
 * 提取到共享模块的原因：SLA 剩余时间会同时出现在工单列表悬浮卡、
 * SLA 风险面板等多处，各写一遍必然在「已超时」的措辞与降级口径上漂移。
 */

export interface SlaLike {
  slaRemainingMinutes?: number | null
  slaProgress?: number
  slaBreached?: boolean
}

/**
 * 把分钟数转为「N 天 N 小时 / N 小时 N 分钟 / N 分钟」。
 *
 * 只做绝对值格式化，正负号语义由调用方决定。
 */
export function formatMinutes(minutes: number): string {
  const abs = Math.abs(Math.round(minutes))
  if (abs >= 1440) {
    const days = Math.floor(abs / 1440)
    const hours = Math.floor((abs % 1440) / 60)
    return hours > 0 ? `${days} 天 ${hours} 小时` : `${days} 天`
  }
  if (abs >= 60) {
    const hours = Math.floor(abs / 60)
    const mins = abs % 60
    return mins > 0 ? `${hours} 小时 ${mins} 分钟` : `${hours} 小时`
  }
  return `${abs} 分钟`
}

/**
 * SLA 剩余时间文案。
 *
 * `slaRemainingMinutes` 为 null 表示后端无法计算（如缺少 deadline），
 * 此时如实退回百分比口径，不编造时间——null 与 0 的区分见 6.38：
 * 0 意为「刚好用完」，null 意为「未知」。
 */
export function slaRemainText(row: SlaLike): string {
  const m = row.slaRemainingMinutes
  if (m === null || m === undefined) {
    return row.slaBreached ? '已超时' : `已消耗 ${row.slaProgress ?? 0}%`
  }
  return m < 0 ? `已超时 ${formatMinutes(m)}` : `还剩 ${formatMinutes(m)}`
}

/** SLA 紧急度分档，用于配色（超时红 / ≥70% 橙 / 其余正常） */
export type SlaSeverity = 'breached' | 'warning' | 'normal'

export function slaSeverity(row: SlaLike): SlaSeverity {
  if (row.slaBreached) return 'breached'
  if ((row.slaProgress ?? 0) >= 70) return 'warning'
  return 'normal'
}

// ==================== 首响状态（B1） ====================

export type FirstResponseState = 'RESPONDED' | 'BREACHED' | 'AT_RISK' | 'WAITING'

const FIRST_RESPONSE_LABELS: Record<FirstResponseState, string> = {
  RESPONDED: '已首响',
  BREACHED: '首响超时',
  AT_RISK: '即将超时',
  WAITING: '待首响'
}

export function firstResponseLabel(state?: string | null): string {
  if (!state) return '待首响'
  return FIRST_RESPONSE_LABELS[state as FirstResponseState] ?? state
}

/**
 * 首响状态的行内文案（含 MTTA）。
 *
 * 已首响时顺带展示耗时，比单说「已响应」信息量更大——运维扫列表时
 * 关心的是「响应有多快」而非「是否响应过」。
 */
export function firstResponseText(row: {
  firstResponseState?: string | null
  firstResponseMinutes?: number | null
}): string {
  if (row.firstResponseState === 'RESPONDED') {
    const m = row.firstResponseMinutes
    return m === null || m === undefined ? '已响应' : `${formatMinutes(m)}响应`
  }
  return firstResponseLabel(row.firstResponseState)
}

/**
 * 首响 tooltip：补充首响人或距截止的剩余时间。
 */
export function firstResponseTitle(row: {
  firstResponseState?: string | null
  firstResponder?: string | null
  responseRemainingMinutes?: number | null
}): string {
  if (row.firstResponseState === 'RESPONDED') {
    return row.firstResponder ? `首响人：${row.firstResponder}` : '已首响'
  }
  const r = row.responseRemainingMinutes
  if (r === null || r === undefined) return '待首响'
  return r < 0
    ? `首响已超时 ${formatMinutes(r)}`
    : `距首响截止还剩 ${formatMinutes(r)}`
}

/** 首响状态对应的 el-tag type */
export function firstResponseTagType(
  state?: string | null
): 'danger' | 'warning' | 'success' | 'info' {
  switch (state) {
    case 'BREACHED': return 'danger'
    case 'AT_RISK': return 'warning'
    case 'RESPONDED': return 'success'
    default: return 'info'
  }
}

/**
 * 首响耗时文案。
 *
 * null 表示尚未首响（而非「0 分钟」）——二者不能混同，
 * 否则 MTTA 展示会把未响应的工单当成秒级响应。
 */
export function firstResponseDurationText(minutes?: number | null): string {
  if (minutes === null || minutes === undefined) return '—'
  return formatMinutes(minutes)
}
