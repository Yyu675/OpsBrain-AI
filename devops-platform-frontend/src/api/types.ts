/**
 * API 类型定义 - 对应后端接口契约
 * 参考文档: docs/05-development-design/03-API接口设计.md
 */

// ==================== 统一响应结构 ====================

/**
 * 统一响应包装（非流式接口）
 */
export interface ApiResponse<T = unknown> {
  code: number
  message: string
  data: T
  traceId: string
  timestamp: number
}

/**
 * 错误码常量
 */
export const ErrorCode = {
  SUCCESS: 0,
  PARAM_ERROR: 40001,           // 参数校验失败
  SECURITY_BLOCK: 40301,        // 输入安全拦截
  RATE_LIMIT: 42901,            // 上游大模型限流
  INTERNAL_ERROR: 50001,        // 服务内部异常
  SERVICE_UNAVAILABLE: 50301,   // 上游全链路不可用
} as const

// ==================== SSE 流式事件 ====================

/**
 * SSE 事件类型
 */
export type SSEEventType = 'start' | 'tool_status' | 'token' | 'complete' | 'error'

/**
 * SSE 基础事件
 *
 * data 的具体形状由 event 决定（见下方 SSEStartEvent / SSETokenEvent 等）。
 * 用 unknown 强制消费方先按 event 分支再窄化，避免直接当作某一类事件误读字段。
 */
export interface SSEEvent {
  event: SSEEventType
  data: unknown
}

/**
 * start 事件 - 会话开始
 */
export interface SSEStartEvent {
  traceId: string
  timestamp: number
  routerModel: string  // deepseek-chat / deepseek-reasoner / Mock-Engine
}

/**
 * tool_status 事件 - 工具执行中间态
 */
export interface SSEToolStatusEvent {
  toolName: 'searchDevOpsKnowledge' | 'createDevOpsTicket'
  status: 'start' | 'success' | 'error'
  message: string
}

/**
 * token 事件 - 打字机文本块
 */
export interface SSETokenEvent {
  text: string
}

/**
 * complete 事件 - 会话结束
 */
export interface SSECompleteEvent {
  traceId: string
  latencyMs: number
  isCached: boolean
  costRmb: number        // 成本字段（人民币元）
  citations: string[]    // 引用出处
  toolResults?: Array<{  // 工具调用结果
    toolName: string
    /**
     * 工具返回载荷。形状随 toolName 而异（如 createDevOpsTicket 返回 { ticketId }），
     * 消费方需自行窄化——用 unknown 而非 any，强制调用点做显式类型断言。
     */
    result: unknown
  }>
}

/**
 * error 事件 - 异常/安全拦截
 */
export interface SSEErrorEvent {
  traceId: string
  code: number
  message: string
}

// ==================== 看板统计 ====================

/**
 * 看板聚合统计响应
 */
export interface DashboardOverview {
  totalQueries: number         // 总查询次数
  cacheHits: number            // 缓存命中次数
  cacheHitRate: number         // 缓存命中率（百分比）
  avgCostRmb: number           // 平均成本（元）
  totalTickets: number         // 总工单数
  modelDistribution: Array<{   // P1-8 契约对齐：{model, count, percentage}
    model: string
    count: number
    percentage: number
  }>
  costSavingsChart: Array<{    // 7 日成本趋势
    date: string
    cost: number
  }>
}

// ==================== RAG 知识文档（6.21 生命周期治理）====================

/**
 * 知识文档状态
 * DRAFT 草稿 / PUBLISHED 已发布 / DEPRECATED 已废弃 / ARCHIVED 已归档
 */
export type KnowledgeDocStatus = 'DRAFT' | 'PUBLISHED' | 'DEPRECATED' | 'ARCHIVED'

/**
 * 向量化状态
 * PENDING 待向量化 / INDEXED 已建索引 / FAILED 失败 / SKIPPED 无需索引
 */
