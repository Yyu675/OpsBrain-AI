/**
 * 工单状态 / 优先级单一来源。
 * 列表、详情、筛选、批量操作必须引用这里，避免漏 void 或样式漂移。
 */
export type TicketStatus = 'pending' | 'processing' | 'resolved' | 'closed' | 'void'
export type TicketPriority = 'urgent' | 'high' | 'medium' | 'low'

/**
 * 「未指派」哨兵值（单一来源）
 *
 * 它不是人，是「还没安排负责人」的占位。后端 sys_team_member 名录不含它，
 * 前端选人下拉框需在名录之外单独提供该选项，DTO 转换时也用它兜住空 assignee。
 *
 * 放在 constants 层而非 store：api/utils/dto-converter.ts 也要用，
 * 若定义在 store 会形成 store → api → store 的循环依赖。
 */
export const UNASSIGNED = '待分配'

export const TICKET_STATUS_LABELS: Record<TicketStatus, string> = {
  pending: '待处理',
  processing: '处理中',
  resolved: '已解决',
  closed: '已关闭',
  void: '已作废'
}

export const TICKET_PRIORITY_LABELS: Record<TicketPriority, string> = {
  urgent: '紧急',
  high: '高',
  medium: '中',
  low: '低'
}

export const TICKET_STATUS_OPTIONS: { value: TicketStatus; label: string }[] = (
  Object.entries(TICKET_STATUS_LABELS) as [TicketStatus, string][]
).map(([value, label]) => ({ value, label }))

export const TICKET_PRIORITY_OPTIONS: { value: TicketPriority; label: string }[] = (
  Object.entries(TICKET_PRIORITY_LABELS) as [TicketPriority, string][]
).map(([value, label]) => ({ value, label }))

/**
 * 合法状态流转表 —— 必须与后端 `TicketEnums.Status.ALLOWED_TRANSITIONS` 一致。
 *
 * 前端有这张表的意义不是「代替后端校验」（后端仍会独立校验），
 * 而是**把非法选项直接置灰**：让用户在点击之前就知道哪些操作不可用，
 * 而不是点了之后收到一个报错。这是「防呆」与「事后报错」的区别。
 *
 * ⚠️ 两侧不一致会造成两类问题：
 *   - 前端比后端宽 → 用户能点，点了报错，体验割裂；
 *   - 前端比后端严 → 明明能做的操作被置灰，用户以为是 bug。
 * 修改任一侧都必须同步另一侧。
 */
const ALLOWED_TRANSITIONS: Record<TicketStatus, TicketStatus[]> = {
  pending: ['processing', 'resolved', 'void'],
  processing: ['pending', 'resolved', 'void'],
  resolved: ['closed', 'processing'],
  // 已关闭仅允许「复发重开」
  closed: ['processing'],
  // 作废是不可逆终态：复活会让「这张单到底存不存在」不可判定
  void: []
}

/** 判断流转是否合法。同态视为合法（幂等重试不应报错） */
export const canTransitionStatus = (
  from: TicketStatus | string | undefined,
  to: TicketStatus | string
): boolean => {
  if (!from) return false
  if (from === to) return true
  return (ALLOWED_TRANSITIONS[from as TicketStatus] ?? []).includes(to as TicketStatus)
}

/** 当前状态可流转到的状态列表 */
export const nextStatuses = (from: TicketStatus | string | undefined): TicketStatus[] =>
  from ? (ALLOWED_TRANSITIONS[from as TicketStatus] ?? []) : []

/** 是否终态（不可再流转）——前端据此整体禁用状态切换控件 */
export const isTerminalStatus = (s: TicketStatus | string | undefined): boolean =>
  s === 'void'

export const getStatusLabel = (s: TicketStatus | string) =>
  TICKET_STATUS_LABELS[s as TicketStatus] || s

/**
 * 状态下拉选项（带 disabled 标记）。
 * 直接喂给 el-select 的 v-for，非法项自动置灰并给出原因。
 */
export const statusOptionsFor = (
  current: TicketStatus | string | undefined
): { value: TicketStatus; label: string; disabled: boolean; reason?: string }[] =>
  TICKET_STATUS_OPTIONS.map((o) => {
    const ok = canTransitionStatus(current, o.value)
    return {
      ...o,
      disabled: !ok,
      reason: ok
        ? undefined
        : isTerminalStatus(current)
          ? '已作废的工单不可再变更状态'
          : `不能从「${getStatusLabel(current ?? '')}」直接变更为「${o.label}」`
    }
  })


export const getPriorityLabel = (p: TicketPriority | string) =>
  TICKET_PRIORITY_LABELS[p as TicketPriority] || p

export const getStatusClass = (s: TicketStatus | string) => `status-${s}`
export const getPriorityClass = (p: TicketPriority | string) => `priority-${p}`
