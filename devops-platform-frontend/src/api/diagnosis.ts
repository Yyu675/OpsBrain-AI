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
  /**
   * 证据载荷原文（后端 Evidence.toToolPayload() 的 JSON 串）。
   * 知识证据的 content.hits[].chunkId 是诊断反馈回流的精确入口——
   * citation 字符串反查会因标题含分隔符、章节改版而漂移，id 不会。
   */
  content?: string | null
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
  /**
   * 后端 AI 模式（MOCK/REAL）。MOCK 下语义检索是哈希假向量，
   * 知识证据恒 NO_DATA——页面必须如实告知，否则「未启用」会被误读成「库里没有」。
   */
  aiMode?: string
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
    hypotheses: Array.isArray(data.hypotheses) ? data.hypotheses : [],
    aiMode: data.aiMode
  }
}

/**
 * 标记假设质量（S2-3 反馈入口），并始终带回本次诊断引用的知识切片 id。
 *
 * chunkIds 不再是「能自注明时才传」：调用方从本次回放的知识证据里提取
 * （{@link citedChunkIds}），取不到传空数组而不是省略——后端以
 * 「字段缺失」为信号才走 citation 反查兜底，传了就以本次真实引用为准，
 * 不再依赖字符串反查的运气。
 */
export async function postHypothesisFeedback(
  hypothesisId: number,
  feedback: HypothesisFeedback,
  chunkIds: number[]
): Promise<void> {
  await http.post<unknown>(API_ENDPOINTS.DIAGNOSIS_FEEDBACK, {
    hypothesisId,
    feedback,
    chunkIds
  })
}

/**
 * 从本次诊断回放的证据里提取被引用的知识切片 id。
 *
 * 只认 knowledge 类型证据载荷里的 content.hits[].chunkId——那是检索时
 * 随行落库的切片主键。载荷缺失、不是合法 JSON、或是本次诊断之前落的
 * 旧证据（没有 chunkId 字段）都安全跳过，返回空数组，由后端兜底反查。
 */
export function citedChunkIds(evidences: DiagnosisEvidenceView[]): number[] {
  const ids = new Set<number>()
  for (const ev of evidences) {
    if (ev.evidence_type !== 'knowledge' || !ev.content) continue
    let payload: unknown
    try {
      payload = JSON.parse(ev.content)
    } catch {
      continue
    }
    const hits = (payload as { content?: { hits?: unknown } } | null)?.content?.hits
    if (!Array.isArray(hits)) continue
    for (const hit of hits) {
      const id = (hit as { chunkId?: unknown } | null)?.chunkId
      if (typeof id === 'number' && Number.isFinite(id)) ids.add(id)
    }
  }
  return [...ids]
}
