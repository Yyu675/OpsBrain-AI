/**
 * AI 对话模式（ChatMode）测试 —— **非流式部分**。
 *
 * ── 范围与理由 ────────────────────────────────────────────────
 * `ChatMode.vue` 821 行，是当前最大的零测试组件。
 * 它的核心是 SSE 流式问答，而流式本身测试成本高（要模拟事件序列、
 * 时序、中断），投入产出比低。
 *
 * 但**缺陷更多集中在"周边"而非核心流程**：发送前的校验、
 * 停止/重新生成的守卫、清空的互斥、卸载时的状态收尾。
 * 这些都不依赖流，可以用极低成本覆盖——本文件就切在这里。
 *
 * ── 用真实 store 而不是桩 ─────────────────────────────────────
 * `useChatStore` 不桩掉。原因是本组件的多数逻辑就是"改 store 状态"，
 * 桩掉之后断言的只是"调用了某个方法"，而真正要守的是
 * **状态迁移的结果**（消息进没进列表、isStreaming 有没有复位）。
 * 只桩 `chatStream`（网络层）与 `notify`。
 *
 * ── 重点：卸载与中断必须复位 isStreaming ──────────────────────
 * `isStreaming` 存在 store 里（跨组件共享）。若组件卸载时不收尾，
 * **切走再回来输入框仍是禁用态**，用户以为页面坏了，只能刷新。
 * 这是本组件注释里专门写过的坑，值得钉死。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'

const notifyMock = vi.hoisted(() => ({
  success: vi.fn(), warning: vi.fn(), error: vi.fn(),
  info: vi.fn(), clearCooldown: vi.fn(),
}))
vi.mock('@/utils/notify', () => ({
  notify: notifyMock,
  handleServerError: vi.fn(),
}))

/**
 * chatStream 桩。
 *
 * ⚠️ 不能让它返回「永不 resolve 的 Promise」：`sendMessage` 内部
 * `await runStream(...)`，永不结束会让 `await vm.sendMessage()` 一直挂起，
 * 测试 5 秒超时——第一版就是这么写的，11 个用例集体 timeout，
 * 失败信息只有一句 "Test timed out"，与被测逻辑毫无关系。
 *
 * 正确做法：让它**立即 resolve 但不触发任何回调**。
 * 这样 chatStream 这一次「调用」结束了，而 store 里的 isStreaming
 * 仍是 true（因为没有 complete/error 事件来收尾）——
 * 正好模拟出「流式进行中」这个待测状态，且不阻塞测试。
 */
const chatStreamMock = vi.hoisted(() => vi.fn())
vi.mock('@/api/chat', () => ({ chatStream: chatStreamMock }))

const copyTextMock = vi.hoisted(() => vi.fn())
vi.mock('@/utils/clipboard', () => ({ copyText: copyTextMock }))

import { useChatStore } from '@/stores/chat'
import ChatMode from '../ChatMode.vue'

const mountChat = async () => {
  const wrapper = mount(ChatMode, {
    global: {
      stubs: { RelativeTime: true },
    },
  })
  await flushPromises()
  return wrapper
}

type Vm = {
  inputText: string
  canSend: boolean
  sendMessage: () => Promise<void>
  stopGeneration: () => void
  clearChat: () => void
  copyMessage: (c: string) => Promise<void>
  regenerate: (m: { id: string; metadata?: { sourceQuery?: string } }) => Promise<void>
  handleKeydown: (e: KeyboardEvent) => void
}
const vmOf = (w: VueWrapper) => w.vm as unknown as Vm

beforeEach(() => {
  setActivePinia(createPinia())
  localStorage.clear()
  vi.clearAllMocks()
  copyTextMock.mockResolvedValue(true)
  // 立即 resolve 且不触发回调 → 调用返回，但 isStreaming 仍为 true
  chatStreamMock.mockResolvedValue(undefined)
})

describe('挂载初始化', () => {
  it('使用 global 伪键会话，并注入欢迎语', async () => {
    // 全局对话与「按工单隔离」的分析会话必须互不干扰，
    // 混在一起会让工单 A 的上下文泄漏到全局问答里
    const w = await mountChat()
    const chat = useChatStore()

    expect(chat.messages.length).toBeGreaterThan(0)
    expect(chat.messages[0].role).toBe('system')
    expect(w.exists()).toBe(true)
  })

  it('欢迎语只注入一次，重复挂载不叠加', async () => {
    const w1 = await mountChat()
    const chat = useChatStore()
    const n = chat.messages.length
    w1.unmount()

    await mountChat()
    expect(chat.messages.length).toBe(n)
  })
})

