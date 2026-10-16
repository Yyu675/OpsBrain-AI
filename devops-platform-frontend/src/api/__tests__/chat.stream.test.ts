/**
 * chatStream 的 onClose 契约测试。
 *
 * 这条回调是「对话框永久卡死」的唯一防线，必须验证它确实被接线到
 * fetchEventSource 的 onclose 上——只在 store 层测收尾逻辑是不够的，
 * 收尾函数写得再对，没人调用它也白搭。
 */
import { afterEach, describe, expect, it, vi } from 'vitest'

const fetchEventSourceMock = vi.hoisted(() => vi.fn())
vi.mock('@microsoft/fetch-event-source', () => ({
  fetchEventSource: fetchEventSourceMock,
}))

import { chatStream } from '../chat'

afterEach(() => {
  vi.clearAllMocks()
})

describe('chatStream — onClose 接线', () => {
  it('服务端关流时触发调用方的 onClose', async () => {
    // 模拟：连接建立后服务端直接关流，既没 complete 也没 error
    fetchEventSourceMock.mockImplementation(async (_url: string, opts: { onclose?: () => void }) => {
      opts.onclose?.()
    })

    const onClose = vi.fn()
    await chatStream('生产库连接超时怎么排查', { onClose })

    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('未提供 onClose 时不报错 —— 回调是可选的', async () => {
    fetchEventSourceMock.mockImplementation(async (_url: string, opts: { onclose?: () => void }) => {
      opts.onclose?.()
    })

    await expect(chatStream('问题', {})).resolves.toBeUndefined()
  })

  it('token 与 complete 事件仍正常分发 —— 别为了加 onClose 破坏原有分发', async () => {
    fetchEventSourceMock.mockImplementation(
      async (_url: string, opts: { onmessage?: (e: { event: string; data: string }) => void; onclose?: () => void }) => {
        opts.onmessage?.({ event: 'token', data: JSON.stringify({ text: '答案' }) })
        opts.onmessage?.({ event: 'complete', data: JSON.stringify({ costRmb: 0.01 }) })
        opts.onclose?.()
      }
    )

    const onToken = vi.fn()
    const onComplete = vi.fn()
    const onClose = vi.fn()
    await chatStream('问题', { onToken, onComplete, onClose })

    expect(onToken).toHaveBeenCalledWith(expect.objectContaining({ text: '答案' }))
    expect(onComplete).toHaveBeenCalledWith(expect.objectContaining({ costRmb: 0.01 }))
    expect(onClose).toHaveBeenCalledTimes(1)
  })
})

describe('chatStream — 入参校验', () => {
  it('空查询直接抛错，不发请求', async () => {
    await expect(chatStream('   ', {})).rejects.toThrow('查询内容不能为空')
    expect(fetchEventSourceMock).not.toHaveBeenCalled()
  })

  it('超长查询直接抛错，不发请求 —— 后端也会拒，前端先挡省一次往返', async () => {
    await expect(chatStream('x'.repeat(1501), {})).rejects.toThrow('1500')
    expect(fetchEventSourceMock).not.toHaveBeenCalled()
  })
})
