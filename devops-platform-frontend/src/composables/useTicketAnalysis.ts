/**
 * 工单 AI 分析共享逻辑
 *
 * 从 AIContextPanel 抽出，供 TicketDetail 的 AnalysisCard（时间线节点）和
 * 回复框的「AI 生成回复」按钮共用。
 *
 * 职责：
 * - runAnalysis：结构化分析（原因/命令/置信度）
 * - generateReply：生成回复草稿（填入回复框）
 * - parseStructuredAnalysis：纯函数，解析流式 markdown 为结构化结果
 * - 相似工单 / 相关文档加载
 */

import { notify } from '@/utils/notify'
import { ref, computed, onBeforeUnmount } from 'vue'

import { chatStream } from '@/api/chat'
import { fetchTickets } from '@/api/tickets'
// 策略 B：AI 分析存独立表（结构化 + 多版本 + 反馈），替换策略 A 的 role='ai' 回复
import {
  saveTicketAiAnalysis,
  fetchLatestTicketAiAnalysis,
  submitAiAnalysisFeedback
} from '@/api/ticketAiAnalysis'
import { fetchKnowledgeDocs } from '@/api/knowledge'
import { copyText } from '@/utils/clipboard'
import { safeMarkdown } from '@/utils/safeMarkdown'
import { errorMessage, isAbortLike } from '@/utils/errors'
import type { FrontendTicket } from '@/api/types/ticket'
import type { KnowledgeDocListItem } from '@/api/types'
import type {
  SSEStartEvent, SSEToolStatusEvent, SSETokenEvent, SSECompleteEvent, SSEErrorEvent
} from '@/api/types'

// ==================== 类型 ====================

export interface StructuredAnalysis {
  reasons: string[]
  commands: string[]
  confidence: number | null
  confidenceText: string
  structured: boolean
  other: string
}

// ==================== 纯函数：解析 ====================

/**
 * 从回答文本回退提取引用出处。
 *
 * 语义缓存命中时工具不执行，后端 complete 事件的 citations 为空（6.20），
 * 此时只能从正文的【来源：文档标题 - 章节】标记回退提取，
 * 否则缓存命中的回答会丢失全部溯源信息。
 *
 * 导出供测试：溯源是 L1 幻觉防护的一环，格式解析出错等于静默丢证据。
 */
export const extractCitationsFromText = (text: string): string[] => {
  const matches = text.matchAll(/【来源：([^-】]+?)\s*-\s*([^】]+?)】/g)
  const result: string[] = []
  for (const m of matches) {
    const title = m[1].trim()
    const section = m[2].trim()
    if (title) {
      const label = section ? `${title} - ${section}` : title
      if (!result.includes(label)) result.push(label)
    }
  }
  return result
}

const extractCodeBlocks = (text: string, lang = 'bash'): { content: string; rest: string }[] => {
  const blocks: { content: string; rest: string }[] = []
  const fence = new RegExp('```' + lang + '\\s*\\n([\\s\\S]*?)(?:```|$)', 'g')
  let m: RegExpExecArray | null
  while ((m = fence.exec(text)) !== null) {
    const inner = m[1].replace(/\s+$/, '')
    blocks.push({ content: inner, rest: '' })
  }
  return blocks
}