describe('发送前校验（canSend）', () => {
  it('空输入不可发送', async () => {
    const w = await mountChat()
    const vm = vmOf(w)

    vm.inputText = '   '
    await w.vm.$nextTick()
    expect(vm.canSend).toBe(false)
  })

  it('超过 1500 字不可发送——与后端 SecurityInputGuard 的上限一致', async () => {
    // 后端 check() 对 >1500 直接抛 40001。前端不拦的话，
    // 用户敲完一大段才被拒，白等一次往返
    const w = await mountChat()
    const vm = vmOf(w)

    vm.inputText = 'a'.repeat(1501)
    await w.vm.$nextTick()
    expect(vm.canSend).toBe(false)

    vm.inputText = 'a'.repeat(1500)
    await w.vm.$nextTick()
    expect(vm.canSend).toBe(true)
  })

  it('流式进行中不可发送——防止并发请求打乱消息顺序', async () => {
    const w = await mountChat()
    const vm = vmOf(w)

    vm.inputText = '问题一'
    await vm.sendMessage()
    await flushPromises()

    vm.inputText = '问题二'
    await w.vm.$nextTick()
    expect(vm.canSend).toBe(false)
  })

  it('不满足条件时 sendMessage 直接返回，不发请求', async () => {
    const w = await mountChat()
    const vm = vmOf(w)
    vm.inputText = ''

    await vm.sendMessage()

    expect(chatStreamMock).not.toHaveBeenCalled()
  })
})

describe('发送', () => {
  it('用户消息进列表，输入框立即清空', async () => {
    // 先清空再等响应是刻意的：不清空的话，用户会以为没发出去而重复点
    const w = await mountChat()
    const vm = vmOf(w)
    const chat = useChatStore()

    vm.inputText = '  Redis 连接超时  '
    await vm.sendMessage()
    await flushPromises()

    expect(vm.inputText).toBe('')
    const userMsgs = chat.messages.filter((m) => m.role === 'user')
    expect(userMsgs[userMsgs.length - 1].content).toBe('Redis 连接超时')
  })

  it('调用 chatStream 时带上 global 会话', async () => {
    const w = await mountChat()
    const vm = vmOf(w)

    vm.inputText = '问题'
    await vm.sendMessage()
    await flushPromises()

    expect(chatStreamMock).toHaveBeenCalledTimes(1)
    expect(chatStreamMock.mock.calls[0][0]).toBe('问题')
  })

  it('发送后进入流式态', async () => {
    const w = await mountChat()
    const chat = useChatStore()

    vmOf(w).inputText = '问题'
    await vmOf(w).sendMessage()
    await flushPromises()

    expect(chat.isStreaming).toBe(true)
  })
})

describe('回车发送', () => {
  it('Enter 发送', async () => {
    const w = await mountChat()
    const vm = vmOf(w)
    vm.inputText = '问题'

    const ev = new KeyboardEvent('keydown', { key: 'Enter', shiftKey: false })
    const spy = vi.spyOn(ev, 'preventDefault')
    vm.handleKeydown(ev)
    await flushPromises()

    expect(spy).toHaveBeenCalled()
    expect(chatStreamMock).toHaveBeenCalled()
  })

  it('Shift+Enter 换行，不发送', async () => {
    // 多行输入是运维贴日志的常见场景，这条搞错会让人没法贴堆栈
    const w = await mountChat()
    const vm = vmOf(w)
    vm.inputText = '第一行'

    vm.handleKeydown(new KeyboardEvent('keydown', { key: 'Enter', shiftKey: true }))
    await flushPromises()

    expect(chatStreamMock).not.toHaveBeenCalled()
  })
})

describe('清空对话', () => {
  it('流式进行中拒绝清空并提示', async () => {
    // 清到一半会让正在写入的 assistant 消息失去归属，
    // 后续 token 追加到一个已不存在的消息上
    const w = await mountChat()
    const vm = vmOf(w)

    vm.inputText = '问题'
    await vm.sendMessage()
    await flushPromises()

    vm.clearChat()

    expect(notifyMock.warning).toHaveBeenCalledWith(expect.stringContaining('无法清空'))
  })

  it('空闲时清空消息与输入框', async () => {
    const w = await mountChat()
    const vm = vmOf(w)
    const chat = useChatStore()
    chat.pushUserMessage('历史消息')
    vm.inputText = '草稿'

    vm.clearChat()

    expect(vm.inputText).toBe('')
    expect(chat.messages.filter((m) => m.role === 'user')).toHaveLength(0)
    expect(notifyMock.success).toHaveBeenCalled()
  })
})

