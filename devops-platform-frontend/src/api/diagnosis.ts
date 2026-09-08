/**
 * 诊断回放 API（S2-3）
 *
 * 按 traceId 拉取完整诊断链（会话 + 证据列表 + 假设列表），
 * 供诊断详情页「点开假设看证据」一屏回放。
 * 与告警 WS 推送（DIAGNOSIS 事件）互补——WS 负责实时刷新信号，
 * 本模块负责权威的回放数据。
 */

import { http, unwrapBiz } from '../utils/http'
import { API_ENDPOINTS } from '../config/api'

export interface DiagnosisSessionView {
  trace_id?: string
  alert_id?: number
  ticket_id?: string
  service?: string
  status?: string
  sufficiency?: string
  summary?: string
}

export interface DiagnosisEvidenceView {
  id: number
  evidence_type: string
  status: 'SUCCESS' | 'NO_DATA' | 'FAILED' | 'UNAVAILABLE'
  title?: string
  source_ref?: string
}

export interface DiagnosisHypothesisView {
  id: number
  rank: number
  statement: string
  reasoning?: string
  confidence: number
  evidence_ids?: string | null
  contradict_ids?: string | null
  suggested_action?: string
  feedback?: string | null
}

interface DiagnosisReplayView {
  traceId: string
  session: DiagnosisSessionView
  evidences: DiagnosisEvidenceView[]
  hypotheses: DiagnosisHypothesisView[]
}

export type HypothesisFeedback = 'HELPFUL' | 'PARTIAL' | 'WRONG'

/**
 * 按 traceId 拉取整条诊断回放链。
 * <p>会话不存在时后端返回空对象（不抛 404）——前端按空态渲染。</p>
 */
export async function fetchDiagnosisReplay(traceId: string): Promise<DiagnosisReplayView> {
  const payload = await http.get<unknown>(`${API_ENDPOINTS.DIAGNOSIS}/${encodeURIComponent(traceId)}`)
  const data = unwrapBiz<DiagnosisReplayView>(payload, '拉取诊断回放失败')
  return {
    traceId: data.traceId,
    session: data.session ?? {},
    evidences: Array.isArray(data.evidences) ? data.evidences : [],
    hypotheses: Array.isArray(data.hypotheses) ? data.hypotheses : []
  }
}

/**
 * 标记假设质量（S2-3 反馈入口）；可选关联知识片段（chunk 侧 boost 臣录）的
 * 靶向回馈——前端能自注明引时的精准发布。
 */
export async function postHypothesisFeedback(
  hypothesisId: number,
  feedback: HypothesisFeedback,
  chunkIds?: number[]
): Promise<void> {
  await http.post<unknown>(API_ENDPOINTS.DIAGNOSIS_FEEDBACK, {
    hypothesisId,
    feedback,
    chunkIds: chunkIds ?? null
  })
}