export type KnowledgeIndexStatus = 'PENDING' | 'INDEXED' | 'FAILED' | 'SKIPPED'

/**
 * 知识文档列表项（不含正文）
 */
export interface KnowledgeDocListItem {
  id: number
  title: string
  category: string | null
  categoryId?: number | null
  author: string | null
  summary: string | null
  version: number
  status: KnowledgeDocStatus
  indexStatus: KnowledgeIndexStatus
  chunkCount: number
  createTime: string
  updateTime: string
  tags: string[]
  /** L1.5 来源回链：源工单 ID，非工单沉淀为 null */
  sourceTicketId: number | null
  /** 来源类型：TICKET / MANUAL / IMPORT 等 */
  sourceType: string | null
}

/**
 * 知识文档详情（含正文）
 */
export interface KnowledgeDocDetail {
  id: number
  title: string
  category: string | null
  categoryId?: number | null
  author: string | null
  content: string
  summary: string | null
  version: number
  status: KnowledgeDocStatus
  indexStatus: KnowledgeIndexStatus
  indexError: string | null
  chunkCount: number
  indexedAt: string | null
  effectiveAt: string | null
  expiredAt: string | null
  knowledgeSource: string | null
  createTime: string
  updateTime: string
  tags: string[]
  /** L1.5 来源回链：源工单 ID，非工单沉淀时为 null */
  sourceTicketId: number | null
  /** 来源类型：TICKET / MANUAL / IMPORT 等 */
  sourceType: string | null
  /** 是否可检索：status=PUBLISHED 且 index=INDEXED */
  retrievable: boolean
}

/**
 * 创建文档请求
 */
export interface KnowledgeDocCreateRequest {
  title: string
  category?: string
  categoryId?: number | null
  author?: string
  content: string
  summary?: string
  tags?: string[]
  /** true=发布（立即向量化）；false=存草稿 */
  publish: boolean
  knowledgeSource?: string
  effectiveAt?: string
  expiredAt?: string
  /** L1.5 来源回链：由工单沉淀时传源工单 ID */
  sourceTicketId?: number
  /** 来源类型：TICKET / MANUAL / IMPORT 等 */
  sourceType?: string
}

/**
 * 更新文档请求
 */
export interface KnowledgeDocUpdateRequest {
  title?: string
  category?: string
  categoryId?: number | null
  author?: string
  content?: string
  summary?: string
  tags?: string[]
  /** 乐观锁版本号 */
  version?: number
  changeReason?: string
}

/**
 * 近似重复项（创建/更新时返回，不阻断）
 */
export interface KnowledgeNearDuplicate {
  docId: number
  title: string
  distance: number
}

/**
 * 向量化结果
 */
export interface KnowledgeIndexOutcome {
  status: KnowledgeIndexStatus | 'UNCHANGED'
  chunkCount: number
  dedupedCount: number
  error: string | null
}

/**
 * 保存结果（创建/更新通用）
 */
export interface KnowledgeDocSaveResult {
  id: number
  version: number
  status: KnowledgeDocStatus | null
  indexStatus: KnowledgeIndexStatus | null
  retrievable: boolean
  nearDuplicates: KnowledgeNearDuplicate[]
  indexOutcome: KnowledgeIndexOutcome | null
}

/**
 * 历史版本项（不含正文）
 */
export interface KnowledgeDocVersion {
  docId: number
  version: number
  title: string
  category: string | null
  author: string | null
  contentLength: number
  changeType: string
  changedBy: string | null
  changeReason: string | null
  createTime: string
}

/**
 * 知识文档分页响应
 */
export interface KnowledgeDocPageResponse {
  content: KnowledgeDocListItem[]
  totalElements: number
  totalPages: number
  currentPage: number
  pageSize: number
}

/**
 * 扁平分类项（侧栏导航，后端全库聚合，含文档数）
 */
export interface KnowledgeDocCategory {
  name: string
  count: number
}

