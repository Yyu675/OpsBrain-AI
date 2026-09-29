/**
 * API 类型定义 - 对应后端接口契约
 * 参考文档: docs/05-development-design/03-API接口设计.md
 */

// ==================== 统一响应结构 ====================

/**
 * 统一响应包装（非流式接口）
 */
/** @public knip 假阳存证：%s */
export interface ApiResponse<T = unknown> {
  code: number
  message: string
  data: T
  traceId: string
  timestamp: number
}

// ==================== SSE 流式事件 ====================

// （SSEEventType/SSEEvent 成对死链已随批次 25 整删：EventType 的唯一消费者
// 就是那个零引用的 SSEEvent 接口——链断即双删，详见报告 128）
/**
 * SSE 基础事件
 *
 * data 的具体形状由 event 决定（见下方 SSEStartEvent / SSETokenEvent 等）。
 * 用 unknown 强制消费方先按 event 分支再窄化，避免直接当作某一类事件误读字段。
 */
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
  /** L1.5 来源回链：源工单字符串流水号（TKT-…），非工单沉淀为 null */
  sourceTicketId: string | null
  /** 来源类型：TICKET / MANUAL / IMPORT / UPLOAD 等 */
  sourceType: string | null
  /** 所属知识库 ID（V2） */
  kbId?: number | null
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
  /** L1.5 来源回链：源工单字符串流水号，非工单沉淀时为 null */
  sourceTicketId: string | null
  /** 来源类型：TICKET / MANUAL / IMPORT 等 */
  sourceType: string | null
  /** 是否可检索：status=PUBLISHED 且 index=INDEXED */
  retrievable: boolean
  /** 所属知识库 ID（V2） */
  kbId?: number | null
  /** 上传原件文件名（sourceType=UPLOAD 时非空） */
  originalFilename?: string | null
  /** 原件是否留存在对象存储（V2）：false=上传时留存降级，无原件可下载 */
  originalStored?: boolean
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
  /** L1.5 来源回链：由工单沉淀时传源工单字符串流水号（TKT-…） */
  sourceTicketId?: string
  /** 来源类型：TICKET / MANUAL / IMPORT 等 */
  sourceType?: string
  /** 所属知识库（V2）；不传落默认库 */
  kbId?: number | null
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
  /** 变更知识库归属（V2）；不传=不变。换库会触发重建索引 */
  kbId?: number | null
}

// ==================== 知识库（V2 顶层实体） ====================

/**
 * 知识库列表/详情项
 * <p>切片三参数为 null 表示跟随全局默认；effective* 是合并默认后的生效值。</p>
 */
export interface KnowledgeBaseItem {
  id: number
  name: string
  code: string
  description: string | null
  parentChunkSize: number | null
  childChunkSize: number | null
  chunkOverlap: number | null
  effectiveParentChunkSize: number
  effectiveChildChunkSize: number
  effectiveChunkOverlap: number
  status: 'ACTIVE' | 'DISABLED'
  docCount: number
  /** 已索引文档数（库级索引健康度） */
  indexedCount: number
  /** 索引失败文档数——发现「文档在库里但检索不到」空洞的入口 */
  failedCount: number
  createTime: string
  updateTime: string
}

/** 创建知识库请求（切片参数可全部缺省=跟随全局默认） */
export interface KnowledgeBaseCreateRequest {
  name: string
  code: string
  description?: string
  parentChunkSize?: number | null
  childChunkSize?: number | null
  chunkOverlap?: number | null
}

/** 更新知识库请求：null/undefined 字段不修改；clearChunkParams=true 清空切片参数回默认 */
export interface KnowledgeBaseUpdateRequest {
  name?: string
  code?: string
  description?: string
  parentChunkSize?: number | null
  childChunkSize?: number | null
  chunkOverlap?: number | null
  status?: 'ACTIVE' | 'DISABLED'
  clearChunkParams?: boolean
}

/** 按库重建索引结果 */
export interface KnowledgeBaseReindexResult {
  kbId: number
  kbName: string
  total: number
  success: number
  failed: number
  failures: Array<{ docId: number; title: string; indexStatus: string; error: string }>
}

