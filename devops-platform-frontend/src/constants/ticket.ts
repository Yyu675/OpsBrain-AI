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

export const getStatusLabel = (s: TicketStatus | string) =>
  TICKET_STATUS_LABELS[s as TicketStatus] || s

export const getPriorityLabel = (p: TicketPriority | string) =>
  TICKET_PRIORITY_LABELS[p as TicketPriority] || p

export const getStatusClass = (s: TicketStatus | string) => `status-${s}`
export const getPriorityClass = (p: TicketPriority | string) => `priority-${p}`
