/**
 * API 配置 - 统一管理接口地址
 * 对应后端配置: server.port=8088, context-path=/ai
 */

// 开发环境 Base URL
export const BASE_URL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8088/ai'

// API 路径前缀
export const API_PREFIX = '/api/v1'

// 完整 API 地址
export const API_BASE = `${BASE_URL}${API_PREFIX}`

// 接口端点
export const API_ENDPOINTS = {
  // M1 对话接入
  CHAT_STREAM: `${API_BASE}/chat/stream`,
  // 鉴权（方向三：Sa-Token）
  AUTH_LOGIN: `${API_BASE}/auth/login`,
  AUTH_ME: `${API_BASE}/auth/me`,
  AUTH_LOGOUT: `${API_BASE}/auth/logout`,

  // M8 看板统计
  DASHBOARD_OVERVIEW: `${API_BASE}/dashboard/overview`,
  DASHBOARD_TRENDS: `${API_BASE}/dashboard/trends`,

  // M5 知识库
  // TODO(P2-待接入): KNOWLEDGE_INGEST / KNOWLEDGE_CHUNKS 端点因后端 /ingest 已废弃(410, 见 6.25 P1-9)、
  // 切片级浏览被文档级 CRUD 取代(6.21)而移除。若未来需要切片级浏览, 应挂到
  // KnowledgeManageController 的保留端点(如 /chunks)而非已废弃的 /ingest。
  // RAG 知识文档 CRUD（6.21 生命周期治理）
  KNOWLEDGE_DOCS: `${API_BASE}/knowledge/docs`,
  // 6.22：扁平分类聚合 / 热门标签（全库跨页）
  KNOWLEDGE_DOC_CATEGORIES: `${API_BASE}/knowledge/docs/categories`,
  KNOWLEDGE_DOC_TAGS_HOT: `${API_BASE}/knowledge/docs/tags/hot`,
  KNOWLEDGE_CATEGORIES: `${API_BASE}/knowledge/categories`,
  KNOWLEDGE_BASE: `${API_BASE}/knowledge`,

  // M7 工单
  TICKETS: `${API_BASE}/tickets`,
  TICKETS_BY_ID: (id: string) => `${API_BASE}/tickets/${id}`,
  TICKETS_BY_TRACE: (traceId: string) => `${API_BASE}/tickets/by-trace/${traceId}`,

  // 团队成员名录（工单负责人来源）
  // A2：此前前端硬编码 ASSIGNEE_OPTIONS 编造七人名单，工单会被指派给不存在的人
  USERS: `${API_BASE}/users`,

  // L2 告警（Stage 3：告警列表 + 人工确认/标记恢复）
  ALERTS: `${API_BASE}/alerts`,
  ALERTS_BY_ID: (id: number) => `${API_BASE}/alerts/${id}`,

  // AI 分析持久化（策略 B：结构化 + 多版本 + 反馈）
  TICKET_AI_ANALYSIS: (id: string) => `${API_BASE}/tickets/${id}/ai-analysis`,
  TICKET_AI_ANALYSIS_LATEST: (id: string) => `${API_BASE}/tickets/${id}/ai-analysis/latest`,
  TICKET_AI_ANALYSIS_VERSIONS: (id: string) => `${API_BASE}/tickets/${id}/ai-analysis/versions`,
  TICKET_AI_ANALYSIS_FEEDBACK: (analysisId: number) => `${API_BASE}/tickets/ai-analysis/${analysisId}/feedback`,
  TICKET_AI_ANALYSIS_STATS: `${API_BASE}/tickets/ai-analysis/stats`,

  // 健康检查
  HEALTH: `${API_BASE}/health`,
} as const

// 超时配置（统一来源，http.ts 引用此值）
export const TIMEOUT = {
  DEFAULT: 15000,      // 普通接口 15s
  SSE: 0,              // SSE 流式无超时
} as const