/** 文件上传入库结果（V2） */
export interface KnowledgeDocUploadResult {
  id: number
  title: string
  version: number
  status: KnowledgeDocStatus
  indexStatus: KnowledgeIndexStatus | null
  retrievable: boolean
  /** Tika 解析出的文本长度 */
  parsedLength: number
  /** 原件是否已留存 MinIO（false=留存失败降级，不影响入库） */
  originalStored: boolean
  nearDuplicates: KnowledgeNearDuplicate[]
  indexError?: string
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
  /** 来源系统标识（V9：/webhook/{system} 路径注入，不可伪造；旧数据为 default） */
  system?: string | null
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
  /** 读路径派生（FR-3.1）：处于自愈观察窗内——活跃+未建单+观察级+未超窗 */
  observing?: boolean
  /** webhook 原始 labels 的 JSON 串（V11）：instance/pod/namespace/job 等下钻维度 */
  labelsJson?: string | null
  /** webhook 原始 annotations 的 JSON 串（V11）：runbook_url/当前值/阈值等 */
  annotationsJson?: string | null
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

// ==================== AI 模型渠道配置（P1：可编辑 + 重启生效）====================
// 对应后端 ModelChannelController.ChannelView（record，camelCase 序列化）。
// P1 起支持编辑（PUT /model-channels/{channelKey}），重启后端后生效。

export interface AiChannelView {
  /** 渠道键（chat/embedding/reranker） */
  channelKey: 'chat' | 'embedding' | 'reranker'
  /** 端点地址 */
  baseUrl: string | null
  /** 脱敏展示 key（如 sk-ws-****7890）。明文/密文 key 永不流出后端。 */
  maskedKey: string | null
  /** chat 渠道：turbo 模型 */
  turboModel: string | null
  /** chat 渠道：reasoner 模型 */
  reasonerModel: string | null
  /** embedding/reranker 渠道：模型名 */
  model: string | null
  /** 向量维度（仅 embedding 有值；铁律 1536，不可编辑） */
  dimension: number | null
  /** 状态（ACTIVE/DISABLED） */
  status: string | null
  /** 更新时间 */
  updatedAt: string | null
  /** true = 本次编辑已保存，需重启后端才对新请求生效（仅 PUT 响应携带） */
  restartRequired?: boolean
  /** 备用模型端点（仅 chat 渠道；方案 A 模型池降级） */
  fallbackBaseUrl?: string | null
  /** 备用模型名（主模型熔断/失败时自动切换） */
  fallbackModel?: string | null
  /** 备用 key 脱敏展示（明文/密文永不流出） */
  fallbackMaskedKey?: string | null
  /** 供应商名称（从 baseUrl 推断，如「阿里云 assistant（通义）」），人性化展示用 */
  provider?: string | null
  /** API 协议（如「OpenAI 兼容」），从 baseUrl 推断 */
  protocol?: string | null
}

export interface AiChannelListResponse {
  channels: AiChannelView[]
  /** 当前 AI 运行模式（MOCK=演示假数据 / REAL=真实调用大模型），前端据此给人性化提示 */
  aiMode?: string
}

/** AI 渠道配置模板（V13 多渠道一键切换）。模板不存 apiKey——应用时保留渠道现有密钥。 */
export interface ChannelTemplate {
  id: number
  channelKey: 'chat' | 'embedding' | 'reranker'
  templateName: string
  provider: string | null
  baseUrl: string
  protocol: string
  turboModel: string | null
  reasonerModel: string | null
  model: string | null
  dimension: number | null
  description: string | null
  sortOrder: number
}

/** PUT /model-channels/{channelKey} 请求体。空串/null = 不修改该字段。 */
export interface AiChannelUpdatePayload {
  baseUrl?: string
  /** API 协议（显式配置，不从 URL 推断）：OPENAI_COMPATIBLE/AZURE_OPENAI/ANTHROPIC/CUSTOM */
  protocol?: string
  /** 供应商名称（可编辑；留空 = 后端从 baseUrl 推断兜底） */
  provider?: string
  turboModel?: string
  reasonerModel?: string
  model?: string
  dimension?: number
  /** 新 API Key（可选；空 = 保留既有。后端加密落库，永不回显） */
  apiKey?: string
  status?: 'ACTIVE' | 'DISABLED'
  /** 备用模型端点（仅 chat 渠道；与 fallbackModel 必须同时提交） */
  fallbackBaseUrl?: string
  /** 备用模型名（仅 chat 渠道） */
  fallbackModel?: string
  /** 备用模型新 Key（可选；空 = 保留既有备用 key） */
  fallbackApiKey?: string
  /** true = 清除整组备用配置（含备用 key） */
  clearFallback?: boolean
}

/** POST /model-channels/{key}/test-connectivity 响应（P2-3 连通性测试） */
export interface ConnectivityResult {
  success: boolean
  latencyMs: number
  dimension: number | null
  message: string
}

// ==================== V5：变更历史 + 能力探测 ====================

/**
 * GET /model-channels/{key}/history 单条历史视图。
 * 与渠道视图同一安全契约：只给 maskedKey，密文不出 API。
 */
export interface AiChannelHistoryView {
  /** 历史行 id（回滚目标的引用） */
  id: number
  channelKey: string
  baseUrl: string | null
  maskedKey: string | null
  turboModel: string | null
  reasonerModel: string | null
  model: string | null
  dimension: number | null
  status: string | null
  fallbackBaseUrl: string | null
  fallbackModel: string | null
  fallbackMaskedKey: string | null
  /** 快照时间（变更发生时刻） */
  changedAt: string | null
  /** 操作人（Sa-Token loginId；系统触发=system） */
  changedBy: string
  /** 变更说明（编辑/回滚前快照#N/重置为 yml 默认值） */
  changeNote: string | null
}

export interface AiChannelHistoryListResponse {
  history: AiChannelHistoryView[]
}

/**
 * 能力探测三态（V5）。UNKNOWN = 探测本身失败（超时/限流），
 * 不代表不支持——前端绝不能把 UNKNOWN 画成红叉。
 */
export type CapabilityState = 'SUPPORTED' | 'UNSUPPORTED' | 'UNKNOWN'

export interface CapabilityItem {
  state: CapabilityState
  detail: string
}

/** 能力探测整轮结果（POST capability-probe / GET capabilities 共用） */
export interface ChannelProbeResult {
  channelKey: string
  capabilities: Record<string, CapabilityItem>
  probedAt: string | null
}