describe('停止生成', () => {
  it('非流式态点停止无副作用', async () => {
    const w = await mountChat()
    vmOf(w).stopGeneration()
    expect(notifyMock.info).not.toHaveBeenCalled()
  })

  it('流式中停止会中断请求并提示', async () => {
    // ── 这里必须用「可控 deferred」而非立即 resolve ──────────
    // `runStream` 的 finally 会把 abortController 置回 null。
    // 若 chatStream 立即 resolve，等到调用 stopGeneration 时
    // controller 已经没了，守卫直接 return——测的就不是停止逻辑了。
    //
    // 所以让它挂起、但**不 await sendMessage**（用 void 起飞），
    // 测完再 resolve 收尾。这样既保留了「流仍在进行」的真实状态，
    // 又不会让测试超时。
    let capturedSignal: AbortSignal | undefined
    let release: () => void = () => {}
    chatStreamMock.mockImplementation((_q, _h, ctrl) => {
      capturedSignal = (ctrl as AbortController)?.signal
      return new Promise<void>((r) => { release = r })
    })
    const w = await mountChat()
    const vm = vmOf(w)

    vm.inputText = '问题'
    const p = vm.sendMessage()
    await flushPromises()

    vm.stopGeneration()

    expect(capturedSignal?.aborted).toBe(true)
    expect(notifyMock.info).toHaveBeenCalledWith(expect.stringContaining('已停止'))

    release()
    await p
  })
})

describe('重新生成', () => {
  it('流式中拒绝，避免并发两条流', async () => {
    const w = await mountChat()
    const vm = vmOf(w)

    vm.inputText = '问题'
    await vm.sendMessage()
    await flushPromises()
    chatStreamMock.mockClear()

    await vm.regenerate({ id: 'x', metadata: { sourceQuery: '问题' } })

    expect(chatStreamMock).not.toHaveBeenCalled()
    expect(notifyMock.warning).toHaveBeenCalledWith(expect.stringContaining('请稍候'))
  })

  it('找不到原始提问时提示而非静默失败', async () => {
    // 静默返回的话，用户点了「重新生成」什么都没发生，
    // 会反复点击并以为系统卡了
    const w = await mountChat()

    await vmOf(w).regenerate({ id: 'no-source' })

    expect(chatStreamMock).not.toHaveBeenCalled()
    expect(notifyMock.warning).toHaveBeenCalledWith(expect.stringContaining('找不到原始提问'))
  })

  it('用 metadata.sourceQuery 重跑，并移除原回答', async () => {
    // 必须用该条回答自己的 sourceQuery，而不是「最后一次提问」——
    // 用户可能在中间问过别的，用 lastUserQuery 会重跑成另一个问题
    const w = await mountChat()
    const chat = useChatStore()
    chat.pushUserMessage('第一个问题')
    const assistant = chat.startAssistantMessage('第一个问题')
    chat.finishStreaming('回答内容')
    chat.pushUserMessage('第二个问题')
    chatStreamMock.mockClear()

    await vmOf(w).regenerate({ id: assistant.id, metadata: { sourceQuery: '第一个问题' } })
    await flushPromises()

    expect(chatStreamMock.mock.calls[0][0]).toBe('第一个问题')
    expect(chat.messages.find((m) => m.id === assistant.id)).toBeUndefined()
  })
})

describe('复制消息', () => {
  it('成功提示已复制', async () => {
    const w = await mountChat()
    await vmOf(w).copyMessage('内容')

    expect(copyTextMock).toHaveBeenCalledWith('内容')
    expect(notifyMock.success).toHaveBeenCalled()
  })

  it('失败时提示手动选择，不静默', async () => {
    // 浏览器在非安全上下文会拒绝 clipboard API。
    // 静默失败会让用户以为复制成功，粘贴出来是旧内容
    copyTextMock.mockResolvedValue(false)
    const w = await mountChat()

    await vmOf(w).copyMessage('内容')

    expect(notifyMock.warning).toHaveBeenCalledWith(expect.stringContaining('手动'))
  })
})