/** 可独立维护的知识库目录分类。 */
export interface KnowledgeCategoryEntity {
  id: number
  parentId: number | null
  name: string
  sortOrder: number
  docCount: number
  createTime?: string
  updateTime?: string
}

export interface KnowledgeTreeDocument {
  id: number
  title: string
  category: string | null
  categoryId?: number | null
  version: number
  status: KnowledgeDocStatus
  updateTime: string
}

export interface KnowledgeCategoryTreeNode extends KnowledgeCategoryEntity {
  documents: KnowledgeTreeDocument[]
}

export interface KnowledgeCategoryTreeResponse {
  categories: KnowledgeCategoryTreeNode[]
  uncategorized: KnowledgeTreeDocument[]
}

/**
 * 热门标签项（后端全库聚合，仅 PUBLISHED 文档计数）
 */
export interface KnowledgeHotTag {
  tag: string
  count: number
}

export interface KnowledgeTag {
  id: number
  name: string
  description: string | null
  color: string | null
  usageCount: number
}

/**
 * 版本对比差异段（R11）
 * @param type  "EQUAL" | "DELETE" | "INSERT"（变更为被删行，INSERT 为新增行）
 * @param lines 该段包含的行
 */
export interface KnowledgeDiffSegment {
  type: 'EQUAL' | 'DELETE' | 'INSERT'
  lines: string[]
}

/**
 * 版本对比结果（R11，GET /docs/{id}/compare?fromV=&toV=）
 */
export interface KnowledgeVersionDiff {
  fromVersion: number
  toVersion: number
  fromTitle: string
  toTitle: string
  segments: KnowledgeDiffSegment[]
}

// ==================== 团队成员（工单负责人名录） ====================

/**
 * 团队成员（GET /api/v1/users）
 *
 * A2：此前前端硬编码 ASSIGNEE_OPTIONS 七人编造名单，库里只有「张明」一个真实负责人，
 * 工单会被指派给不存在的人。现由后端 sys_team_member 表下发。
 *
 * @param status ACTIVE=在册可指派 / DISABLED=已停用 / LEGACY=不在册但历史工单指派过
 *               （LEGACY 必须下发，否则下拉框选不中当前负责人，用户误以为工单未指派）
 * @param activeTicketCount 该成员进行中（待处理/处理中）的工单数，供选人时参考负载
 */
export interface TeamMember {
  id?: number
  name: string
  email?: string | null
  role: string
  title?: string | null
  status: 'ACTIVE' | 'DISABLED' | 'LEGACY'
  sortOrder?: number
  activeTicketCount?: number
}

// ==================== L2 告警（Stage 3） ====================

/**
 * 告警状态
 * FIRING 触发中 / ACKNOWLEDGED 已确认 / RESOLVED 已恢复
 */
export type AlertStatus = 'FIRING' | 'ACKNOWLEDGED' | 'RESOLVED'

/**
 * 告警实体（GET /api/v1/alerts，18 字段与后端 Alert 实体对齐）
 *
 * 注意：与 WebSocket 推送的 12 字段 AlertPayload 不同——
 * REST 列表返回完整实体，含 source / dedupKey / 三处时间戳 / ticketId 等。
 */
export interface Alert {
  id: number
  source: string | null
  alertName: string | null
  level: string | null
  title: string | null
  description: string | null
  status: AlertStatus | null
  dedupKey: string | null
  service: string | null
  module: string | null
  occurrenceCount: number | null
  firstOccurredAt: string | null
  lastOccurredAt: string | null
  acknowledgedAt: string | null
  resolvedAt: string | null
  ticketId: string | null
  createTime: string | null
  updateTime: string | null
}

/**
 * 告警列表分页响应（与 AlertController.listAlerts 返回的 Map 对齐）
 */
export interface AlertsResponse {
  alerts: Alert[]
  total: number
  page: number
  size: number
  totalPages: number
}

