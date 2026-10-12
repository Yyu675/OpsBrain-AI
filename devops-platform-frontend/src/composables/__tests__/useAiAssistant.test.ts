/**
 * useAiAssistant — AI 助手会话编排单测（R0-2）。
 *
 * 保护的真实行为：五事件 SSE 契约折叠成消息列表；onClose 兜底复位
 * 「生成中」（chat.ts 契约：服务端可能不发 complete 就关流）；
 * sessionId 持久化跨刷新续会话。只 mock chatStream/存储边界。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'

const api = vi.hoisted(() => ({ chatStream: vi.fn() }))
vi.mock('@/api/chat', () => api)

import { useAiAssistant } from '../useAiAssistant'

interface Cbs {
  onToolStatus?: (d: { status: string; message: string }) => void
  onToken?: (d: { text: string }) => void
  onComplete?: (d: { costRmb: number; citations: string[] }) => void
  onError?: (d: { message: string }) => void
  onClose?: () => void
}

/** 让 ask() 挂起并把回调抓出来，由用例手动驱动事件 */
const useCapturedStream = () => {
  let cbs: Cbs = {}
  let release: () => void = () => {}
  api.chatStream.mockImplementation(
    (_q: string, cb: Cbs) => new Promise<void>((res) => { cbs = cb; release = () => { cb.onClose?.(); res() } })
  )
  return {
    get cbs() { return cbs },
    /** 收尾：complete 之后模拟关流 */
    finish: () => release(),
  }
}

describe('useAiAssistant', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
  })

  it('token 事件逐段拼接进最新回答，complete 收尾带成本与引用', async () => {
    const stream = useCapturedStream()
    const a = useAiAssistant()
    const p = a.ask('CPU 高怎么办')
    await vi.waitFor(() => expect(stream.cbs.onToken).toBeDefined())

    stream.cbs.onToolStatus?.({ status: 'start', message: '正在检索知识库…' })
    expect(a.messages.value[1].toolStatus).toBe('正在检索知识库…')

    stream.cbs.onToken?.({ text: '先看 ' })
    stream.cbs.onToken?.({ text: 'top 输出' })
    stream.cbs.onComplete?.({ costRmb: 0.0021, citations: ['KB-12 CPU 手册'] })
    stream.finish()
    await p

    expect(a.messages.value).toHaveLength(2)
    const reply = a.messages.value[1]
    expect(reply.role).toBe('assistant')
    expect(reply.text).toBe('先看 top 输出')
    expect(reply.costRmb).toBe(0.0021)
    expect(reply.citations).toEqual(['KB-12 CPU 手册'])
    expect(reply.toolStatus).toBeUndefined()
    expect(a.streaming.value).toBe(false)
  })

  it('服务端未发 complete 就关流：streaming 必须复位（否则输入框永久禁用）', async () => {
    const stream = useCapturedStream()
    const a = useAiAssistant()
    const p = a.ask('问题')
    await vi.waitFor(() => expect(stream.cbs.onClose).toBeDefined())

    // 只关流不发 complete —— onClose 回调必须当场复位 streaming
    stream.cbs.onClose?.()
    expect(a.streaming.value).toBe(false)

    stream.finish()
    await p
  })

  it('error 事件把可读文案写进回答气泡，而不是静默', async () => {
    const stream = useCapturedStream()
    const a = useAiAssistant()
    const p = a.ask('问题')
    await vi.waitFor(() => expect(stream.cbs.onError).toBeDefined())

    stream.cbs.onError?.({ message: 'LLM 服务限流，请稍后重试' })
    stream.finish()
    await p
    expect(a.messages.value[1].error).toContain('限流')
  })

  it('空输入与生成中重复提问都被拦截；chatStream 收到 sessionId', async () => {
    const stream = useCapturedStream()
    const a = useAiAssistant()

    await a.ask('   ')
    expect(api.chatStream).not.toHaveBeenCalled()

    const p = a.ask('第一个问题') // 保持生成中
    await vi.waitFor(() => expect(api.chatStream).toHaveBeenCalledTimes(1))
    a.ask('第二个问题')
    expect(api.chatStream).toHaveBeenCalledTimes(1)
    expect(api.chatStream.mock.calls[0][3]).toMatch(/^web-/)

    a.stop() // AbortController.abort：底层 fetch 会被 reject
    stream.finish()
    await p
  })

  it('sessionId 持久化：新实例复用同一会话（三层记忆多轮不中断）', async () => {
    const a1 = useAiAssistant()
    const sid = a1.sessionId.value
    const a2 = useAiAssistant()
    expect(a2.sessionId.value).toBe(sid)

    a2.newSession()
    expect(a2.sessionId.value).not.toBe(sid)
    const a3 = useAiAssistant()
    expect(a3.sessionId.value).toBe(a2.sessionId.value)
  })
})
