/**
 * 工单相关类型定义
 * 定义前后端交互的数据结构
 */

// 后端工单状态枚举
// VOID 为 Saga 补偿作废态，与 CLOSED（正常关闭）语义不同，需单独区分
export type BackendTicketStatus = 'PENDING' | 'PROCESSING' | 'RESOLVED' | 'CLOSED' | 'VOID'

// 前端工单状态枚举
export type FrontendTicketStatus = 'pending' | 'processing' | 'resolved' | 'closed' | 'void'

// 后端优先级枚举
/**
 * 后端优先级四档（B0 起，原 HIGH/MEDIUM/LOW 三档）
 *
 * 三档时前端 urgent 与 high 都映射为 HIGH，回读又恒为 urgent——
 * 「高」这一档事实上不存在，用户选了会被静默改成「紧急」。
 * 四档后与前端一一对应：urgent↔P0 / high↔P1 / medium↔P2 / low↔P3。
 *
 * 旧值保留在联合类型中：库中可能有未迁移的历史数据，
 * 类型上承认其存在才能在映射函数里显式兜底，而不是让它落入 undefined。
 */
export type BackendTicketPriority = 'P0' | 'P1' | 'P2' | 'P3' | 'HIGH' | 'MEDIUM' | 'LOW'

// 前端优先级枚举
export type FrontendTicketPriority = 'urgent' | 'high' | 'medium' | 'low'

/**
 * 后端工单数据结构
 */
export interface BackendTicket {
  id: string
  title: string
  priority: BackendTicketPriority
  module: string
  description: string
  stackTrace?: string
  status: BackendTicketStatus
  sourceTraceId?: string
  assignee?: string
  creator?: string
  category?: string
  sla?: string
  /** 乐观锁版本号（P1-4）。更新时须回传，否则丢失并发保护 */
  version?: number
  /** 标签列表，来自 sys_ticket_tag 关联表 */
  tags?: string[]
  /** SLA 已消耗百分比（0~100），后端按 create_time 与 sla 时限推算 */
  slaProgress?: number
  /** SLA 是否已超时。与百分比分开：进度封顶 100 后无法区分「刚好用完」与「严重超时」 */
  slaBreached?: boolean
  /**
   * 距解决截止的剩余分钟数（负数=已超时的分钟数）
   *
   * B0 新增。关闭 6.42 遗留限制：此前悬浮卡只能显示「已消耗 75%」，
   * 而运维需要的是「还剩 45 分钟」——百分比无法转化为行动。
   * 由后端算（6.15 契约），null 表示无法计算，前端应隐藏该项而非显示 0。
   */
  slaRemainingMinutes?: number | null
  /** 首次响应时刻；null=尚未首响（B1） */
  firstResponseAt?: string | null
  /** 首响人 */
  firstResponder?: string | null
  /** MTTA：首响耗时（分钟）；null=尚未首响（不是 0——0 意为秒级响应） */
  firstResponseMinutes?: number | null
  /** 距首响截止的剩余分钟数（负数=已超时）；已首响或终态返回 null */
  responseRemainingMinutes?: number | null
  /** 首响状态：RESPONDED已首响 / BREACHED已超时 / AT_RISK即将超时 / WAITING待首响 */
  firstResponseState?: 'RESPONDED' | 'BREACHED' | 'AT_RISK' | 'WAITING'
  /** 首响是否已超时（由定时扫描固化，不因事后补首响而消失） */
  responseBreached?: boolean
  /** 升级时刻；null=未升级 */
  escalatedAt?: string | null
  /** 升级原因 */
  escalateReason?: string | null
  /** B2 处置阶段：TRIAGE/MITIGATED/FIXING/VERIFYING（仅 PROCESSING 期间有效） */
  handlingStage?: string | null
  /** B2 止损完成时刻 */
  mitigatedAt?: string | null
  /** B3 根因确认时刻 */
  rootCauseAt?: string | null
  /** B3 验证通过时刻 */
  verifiedAt?: string | null
  /** B3 是否跳过验证 */
  verifySkipped?: boolean
  /** B3 根因分类 */
  rootCauseCategory?: string | null
  /** B3 人工确认的根因（可读描述，非 AI 建议） */
  rootCause?: string | null
  /** B3 根因确认人 */
  rootCauseBy?: string | null
  /** B3 验证人 */
  verifier?: string | null
  /** B3 验证方式（MONITOR/LOG/BUSINESS/MANUAL） */
  verifyMethod?: string | null
  /** B3 验证结论 */
  verifyConclusion?: string | null
  /** B3 跳过验证的理由（verifySkipped=true 时必填） */
  verifySkipReason?: string | null
  createTime: string
  updateTime: string
}