export function parseStructuredAnalysis(raw: string): StructuredAnalysis {
  const result: StructuredAnalysis = {
    reasons: [], commands: [], confidence: null,
    confidenceText: '', structured: false, other: ''
  }
  if (!raw || !raw.trim()) return result

  const sections = raw.split(/(?=^##\s+)/m)
  const otherBuf: string[] = []

  for (const section of sections) {
    const trimmed = section.trim()
    if (!trimmed) continue
    const headerMatch = trimmed.match(/^##\s+(.*)$/m)
    const header = (headerMatch?.[1] ?? '').trim().toLowerCase()
    const body = headerMatch
      ? trimmed.slice(headerMatch.index! + headerMatch[0].length).trim()
      : trimmed

    if (header.includes('可能原因') || header.includes('原因') || header.includes('可能')) {
      const lines = body.split('\n')
      for (const line of lines) {
        const m = line.match(/^\s*(?:\d+[.、)）]?\s*|[-*]\s*)(.+)$/)
        if (m && m[1].trim()) result.reasons.push(m[1].trim())
      }
      if (result.reasons.length === 0 && body) {
        result.reasons.push(body.split('\n')[0].trim())
      }
    } else if (header.includes('排查命令') || header.includes('命令') || header.includes('排查') || header.includes('处理步骤')) {
      const blocks = extractCodeBlocks(body, 'bash')
      if (blocks.length) {
        for (const b of blocks) {
          const cmds = b.content.split('\n').map(s => s.trim()).filter(Boolean)
          result.commands.push(...cmds)
        }
      } else {
        const lines = body.split('\n')
        for (const line of lines) {
          const cm = line.match(/^\s*(?:\d+[.、)）]?\s*|[-*]\s*)?(.+)$/)
          if (cm && cm[1].trim()) {
            const cmd = cm[1].trim().replace(/^`+|`+$/g, '')
            if (cmd) result.commands.push(cmd)
          }
        }
      }
    } else if (header.includes('置信度') || header.includes('置信') || header.includes('可信度')) {
      const cm = body.match(/(\d{1,3})\s*%?/)
      if (cm) {
        const v = parseInt(cm[1], 10)
        if (v >= 0 && v <= 100) {
          result.confidence = v
          result.confidenceText = body.split('\n')[0].trim()
        }
      }
      if (result.confidence === null) {
        result.confidenceText = body.split('\n')[0].trim()
      }
    } else {
      otherBuf.push(trimmed)
    }
  }

  const hasAnyHeader = /(^|\n)##\s+/.test(raw)
  if (!hasAnyHeader) {
    result.other = raw.trim()
    result.structured = false
    return result
  }

  result.other = otherBuf.join('\n\n').trim()
  result.structured = result.reasons.length > 0 || result.commands.length > 0
  return result
}

// ==================== 格式指令 ====================

const ANALYSIS_FORMAT_INSTRUCTION = `请按以下固定 Markdown 骨架输出分析（严格使用二级标题，不要加多余段落）：

## 可能原因
1. （按可能性从高到低列出 2-4 条，每条一句话说明）
2. ...

## 排查命令
\`\`\`bash
（每行一条可直接执行的排查命令，命令须真实可运行，不要编造）
\`\`\`

## 置信度
（0-100 的整数百分比，如 85%）

下面是工单上下文，请基于此分析：`

// ==================== Composable ====================

export function useTicketAnalysis(
  ticketId: () => string,
  ticketContext: () => string,
  ticketService: () => string,
  ticketTitle: () => string
) {
  // 分析状态
  const analysisContent = ref('')
  const analysisStreaming = ref(false)
  const analysisDone = ref(false)
  const citations = ref<string[]>([])
  const analysisCost = ref(0)

  /**
   * 当前展示的分析是否来自历史存档（而非本次实时生成）
   * <p>用于在卡片上标注来源与时间，让用户知道这是上次的分析结果。</p>
   */
  const analysisFromArchive = ref(false)
  const analysisArchivedAt = ref<string>('')

  /**
   * 当前分析在库中的 id（策略 B）
   * <p>用户反馈（有用/没用）需要它定位记录。实时生成后由 save 回填，
   * 载入存档后直接取。为 null 时反馈按钮不可用。</p>
   */
  const analysisId = ref<number | null>(null)

  /** 当前分析的用户反馈：null=未评价 / 'HELPFUL' / 'UNHELPFUL' */
  const analysisFeedback = ref<string | null>(null)

  // Insights 数据
  const similarTickets = ref<FrontendTicket[]>([])
  const similarLoading = ref(false)
  const relatedDocs = ref<KnowledgeDocListItem[]>([])
  const relatedLoading = ref(false)

  let abortController: AbortController | null = null

  const structured = computed(() => parseStructuredAnalysis(analysisContent.value))

  const confidenceClass = computed(() => {
    const v = structured.value.confidence
    if (v === null) return ''
    if (v >= 80) return 'confidence-high'
    if (v >= 50) return 'confidence-mid'
    return 'confidence-low'
  })

  const useStructuredRender = computed(() => structured.value.structured)

  // ==================== 加载 Insights ====================

  const loadSimilarTickets = async () => {
    const svc = ticketService()
    if (!svc) return
    similarLoading.value = true
    try {
      const result = await fetchTickets({ service: svc, size: 5, page: 1 })
      similarTickets.value = result.tickets.filter(t => t.id !== ticketId()).slice(0, 3)
    } catch (e) {
      console.warn('[useTicketAnalysis] 加载相似工单失败', e)
    } finally {
      similarLoading.value = false
    }
  }

  const loadRelatedDocs = async () => {
    const keywords = [ticketService(), ticketTitle()].filter(Boolean)
    const keyword = keywords.join(' ')
    if (!keyword.trim()) return
    relatedLoading.value = true
    try {
      const result = await fetchKnowledgeDocs({ keyword: keyword.trim(), size: 3, page: 1, status: 'PUBLISHED' })
      relatedDocs.value = (result.content ?? []).slice(0, 3)
    } catch (e) {
      console.warn('[useTicketAnalysis] 加载相关文档失败', e)
    } finally {
      relatedLoading.value = false
    }
  }

  // ==================== 分析 ====================

  /**
   * 重置分析状态
   * <p>
   * 切换工单时必须调用。此前 TicketDetail 的 watch(ticketId) 只重载详情，
   * 不清空分析——从工单 A 点「相似工单」跳到工单 B（同路由 /tickets/:id，
   * Vue 复用组件实例，onMounted 不再触发），B 的时间线会<b>继续挂着 A 的
   * AI 分析</b>，把 A 的根因当作 B 的呈现给用户。
   * </p>
   */
  const resetAnalysis = () => {
    // 有正在进行的流式请求先中断，否则旧工单的 token 会继续写进新工单的内容
    if (abortController) {
      abortController.abort()
      abortController = null
    }
    analysisContent.value = ''
    citations.value = []
    analysisDone.value = false
    analysisStreaming.value = false
    analysisCost.value = 0
    analysisFromArchive.value = false
    analysisArchivedAt.value = ''
    analysisId.value = null
    analysisFeedback.value = null
    similarTickets.value = []
    relatedDocs.value = []
  }

  /**
   * 把分析结果存档到独立表（策略 B）
   * <p>
   * 结构化字段（reasons/commands/citations/confidence）由前端解析后一并存入，
   * 保留完整结构与成本；version 由后端自增。回填 analysisId 供反馈定位。
   * </p>
   * <p>
   * 失败不抛出：存档是增强而非主流程，失败仅告警不打断用户已看到的分析。
   * </p>
   */
  const archiveAnalysis = async (text: string) => {
    const body = text.trim()
    if (!body) return
    const id = ticketId()
    if (!id) return

    const s = parseStructuredAnalysis(body)
    try {
      const saved = await saveTicketAiAnalysis(id, {
        content: body,
        reasons: s.reasons,
        commands: s.commands,
        citations: citations.value,
        confidence: s.confidence,
        costRmb: analysisCost.value
      })
      // 回填 id：本次分析可立即被用户评价「有用/没用」
      analysisId.value = saved.id
      analysisFeedback.value = saved.feedback ?? null
    } catch (e) {
      // 网络异常等：分析本身已展示给用户，存档失败只影响下次复用与反馈
      console.warn('[useTicketAnalysis] AI 分析存档失败（不影响本次展示）', e)
    }
  }

  /**
   * 尝试载入已存档的分析（策略 B：独立表）
   * <p>
   * 命中则直接展示，<b>不调用付费 LLM</b>。结构化字段从库直接读取，
   * 无需二次解析。此前 onMounted 无条件 runAnalysis()，每次打开/刷新工单
   * 详情都调一次 DeepSeek——10 人各看同一张单 10 次即 100 次付费调用产出
   * 同一份内容，且结果纯内存、关页即失，与项目「知识沉淀」目标相悖。
   * </p>
   *
   * @returns true=已载入存档（调用方不应再触发分析）
   */
  const loadArchivedAnalysis = async (): Promise<boolean> => {
    const id = ticketId()
    if (!id) return false
    try {
      const latest = await fetchLatestTicketAiAnalysis(id)
      if (!latest || !latest.content?.trim()) return false

      analysisContent.value = latest.content
      analysisDone.value = true
      analysisStreaming.value = false
      analysisFromArchive.value = true
      analysisArchivedAt.value = latest.createTime ?? ''
      analysisId.value = latest.id
      analysisFeedback.value = latest.feedback ?? null
      analysisCost.value = latest.costRmb ?? 0
      // 引用直接取库中结构化字段；为空再从正文【来源：X - Y】标记兜底还原
      if (latest.citations?.length) {
        citations.value = latest.citations
      } else {
        const fromText = extractCitationsFromText(latest.content)
        if (fromText.length) citations.value = fromText
      }
      return true
    } catch (e) {
      console.warn('[useTicketAnalysis] 读取分析存档失败，将走实时分析', e)
      return false
    }
  }

  /**
   * 提交用户反馈（有用 / 没用）——AI 准确率统计数据来源
   * <p>需先有 analysisId（实时生成后回填或载入存档后取得），否则忽略。</p>
   */
  const submitFeedback = async (helpful: boolean) => {
    const aid = analysisId.value
    if (aid == null) {
      notify.warning('分析尚未存档，暂时无法评价')
      return
    }
    // 乐观更新：先反映到 UI，失败回滚
    const prev = analysisFeedback.value
    analysisFeedback.value = helpful ? 'HELPFUL' : 'UNHELPFUL'
    try {
      await submitAiAnalysisFeedback(aid, helpful)
      notify.success(helpful ? '感谢反馈，已记录「有用」' : '已记录「没用」，我们会持续改进')
    } catch (e) {
      analysisFeedback.value = prev
      console.error('[useTicketAnalysis] 反馈提交失败', e)
      notify.error('反馈提交失败，请稍后重试')
    }
  }

  const runAnalysis = async () => {
    if (analysisStreaming.value) return
    analysisContent.value = ''
    citations.value = []
    analysisDone.value = false
    analysisCost.value = 0
    // 本次是实时生成，不再是存档；清空上一版分析的 id 与反馈
    analysisFromArchive.value = false
    analysisArchivedAt.value = ''
    analysisId.value = null
    analysisFeedback.value = null
    analysisStreaming.value = true
    abortController = new AbortController()

    const query = `${ANALYSIS_FORMAT_INSTRUCTION}\n\n${ticketContext()}`

    try {
      await chatStream(query, {
        onStart: (_data: SSEStartEvent) => { /* 可扩展 */ },
        onToolStatus: (data: SSEToolStatusEvent) => { console.debug('[useTicketAnalysis] tool:', data) },
        onToken: (data: SSETokenEvent) => { analysisContent.value += data.text },
        onComplete: (data: SSECompleteEvent) => {
          analysisCost.value = data.costRmb ?? 0
          if (data.citations?.length) {
            citations.value = data.citations
          } else {
            const fromText = extractCitationsFromText(analysisContent.value)
            if (fromText.length) citations.value = fromText
          }
          analysisDone.value = true
          analysisStreaming.value = false
          // 仅成功完成才存档：失败/中断的内容存下来会在下次被当作有效分析复用，
          // 用户会看到一段残缺的结论却不知它是残缺的
          void archiveAnalysis(analysisContent.value)
        },
        onError: (data: SSEErrorEvent) => {
          analysisContent.value += `\n\n❌ ${data.message || '分析请求失败，请稍后重试'}`
          analysisDone.value = true
          analysisStreaming.value = false
        }
      }, abortController)
    } catch (error: unknown) {
      if (isAbortLike(error) || abortController?.signal.aborted) {
        analysisContent.value += '\n\n_（已停止生成）_'
      } else {
        analysisContent.value += `\n\n❌ 连接失败：${errorMessage(error)}`
      }
      analysisDone.value = true
      analysisStreaming.value = false
    } finally {
      abortController = null
    }
  }

  const stopAnalysis = () => {
    if (!analysisStreaming.value || !abortController) return
    abortController.abort()
  }

  const regenerateAnalysis = () => runAnalysis()

  // ==================== 生成回复草稿 ====================

  const generateReply = async (): Promise<string | null> => {
    if (analysisStreaming.value) {
      notify.warning('AI 正在分析中，请稍候')
      return null
    }

    const replyQuery = `请根据工单上下文生成回复草稿（不要直接发送，仅生成草稿供用户审核）：\n${ticketContext()}\n\n请用中文回复，包含：\n1. 问题分析摘要\n2. 建议的排查步骤\n3. 相关的知识库参考`

    analysisContent.value = ''
    citations.value = []
    analysisDone.value = false
    analysisStreaming.value = true
    abortController = new AbortController()

    try {
      let replyText = ''
      await chatStream(replyQuery, {
        onStart: () => {},
        onToolStatus: () => {},
        onToken: (data: SSETokenEvent) => {
          replyText += data.text
          analysisContent.value = replyText
        },
        onComplete: (data: SSECompleteEvent) => {
          if (data.citations?.length) citations.value = data.citations
          analysisDone.value = true
          analysisStreaming.value = false
        },
        onError: (data: SSEErrorEvent) => {
          replyText += `\n\n❌ ${data.message || '请求失败'}`
          analysisContent.value = replyText
          analysisDone.value = true
          analysisStreaming.value = false
        }
      }, abortController)
      return replyText
    } catch (error: unknown) {
      if (isAbortLike(error) || abortController?.signal.aborted) {
        analysisContent.value += '\n\n_（已停止生成）_'
      } else {
        analysisContent.value += `\n\n❌ 连接失败：${errorMessage(error)}`
      }
      analysisDone.value = true
      analysisStreaming.value = false
      return null
    } finally {
      abortController = null
    }
  }

  // ==================== 工具方法 ====================

  const renderMarkdown = (text: string): string => safeMarkdown(text)

  const copyCommand = async (cmd: string) => {
    const ok = await copyText(cmd)
    if (ok) notify.success('命令已复制')
    else notify.warning('复制失败，请手动选择')
  }

  const copyAnalysis = async () => {
    const ok = await copyText(analysisContent.value)
    if (ok) notify.success('已复制到剪贴板')
    else notify.warning('复制失败，请手动选择文本')
  }

  // ==================== 生命周期 ====================

  onBeforeUnmount(() => {
    if (abortController) {
      abortController.abort()
      abortController = null
    }
  })

  return {
    // 分析状态
    analysisContent,
    analysisStreaming,
    analysisDone,
    citations,
    analysisCost,
    // 存档来源标识（供卡片标注「上次分析结果」及时间）
    analysisFromArchive,
    analysisArchivedAt,
    // 反馈（策略 B：AI 准确率数据来源）
    analysisId,
    analysisFeedback,
    submitFeedback,
    // 结构化
    structured,
    confidenceClass,
    useStructuredRender,
    // Insights
    similarTickets,
    similarLoading,
    relatedDocs,
    relatedLoading,
    // 方法
    runAnalysis,
    stopAnalysis,
    regenerateAnalysis,
    generateReply,
    loadSimilarTickets,
    loadRelatedDocs,
    // 存档读写与状态重置（切换工单必须调 resetAnalysis）
    loadArchivedAnalysis,
    resetAnalysis,
    renderMarkdown,
    copyCommand,
    copyAnalysis,
  }
}
