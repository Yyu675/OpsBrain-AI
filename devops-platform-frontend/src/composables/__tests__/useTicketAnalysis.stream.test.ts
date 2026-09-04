/**
 * AI 分析的流式生命周期测试。
 *
 * ── 要锁住什么 ────────────────────────────────────────────────
 * `analysisStreaming` 控制着工单详情页 AI 面板的「停止生成」按钮与
 * 「重新分析」的可用性。任何一条路径漏了收尾，面板就**永久卡死**——
 * 停止按钮一直显示、重新分析点不动，用户只能刷新整个工单详情页。
 *
 * 真实触发场景：服务端未发 complete 就关流（后端超时切断、网关 502、
 * Nginx proxy_read_timeout 到期）。`fetchEventSource` 此时**正常 resolve**，
 * 不抛错、不进 catch、不触发 onError——所以只在 complete/error 里
 * 复位状态的写法必然漏掉这条路径。
 *
 * ── 为什么单独一个文件 ────────────────────────────────────────
 * 既有的 useTicketAnalysis.test.ts 只测纯解析函数（不需要挂载组件）。
 * 本文件要跑 composable 的完整生命周期，依赖 Vue 组件上下文与 SSE mock，
 * 混在一起会让那批纯函数用例也背上 mock 负担。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h } from 'vue'
import { mount } from '@vue/test-utils'

/** 捕获 chatStream 的回调，供用例手动驱动 SSE 事件 */
const chatMock = vi.hoisted(() => ({
  chatStream: vi.fn(),
  lastCallbacks: null as Record<string, ((d?: unknown) => void) | undefined> | null,
}))
vi.mock('@/api/chat', () => ({
  chatStream: (query: string, callbacks: Record<string, () => void>, ctl?: AbortController) => {
    chatMock.lastCallbacks = callbacks
    return chatMock.chatStream(query, callbacks, ctl)
  },
}))

vi.mock('@/utils/notify', () => ({
  notify: {
    success: vi.fn(), warning: vi.fn(), error: vi.fn(),
    info: vi.fn(), clearCooldown: vi.fn(),
  },
  handleServerError: vi.fn(),
}))

vi.mock('@/api/ticketAiAnalysis', () => ({
  saveTicketAiAnalysis: vi.fn(async () => ({ id: 1 })),
  fetchLatestTicketAiAnalysis: vi.fn(async () => null),
  submitAiAnalysisFeedback: vi.fn(async () => undefined),
}))

vi.mock('@/api/tickets', () => ({
  fetchTickets: vi.fn(async () => ({ tickets: [], total: 0, totalPages: 0 })),
}))

vi.mock('@/api/knowledge', () => ({
  fetchKnowledgeDocs: vi.fn(async () => ({ content: [], totalElements: 0 })),
}))

import { useTicketAnalysis } from '../useTicketAnalysis'

type Analysis = ReturnType<typeof useTicketAnalysis>

/** 在组件上下文中跑 composable（它注册了 onBeforeUnmount） */
const setup = () => {
  let api!: Analysis
  const wrapper = mount(
    defineComponent({
      setup() {
        api = useTicketAnalysis(
          () => 'TKT-20260824-0001',
          () => '工单上下文',
          () => '订单服务',
          () => '支付超时'
        )
        return () => h('div')
      },
    })
  )
  return { api, wrapper }
}

beforeEach(() => {
  vi.clearAllMocks()
  chatMock.lastCallbacks = null
})

describe('useTicketAnalysis — 流未正常收尾时必须兜底', () => {
  it('服务端关流但没发 complete 时，analysisStreaming 复位', async () => {
    // 模拟：连接建立、吐了几个 token，然后服务端直接关流
    chatMock.chatStream.mockImplementation(async (_q, cb) => {
      cb.onToken?.({ text: '可能原因：连接池打满' })
      cb.onClose?.()
    })

    const { api, wrapper } = setup()
    await api.runAnalysis()
    await wrapper.vm.$nextTick()

    expect(api.analysisStreaming.value).toBe(false)
    expect(api.analysisDone.value).toBe(true)
  })

  it('中断时保留已生成内容并注明 —— 不能把用户已看到的分析抹掉', async () => {
    chatMock.chatStream.mockImplementation(async (_q, cb) => {
      cb.onToken?.({ text: '可能原因：连接池打满' })
      cb.onClose?.()
    })

    const { api } = setup()
    await api.runAnalysis()

    expect(api.analysisContent.value).toContain('连接池打满')
    expect(api.analysisContent.value).toContain('连接已中断')
  })

  it('完全没内容就断流时给出明确提示，而非留空白面板', async () => {
    chatMock.chatStream.mockImplementation(async (_q, cb) => {
      cb.onClose?.()
    })

    const { api } = setup()
    await api.runAnalysis()

    expect(api.analysisContent.value).toContain('连接意外中断')
    expect(api.analysisStreaming.value).toBe(false)
  })

  it('正常 complete 后 onClose 不重复改写内容', async () => {
    chatMock.chatStream.mockImplementation(async (_q, cb) => {
      cb.onToken?.({ text: '完整分析结论' })
      cb.onComplete?.({ costRmb: 0.01, citations: [] })
      cb.onClose?.()   // fetchEventSource 在 complete 之后仍会触发 onclose
    })

    const { api } = setup()
    await api.runAnalysis()

    expect(api.analysisContent.value).toBe('完整分析结论')
    expect(api.analysisContent.value).not.toContain('连接已中断')
    expect(api.analysisStreaming.value).toBe(false)
  })

  it('onError 之后 onClose 也不重复追加', async () => {
    chatMock.chatStream.mockImplementation(async (_q, cb) => {
      cb.onError?.({ message: '模型服务繁忙' })
      cb.onClose?.()
    })

    const { api } = setup()
    await api.runAnalysis()

    const occurrences = api.analysisContent.value.split('❌').length - 1
    expect(occurrences).toBe(1)
    expect(api.analysisStreaming.value).toBe(false)
  })
})

describe('useTicketAnalysis — generateReply 同样要兜底', () => {
  it('生成回复草稿时断流，状态复位且内容保留', async () => {
    chatMock.chatStream.mockImplementation(async (_q, cb) => {
      cb.onToken?.({ text: '建议先检查连接池配置' })
      cb.onClose?.()
    })

    const { api } = setup()
    await api.generateReply()

    expect(api.analysisStreaming.value).toBe(false)
    expect(api.analysisContent.value).toContain('建议先检查连接池配置')
    expect(api.analysisContent.value).toContain('连接已中断')
  })

  it('无内容断流时给出提示', async () => {
    chatMock.chatStream.mockImplementation(async (_q, cb) => {
      cb.onClose?.()
    })

    const { api } = setup()
    await api.generateReply()

    expect(api.analysisContent.value).toContain('连接意外中断')
    expect(api.analysisStreaming.value).toBe(false)
  })
})

describe('useTicketAnalysis — 卸载时收尾', () => {
  it('组件卸载时把「分析中」状态复位 —— 该状态跨组件共享不能残留', async () => {
    // 让流悬停，模拟卸载时仍在生成
    chatMock.chatStream.mockImplementation(() => new Promise(() => { /* 永不 resolve */ }))

    const { api, wrapper } = setup()
    void api.runAnalysis()
    await wrapper.vm.$nextTick()
    expect(api.analysisStreaming.value).toBe(true)

    wrapper.unmount()

    expect(api.analysisStreaming.value).toBe(false)
  })
})
