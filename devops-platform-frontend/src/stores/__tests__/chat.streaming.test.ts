/**
 * 对话流式状态的收尾测试。
 *
 * ── 要锁住什么 ────────────────────────────────────────────────
 * `isStreaming` 控制着输入框的 `:disabled` 与「发送/停止」按钮的切换。
 * 只要有任何一条路径漏了收尾，整个对话框就**永久卡死**：
 *
 *   - 输入框一直禁用，打不了字
 *   - 发送按钮被「停止生成」替换，而此时 abortController 已置空，
 *     stopGeneration 第一行就 return —— 点了没有任何反应
 *
 * 用户唯一的出路是刷新页面，而且完全不知道为什么。
 *
 * 真实触发场景：服务端未发 complete 就关流（后端超时切断、网关 502、
 * Nginx proxy_read_timeout 到期）。fetchEventSource 此时**正常 resolve**，
 * 不抛错、不进 catch、不触发 onError——所以只在 complete/error 里
 * 复位状态的写法必然漏掉这条路径。
 */
import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

import { useChatStore } from '../chat'

beforeEach(() => {
  localStorage.clear()
  setActivePinia(createPinia())
})

describe('chat store — 流式状态必须能收尾', () => {
  it('finishStreaming 复位 isStreaming 与 streamingId', () => {
    const chat = useChatStore()
    chat.ensureSession('global')
    chat.pushUserMessage('问题')
    chat.startAssistantMessage('问题')

    expect(chat.isStreaming).toBe(true)
    expect(chat.streamingId).not.toBeNull()

    chat.finishStreaming()

    expect(chat.isStreaming).toBe(false)
    expect(chat.streamingId).toBeNull()
  })

  it('带错误文案收尾时，内容被替换且状态复位', () => {
    const chat = useChatStore()
    chat.ensureSession('global')
    chat.startAssistantMessage('问题')
    chat.appendToken('部分内容')

    chat.finishStreaming('❌ 连接意外中断，未收到回答，请重试')

    expect(chat.isStreaming).toBe(false)
    const last = chat.messages[chat.messages.length - 1]
    expect(last.content).toContain('连接意外中断')
  })

  /**
   * 这条对应 ChatMode 的 onClose 兜底逻辑：
   * 已生成的内容必须保留，不能因为断流就把用户已经看到的回答抹掉。
   */
  it('中途断流保留已生成内容', () => {
    const chat = useChatStore()
    chat.ensureSession('global')
    chat.startAssistantMessage('问题')
    chat.appendToken('第一段')
    chat.appendToken('第二段')

    const partial = chat.streamingMessage?.content ?? ''
    expect(partial).toBe('第一段第二段')

    chat.finishStreaming(`${partial}\n\n_（连接已中断，以上为已生成内容）_`)

    const last = chat.messages[chat.messages.length - 1]
    expect(last.content).toContain('第一段第二段')
    expect(last.content).toContain('连接已中断')
    expect(chat.isStreaming).toBe(false)
  })

  it('重复 finishStreaming 幂等，不会把已收尾的消息再改一次', () => {
    const chat = useChatStore()
    chat.ensureSession('global')
    chat.startAssistantMessage('问题')
    chat.appendToken('正常回答')
    chat.finishStreaming()

    const contentAfterFirst = chat.messages[chat.messages.length - 1].content
    // onClose 兜底会在已收尾时提前 return，这里模拟即使误调也不破坏内容
    chat.finishStreaming()

    expect(chat.messages[chat.messages.length - 1].content).toBe(contentAfterFirst)
    expect(chat.isStreaming).toBe(false)
  })

  it('isStreaming 不进入持久化 —— 刷新后不该带着卡死状态回来', () => {
    const chat = useChatStore()
    chat.ensureSession('global')
    chat.startAssistantMessage('问题')
    expect(chat.isStreaming).toBe(true)

    // 持久化的只有 buckets（会话内容），不含流式状态
    const raw = localStorage.getItem('__store__:chat-sessions')
    if (raw) {
      expect(raw).not.toContain('isStreaming')
    }

    // 新实例（模拟刷新）应回到非流式
    setActivePinia(createPinia())
    const fresh = useChatStore()
    expect(fresh.isStreaming).toBe(false)
  })
})