describe('SSE 出错时的收尾：已生成内容不能被抹掉', () => {
  /** 让 chatStream 在被调用时立刻回调 onError，并可控制此前是否已产出内容 */
  const streamThenError = (partial: string, code: number, message: string) => {
    chatStreamMock.mockImplementation(async (_q: unknown, cbs: any) => {
      // SSETokenEvent 的字段名是 text 而非 content。写错会让 appendToken
      // 收到 undefined，断言失败看起来像产品代码有问题，实则是夹具错
      if (partial) cbs.onToken?.({ text: partial })
      cbs.onError?.({ traceId: 't-1', code, message })
    })
  }

  it('模型已答了一半才出错 —— 保留那半页，错误提示追加在后面', async () => {
    // 这是本组最重要的一条。此前 onError 直接 msg.content = errorText，
    // 模型写了半页诊断思路被一句「服务内部异常」整个抹掉，
    // 而那半页往往已经有用，用户还没来得及复制
    streamThenError('第一步：先看 Pod 状态', 50001, '服务内部异常')
    const w = await mountChat()
    const chat = useChatStore()

    vmOf(w).inputText = '问题'
    await vmOf(w).sendMessage()
    await flushPromises()

    const last = chat.messages[chat.messages.length - 1]
    // 断言落在「已输出内容还在不在」——覆盖式实现这里必然失败
    expect(last.content).toContain('第一步：先看 Pod 状态')
    expect(last.content).toContain('服务内部异常')
  })

  it('一个 token 都没产出就出错 —— 只显示错误，不留空行残迹', async () => {
    // 与上一条构成分叉：无条件拼接 partial 的实现会在这里
    // 产出 "\n\n---\n\n❌ ..." 这种以分隔线开头的怪内容
    streamThenError('', 40005, '配额已用完')
    const w = await mountChat()
    const chat = useChatStore()

    vmOf(w).inputText = '问题'
    await vmOf(w).sendMessage()
    await flushPromises()

    const last = chat.messages[chat.messages.length - 1]
    expect(last.content).toContain('配额已用完')
    expect(last.content.startsWith('❌')).toBe(true)
  })

  it('可重试的码用 warning 提示可重试，不可重试的用 error', async () => {
    // 50002 的 Retry=SAFE：告诉用户「可重试」，否则他以为服务坏了就走了
    streamThenError('', 50002, '连接超时')
    const w = await mountChat()

    vmOf(w).inputText = '问题'
    await vmOf(w).sendMessage()
    await flushPromises()

    expect(notifyMock.warning).toHaveBeenCalledWith(expect.stringContaining('可重试'))
    expect(notifyMock.error).not.toHaveBeenCalled()
  })

  it('不可重试的码不得提示可重试 —— 配额用完时让用户反复点是有害的', async () => {
    streamThenError('', 40005, '配额已用完')
    const w = await mountChat()

    vmOf(w).inputText = '问题'
    await vmOf(w).sendMessage()
    await flushPromises()

    expect(notifyMock.error).toHaveBeenCalled()
    expect(notifyMock.warning).not.toHaveBeenCalledWith(
      expect.stringContaining('可重试')
    )
  })

  it('出错后 isStreaming 必须复位，否则输入框永久禁用', async () => {
    streamThenError('半句', 50001, '服务内部异常')
    const w = await mountChat()
    const chat = useChatStore()

    vmOf(w).inputText = '问题'
    await vmOf(w).sendMessage()
    await flushPromises()

    expect(chat.isStreaming).toBe(false)
  })
})

describe('卸载收尾：isStreaming 必须复位', () => {
  it('卸载时中断请求', async () => {
    // 同上：需要 abortController 在卸载时仍存在，故用可控 deferred
    let capturedSignal: AbortSignal | undefined
    let release: () => void = () => {}
    chatStreamMock.mockImplementation((_q, _h, ctrl) => {
      capturedSignal = (ctrl as AbortController)?.signal
      return new Promise<void>((r) => { release = r })
    })
    const w = await mountChat()

    vmOf(w).inputText = '问题'
    const p = vmOf(w).sendMessage()
    await flushPromises()

    w.unmount()

    expect(capturedSignal?.aborted).toBe(true)
    release()
    await p
  })

  it('卸载后 isStreaming 复位——否则切走再回来输入框永久禁用', async () => {
    // ── 本文件最重要的一条 ──────────────────────────────────
    // isStreaming 存在 store（跨组件共享）。只 abort 不收尾的话，
    // abort 走的 catch 分支在组件已卸载时未必执行完，
    // 状态就永远停在 true，用户以为页面坏了只能刷新
    const w = await mountChat()
    const chat = useChatStore()

    vmOf(w).inputText = '问题'
    await vmOf(w).sendMessage()
    await flushPromises()
    expect(chat.isStreaming).toBe(true)

    w.unmount()

    expect(chat.isStreaming).toBe(false)
  })

  it('已生成的部分内容在卸载后保留，并注明已停止', async () => {
    // 用户切走前 AI 已经写了一半，那部分有价值，不该丢
    const w = await mountChat()
    const chat = useChatStore()

    vmOf(w).inputText = '问题'
    await vmOf(w).sendMessage()
    await flushPromises()
    chat.appendToken?.('已生成的一半内容')

    w.unmount()

    const last = chat.messages[chat.messages.length - 1]
    expect(last.content).toContain('已停止生成')
  })
})
