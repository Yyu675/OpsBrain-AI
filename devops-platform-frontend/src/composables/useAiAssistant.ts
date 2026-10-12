/**
 * AI 助手页的会话状态与 SSE 编排（R0-2 方案 A）。
 *
 * 把 chatStream 的五事件契约（start/tool_status/token/complete/error）
 * 折叠成消息列表状态机，视图只做渲染。会话 id 经 persist 持久化，
 * 刷新后继续同一会话——后端三层记忆按 sessionId 关联多轮上下文。
 */
import { ref } from 'vue'
import { chatStream } from '@/api/chat'
import { loadPersisted, savePersisted } from '@/utils/persist'
import type { SSECompleteEvent } from '@/api/types'

const SESSION_KEY = 'ai-a…sion'
const STORE_VERSION = 1

// 类型不导出：视图经 useAiAssistant() 返回值推断消费（knip 零导出纪律）
interface AssistantMessage {
  id: number
  role: 'user' | 'assistant'
  text: string
  /** 生成中的回答也保留 tool_status 文案（检索/建单进度提示） */
  toolStatus?: string
  /** complete 事件回传的引用出处（字符串列表，见 SSECompleteEvent.citations） */
  citations?: string[]
  /** complete 回传的成本（元）；生成中为 null */
  costRmb: number | null
  error?: string
}

let nextId = 1

/**
 * 会话存储缺失时的兜底 id：与后端约定 sessionId ≤64 字符。
 * 随机段用 crypto.randomUUID（浏览器 API 边界，测试可 mock）。
 */
const freshSessionId = (): string => `web-${crypto.randomUUID()}`

export function useAiAssistant() {
  const messages = ref<AssistantMessage[]>([])
  const streaming = ref(false)
  const sessionId = ref(
    loadPersisted<string>(SESSION_KEY, STORE_VERSION) || freshSessionId()
  )

  let controller: AbortController | null = null

  const persistSession = () => savePersisted(SESSION_KEY, sessionId.value, STORE_VERSION)
  persistSession()

  /** 提问并把回答流式写入列表。空输入/超长输入由 chatStream 前置校验抛错 */
  const ask = async (query: string) => {
    const text = query.trim()
    if (!text || streaming.value) return
    messages.value.push({ id: nextId++, role: 'user', text, costRmb: null })
    const reply: AssistantMessage = { id: nextId++, role: 'assistant', text: '', costRmb: null }
    messages.value.push(reply)
    // 取回数组内的响应式代理，直接改字段才会触发渲染
    const live = messages.value[messages.value.length - 1]!

    streaming.value = true
    controller = new AbortController()
    try {
      await chatStream(
        text,
        {
          // tool_status 的 status 是枚举词（start/success/error），
          // 给人看的是 message（如「正在检索知识库…」）
          onToolStatus: (d) => { live.toolStatus = d.message },
          onToken: (d) => { live.text += d.text ?? '' },
          onComplete: (d: SSECompleteEvent) => {
            live.citations = d.citations ?? []
            live.costRmb = typeof d.costRmb === 'number' ? d.costRmb : null
            live.toolStatus = undefined
          },
          onError: (d) => {
            live.error = d.message || 'AI 服务暂时不可用，请稍后重试'
            live.toolStatus = undefined
          },
          // 服务端可能未发 complete 就关流（超时/网关截断）——
          // 没有这层兜底「生成中」会永远挂着，输入框禁用（chat.ts 契约注释）
          onClose: () => { streaming.value = false },
        },
        controller,
        sessionId.value,
      )
    } catch (e) {
      live.error = e instanceof Error ? e.message : '请求失败，请稍后重试'
    } finally {
      streaming.value = false
      controller = null
      live.toolStatus = undefined
    }
  }

  /** 停止生成：中断 SSE，保留已收到的部分回答 */
  const stop = () => controller?.abort()

  /** 新会话：中断在途请求并换 sessionId（登出钩子亦可复用） */
  const newSession = () => {
    controller?.abort()
    messages.value = []
    sessionId.value = freshSessionId()
    persistSession()
  }

  return { messages, streaming, sessionId, ask, stop, newSession }
}