/**
 * 前端工单数据结构
 */
export interface FrontendTicket {
  id: string
  title: string
  description: string
  status: FrontendTicketStatus
  priority: FrontendTicketPriority
  assignee: string
  creator: string
  createdAt: string
  updatedAt: string
  service: string
  category: string
  tags: string[]
  sla: string
  /** SLA 已消耗百分比，由后端计算 */
  slaProgress: number
  /** SLA 是否已超时 */
  slaBreached: boolean
  /** 距解决截止的剩余分钟数（负数=已超时）；null=无法计算 */
  slaRemainingMinutes: number | null
  /** 首响状态（B1，后端计算，阈值属业务规则不散落前端） */
  firstResponseState: 'RESPONDED' | 'BREACHED' | 'AT_RISK' | 'WAITING'
  /** MTTA：首响耗时（分钟）；null=尚未首响 */
  firstResponseMinutes: number | null
  /** 距首响截止剩余分钟（负数=已超时）；已首响或终态为 null */
  responseRemainingMinutes: number | null
  /** 首响人 */
  firstResponder: string | null
  /** 升级原因；null=未升级 */
  escalateReason: string | null
  /** B2 处置阶段；null=未设置 */
  handlingStage: string | null
  /** B2 止损完成时刻；null=未止损 */
  mitigatedAt?: string | null
  /** B3 根因确认时刻；null=未确认 */
  rootCauseAt?: string | null
  /** B3 验证通过时刻；null=未验证 */
  verifiedAt?: string | null
  /** B3 验证方式 */
  verifyMethod?: string | null
  /** B3 验证结论 */
  verifyConclusion?: string | null
  /** B3 是否跳过验证 */
  verifySkipped?: boolean
  /** B3 跳过验证的理由 */
  verifySkipReason?: string | null
  /** B3 人工确认的根因（可读描述） */
  rootCause?: string | null
  /** B3 根因确认人 */
  rootCauseBy?: string | null
  /** B3 验证人 */
  verifier?: string | null
  /** B3 根因分类；null=未确认 */
  rootCauseCategory: string | null
  attachments: string[]
  replies: TicketReply[]
  activities: TicketActivity[]
  /**
   * 乐观锁版本号（P1-4）
   * <p>提交更新时回传，服务端据此拒绝覆盖他人的修改。</p>
   */
  version: number
}

/**
 * 工单回复
 */
export interface TicketReply {
  role: 'creator' | 'agent' | 'ai' | 'system'
  author: string
  authorColor?: string
  time: string
  content: string
}

/**
 * 工单活动
 */
export interface TicketActivity {
  color: 'success' | 'primary' | 'gray' | 'warning'
  text: string
  detail?: string
  user: string
  time: string
  highlight?: boolean
}

/**
 * 工单列表查询参数
 */
export interface TicketsRequest {
  page?: number
  size?: number
  /** 前端小写枚举，API 层会映射为后端大写值 */
  status?: FrontendTicketStatus
  /** 前端小写枚举，API 层会映射（urgent → HIGH） */
  priority?: FrontendTicketPriority
  /** 关键词，后端匹配工单号/标题/描述 */
  keyword?: string
  /** 前端服务展示名，API 层会转为后端 module 枚举 */
  service?: string
  category?: string
  assignee?: string
  /** 创建时间下界 yyyy-MM-dd */
  createdFrom?: string
  /** 创建时间上界 yyyy-MM-dd（含当天） */
  createdTo?: string
  /** 标签，AND 语义（须同时含全部） */
  tags?: string[]
  /**
   * 排序字段（前端字段名，如 priority / createdAt / status）
   *
   * 排序必须由后端执行：前端表格本地排序只作用于当前页，
   * 「按优先级排序」会漏掉页外更高优先级的工单。
   * 后端有白名单校验，未知字段降级为默认排序（create_time DESC）。
   */
  sortBy?: string
  /** true=升序，false/省略=降序 */
  sortAsc?: boolean
}

/**
 * 工单列表响应
 */
export interface TicketsResponse {
  tickets: BackendTicket[]
  total: number
  page: number
  size: number
  totalPages: number
}
