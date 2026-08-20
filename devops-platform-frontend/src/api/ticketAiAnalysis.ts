/**
 * 工单 AI 分析 API（策略 B）
 *
 * 从策略 A（复用 sys_ticket_reply role=ai，纯文本）演进到独立表：
 * 保留结构化字段（reasons/commands/citations/confidence/cost）、多版本、用户反馈。
 *
 * 结构化字段由前端解析后传入——前端已有 parseStructuredAnalysis / extractCitationsFromText，
 * content 是真相源，后端不重复实现解析器。
 */

import { API_ENDPOINTS } from '../config/api'
import { http, unwrapBiz } from '../utils/http'

/** 后端 sys_ticket_ai_analysis 记录 */
export interface TicketAiAnalysis {
  id: number
  ticketId: string
  version: number
  content: string
  reasons: string[]
  commands: string[]
  citations: string[]
  confidence: number | null
  costRmb: number
  /** null=未评价 / HELPFUL / UNHELPFUL */
  feedback: string | null
  feedbackAt: string | null
  createTime: string
}

/** 保存分析的入参（结构化字段由前端解析） */
export interface SaveAnalysisPayload {
  content: string
  reasons: string[]
  commands: string[]
  citations: string[]
  confidence: number | null
  costRmb: number
}

/**
 * 保存一次 AI 分析（version 由后端自增）
 */
export async function saveTicketAiAnalysis(
  ticketId: string,
  payload: SaveAnalysisPayload
): Promise<TicketAiAnalysis> {
  const raw = await http.post<unknown>(API_ENDPOINTS.TICKET_AI_ANALYSIS(ticketId), payload)
  return unwrapBiz<TicketAiAnalysis>(raw, 'AI 分析保存失败')
}

/**
 * 查询工单最新 AI 分析（当前结论）
 *
 * @returns 分析记录；null 表示该工单尚无分析
 */
export async function fetchLatestTicketAiAnalysis(ticketId: string): Promise<TicketAiAnalysis | null> {
  const raw = await http.get<unknown>(API_ENDPOINTS.TICKET_AI_ANALYSIS_LATEST(ticketId))
  return unwrapBiz<TicketAiAnalysis | null>(raw, '查询 AI 分析失败')
}

/**
 * 查询工单全部 AI 分析版本（version 倒序，供历史对比）
 */
export async function fetchTicketAiAnalysisVersions(ticketId: string): Promise<TicketAiAnalysis[]> {
  const raw = await http.get<unknown>(API_ENDPOINTS.TICKET_AI_ANALYSIS_VERSIONS(ticketId))
  const data = unwrapBiz<TicketAiAnalysis[]>(raw, '查询 AI 分析版本失败')
  return Array.isArray(data) ? data : []
}

/**
 * 记录 AI 分析反馈（有用 / 没用）——AI 准确率统计数据来源
 */
export async function submitAiAnalysisFeedback(analysisId: number, helpful: boolean): Promise<void> {
  const raw = await http.post<unknown>(API_ENDPOINTS.TICKET_AI_ANALYSIS_FEEDBACK(analysisId), { helpful })
  unwrapBiz<unknown>(raw, '反馈提交失败')
}

/** AI 分析准确率统计 */
export interface AiAnalysisStats {
  total: number
  rated: number
  helpful: number
  unhelpful: number
  helpfulRate: number
}

/**
 * AI 分析准确率统计（供数据概览展示）
 */
export async function fetchAiAnalysisStats(): Promise<AiAnalysisStats> {
  const raw = await http.get<unknown>(API_ENDPOINTS.TICKET_AI_ANALYSIS_STATS)
  return unwrapBiz<AiAnalysisStats>(raw, '查询 AI 分析统计失败')
}
