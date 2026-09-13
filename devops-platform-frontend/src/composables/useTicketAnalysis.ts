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
  fetchTicketAiAnalysisVersions,
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

  /**
   * 全部历史版本（方案 3 批 78：版本切换）
   *
   * AI 分析有两条写库路径（前端生成 / 告警诊断自动回填），同一工单天然
   * 可能多版本并存。只展示「最新一条」会把早期版本藏起来——工单处理了
   * 几小时后回看，中间那次诊断结论就找不到了。versions 记录 id/version/
   * createTime 轻量清单，切换时按 id 从清单回填内容。
   */
  const analysisVersions = ref<Array<{ id: number; version: number; createTime: string }>>([])
  /** 当前展示的版本号：null = 最新（未切历史） */
  const analysisViewVersion = ref<number | null>(null)

  /** 载入全部版本清单（在 loadArchivedAnalysis 命中后调用；≤1 版时不值得渲染切换器） */
  const loadAnalysisVersions = async () => {
    const id = ticketId()
    if (!id) return
    const epoch = analysisEpoch
    try {
      const versions = await fetchTicketAiAnalysisVersions(id)
      if (epoch !== analysisEpoch) return   // 用户已切工单，过期响应丢弃
      analysisVersions.value = versions.map(v => ({ id: v.id, version: v.version, createTime: v.createTime }))
    } catch (e) {
      if (epoch === analysisEpoch) {
        console.warn('[useTicketAnalysis] 加载版本清单失败（不影响展示最新版）', e)
        analysisVersions.value = []
      }
    }
  }

  /**
   * 切换到指定历史版本展示
   *
   * 从版本清单按 id 找记录回填 content 等展示字段；找不到（清单过期等）
   * 回退刷新清单再试一次。切回 null = 最新。
   */
  const switchAnalysisVersion = async (version: number | null) => {
    if (version === null) {
      analysisViewVersion.value = null
      await loadArchivedAnalysis()
      await loadAnalysisVersions()
      return
    }
    const target = analysisVersions.value.find(v => v.version === version)
    if (!target) return
    try {
      const versions = await fetchTicketAiAnalysisVersions(ticketId())
      const full = versions.find(v => v.version === version)
      if (!full?.content) return
      analysisContent.value = full.content
      analysisDone.value = true
      analysisStreaming.value = false
      analysisFromArchive.value = true
      analysisArchivedAt.value = full.createTime
      analysisId.value = full.id
      analysisFeedback.value = full.feedback ?? null
      analysisViewVersion.value = version
      citations.value = full.citations?.length ? full.citations : extractCitationsFromText(full.content)
      void resolveCitations(citations.value)
    } catch (e) {
      console.warn('[useTicketAnalysis] 切换历史版本失败', e)
    }
  }

  // Insights 数据
  const similarTickets = ref<FrontendTicket[]>([])
  const similarLoading = ref(false)
  const relatedDocs = ref<KnowledgeDocListItem[]>([])
  const relatedLoading = ref(false)

  /**
   * 引用文档的可跳转形态：对 citations 标题逐个查知识库，解析出 {id, title}。
   * <p>AnalysisCard 用它在「引用文档」区渲染 RouterLink（跳 /knowledge/{id}）。
   * 查不到（知识库无该文档 / 标题为自由文本）时该项为 null，前端回退纯文本标题。</p>
   */
  const citationDocs = ref<Array<{ id: string | number; title: string } | null>>([])

  /** 解析引用 → 可跳转文档（并发查询，任一失败仅该项降级为纯文本，不阻塞） */
  const resolveCitations = async (titles: string[]) => {
    const uniq = [...new Set(titles.map(t => t.trim()).filter(Boolean))]
    if (!uniq.length) { citationDocs.value = []; return }
    citationDocs.value = await Promise.all(uniq.map(async (title) => {
      try {
        const res = await fetchKnowledgeDocs({ keyword: title, size: 1, page: 1, status: 'PUBLISHED' })
        const hit = (res.content ?? []).find(d => d.title === title || title.includes(d.title) || d.title.includes(title))
        return hit ? { id: hit.id, title: hit.title } : null
      } catch { return null }
    }))
  }

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
    const epoch = analysisEpoch
    similarLoading.value = true
    try {
      const result = await fetchTickets({ service: svc, size: 5, page: 1 })
      if (epoch !== analysisEpoch) return   // 已切工单，A 的相似工单不覆盖 B
      similarTickets.value = result.tickets.filter(t => t.id !== ticketId()).slice(0, 3)
    } catch (e) {
      if (epoch === analysisEpoch) console.warn('[useTicketAnalysis] 加载相似工单失败', e)
    } finally {
      if (epoch === analysisEpoch) similarLoading.value = false
    }
  }

  const loadRelatedDocs = async () => {
    const keywords = [ticketService(), ticketTitle()].filter(Boolean)
    const keyword = keywords.join(' ')
    if (!keyword.trim()) return
    const epoch = analysisEpoch
    relatedLoading.value = true
    try {
      const result = await fetchKnowledgeDocs({ keyword: keyword.trim(), size: 3, page: 1, status: 'PUBLISHED' })
      if (epoch !== analysisEpoch) return   // 已切工单，A 的相关文档不覆盖 B
      relatedDocs.value = (result.content ?? []).slice(0, 3)
    } catch (e) {
      if (epoch === analysisEpoch) console.warn('[useTicketAnalysis] 加载相关文档失败', e)
    } finally {
      if (epoch === analysisEpoch) relatedLoading.value = false
    }
  }

  // ==================== 分析 ====================

  /**
   * 请求代数守卫（前端审计批一，P0 竞态）
   *
   * 切换工单时 resetAnalysis 自增 epoch；所有异步读（存档/版本清单/相似
   * 工单/相关文档）发起时捕获当前代数，响应回来若代数已变（用户已切走）
   * 则整批丢弃——否则工单 A 的慢响应会晚到覆盖 B 的数据：分析卡挂出 A
   * 的根因、analysisId 指向 A 的记录（B 页点「有用」反馈到 A）、相似工单
   * 列表也变成 A 的。与 tickets store 的搜索序号守卫同一模式。
   */
  let analysisEpoch = 0

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
    // 代数自增：使所有在途响应作废（见 analysisEpoch 注释）
    analysisEpoch++
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
    analysisVersions.value = []
    analysisViewVersion.value = null
    similarTickets.value = []
    relatedDocs.value = []
    replyDrafting.value = false
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
    const epoch = analysisEpoch
    try {
      const latest = await fetchLatestTicketAiAnalysis(id)
      // 已切工单：A 的存档晚到不得写进 B——analysisId 会指向 A 的记录，
      // 用户在 B 页点「有用/没用」将反馈到 A 的分析上（审计 P0-1）
      if (epoch !== analysisEpoch) return false
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
      void resolveCitations(citations.value)
      // 方案 3 批 78：命中存档同时拉版本清单——多版本时 AnalysisCard 渲染切换器
      await loadAnalysisVersions()
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
    analysisViewVersion.value = null
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
          void resolveCitations(citations.value)
          analysisDone.value = true
          analysisStreaming.value = false
          // 仅成功完成才存档：失败/中断的内容存下来会在下次被当作有效分析复用，
          // 用户会看到一段残缺的结论却不知它是残缺的
          void archiveAnalysis(analysisContent.value).then(() => loadAnalysisVersions())
        },
        onError: (data: SSEErrorEvent) => {
          analysisContent.value += `\n\n❌ ${data.message || '分析请求失败，请稍后重试'}`
          analysisDone.value = true
          analysisStreaming.value = false
        },

        /**
         * 服务端未发 complete 就关流时的兜底。
         *
         * fetchEventSource 在流关闭时**正常 resolve**——不抛错、不进 catch、
         * 不触发 onError。只在 complete/error 里复位 analysisStreaming 的话，
         * 后端超时切断 / 网关 502 / Nginx proxy_read_timeout 到期这几种情况下
         * 它会永远停在 true：「停止生成」按钮一直显示、「重新分析」点不动，
         * 用户只能刷新整个工单详情页。
         *
         * 与 ChatMode 是同一个缺陷，上一轮只修了那一处——这里是同类漏网。
         */
        onClose: () => {
          if (!analysisStreaming.value) return   // 已由 complete/error 正常收尾
          analysisContent.value += analysisContent.value
            ? '\n\n_（连接已中断，以上为已生成内容）_'
            : '❌ 连接意外中断，未收到分析结果，请重试'
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

  /**
   * 回复草稿独立状态（方案 B，批 79）
   *
   * 此前 generateReply 直接复用 analysisContent/analysisStreaming——生成回复
   * 会把时间线上「AI 分析建议」卡片的内容逐字替换成回复草稿，且 analysisId
   * 不清（反馈按钮仍指向旧分析）、存档分析被顶掉、切工单时 resetAnalysis 又
   * 只清分析不清草稿。根因是两个语义不同的流共用了同一组状态变量。
   *
   * 回复草稿的消费方是回复框（handleGenerateReply 填入 replyContent），
   * 不需要进分析卡；流式过程用 replyDrafting 让回复按钮转圈即可。
   */
  const replyDrafting = ref(false)

  const generateReply = async (): Promise<string | null> => {
    if (replyDrafting.value || analysisStreaming.value) {
      notify.warning('AI 正在生成中，请稍候')
      return null
    }

    const replyQuery = `请根据工单上下文生成回复草稿（不要直接发送，仅生成草稿供用户审核）：\n${ticketContext()}\n\n请用中文回复，包含：\n1. 问题分析摘要\n2. 建议的排查步骤\n3. 相关的知识库参考`

    replyDrafting.value = true
    abortController = new AbortController()

    let replyText = ''
    try {
      await chatStream(replyQuery, {
        onStart: () => {},
        onToolStatus: () => {},
        onToken: (data: SSETokenEvent) => {
          replyText += data.text
        },
        onComplete: () => {
          replyDrafting.value = false
        },
        onError: (data: SSEErrorEvent) => {
          replyText += `\n\n❌ ${data.message || '请求失败'}`
          replyDrafting.value = false
        },

        // 同 runAnalysis：流关闭但没收到 complete 时兜底收尾，
        // 否则 replyDrafting 永远为 true，回复按钮一直转圈
        onClose: () => {
          if (!replyDrafting.value) return
          if (replyText) {
            replyText += '\n\n_（连接已中断，以上为已生成内容）_'
          } else {
            replyText = '❌ 连接意外中断，未收到回复草稿，请重试'
          }
          replyDrafting.value = false
        }
      }, abortController)
      return replyText
    } catch (error: unknown) {
      if (isAbortLike(error) || abortController?.signal.aborted) {
        replyText += '\n\n_（已停止生成）_'
      } else {
        replyText += `\n\n❌ 连接失败：${errorMessage(error)}`
      }
      replyDrafting.value = false
      return replyText
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
    // 只 abort 不够：abort 走的是 catch 分支，而组件已卸载、
    // 那段 catch 未必来得及执行。显式收尾保证状态不会残留为「分析中」
    if (analysisStreaming.value) {
      analysisStreaming.value = false
      analysisDone.value = true
    }
  })

  return {
    // 分析状态
    analysisContent,
    analysisStreaming,
    analysisDone,
    citations,
    citationDocs,
    analysisCost,
    // 存档来源标识（供卡片标注「上次分析结果」及时间）
    analysisFromArchive,
    analysisArchivedAt,
    // 版本切换（方案 3：多版本并存时的历史回看）
    analysisVersions,
    analysisViewVersion,
    switchAnalysisVersion,
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
    // 回复草稿生成中状态（方案 B：与分析状态分离，回复按钮转圈用）
    replyDrafting,
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
