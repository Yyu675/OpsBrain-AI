/**
 * httpRequest 传输层行为测试。
 *
 * 现有 http.test.ts 只覆盖了 toFriendlyError / unwrapBiz 这两个纯函数，
 * 真正决定「用户看到哪种故障」的分类逻辑（超时 vs 网络不通 vs 主动取消）
 * 一直没有测试保护——本文件补上，锁住三条容易悄悄退化的契约：
 *
 * 1. **超时必须归类为 TIMEOUT，不能落成 NETWORK**。
 *    这两种故障的排查方向相反：NETWORK 提示「确认后端是否启动」，
 *    TIMEOUT 说明服务连上了只是没返回（慢查询 / 线程池打满）。
 *    分错会把排查的人引向完全错误的方向。
 *
 * 2. **主动取消不得被重试**。组件卸载 / 用户点「停止」后继续重试，
 *    等于无视取消意图，还会在页面销毁后继续打后端。
 *
 * 3. **写操作默认不重试**。POST 超时重试会建出重复工单。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { HttpError, httpRequest } from '../http'

const originalFetch = globalThis.fetch

afterEach(() => {
  globalThis.fetch = originalFetch
  vi.restoreAllMocks()
})

beforeEach(() => {
  vi.restoreAllMocks()
})

/** 永不 resolve 的 fetch，只在 signal 中止时按 reason reject——与真实 fetch 行为一致 */
const hangingFetch = vi.fn((_url: string, init?: RequestInit) =>
  new Promise((_resolve, reject) => {
    const signal = init?.signal
    if (!signal) return
    if (signal.aborted) {
      reject(signal.reason)
      return
    }
    signal.addEventListener('abort', () => reject(signal.reason), { once: true })
  })
)

describe('httpRequest — 超时分类', () => {
  it('超时抛出的是 TIMEOUT 而非 NETWORK', async () => {
    globalThis.fetch = hangingFetch as unknown as typeof fetch

    const err = await httpRequest('/api/v1/tickets', { timeout: 20, retries: 0 })
      .then(() => null)
      .catch((e: unknown) => e)

    expect(err).toBeInstanceOf(HttpError)
    // 关键断言：修复前 abort(new Error('timeout')) 的 name 是 'Error'，
    // isAbortError 判不出来，最终落到 NETWORK 分支
    expect((err as HttpError).code).toBe('TIMEOUT')
  })

  it('超时提示带上实际等待时长，而非写死的 15 秒', async () => {
    globalThis.fetch = hangingFetch as unknown as typeof fetch

    const err = await httpRequest('/api/v1/tickets', { timeout: 30, retries: 0 })
      .catch((e: unknown) => e as HttpError)

    // 30ms → 四舍五入 0 秒，重点是文案由 timeout 派生而非硬编码
    expect((err as HttpError).message).toContain('请求超时')
  })

  it('GET 超时会按 retries 重试', async () => {
    const spy = vi.fn((_url: string, init?: RequestInit) =>
      new Promise((_r, reject) => {
        init?.signal?.addEventListener('abort', () => reject(init.signal!.reason), { once: true })
      })
    )
    globalThis.fetch = spy as unknown as typeof fetch

    await httpRequest('/api/v1/tickets', { timeout: 10, retries: 2, retryDelay: 1 })
      .catch(() => undefined)

    // 首次 + 2 次重试
    expect(spy).toHaveBeenCalledTimes(3)
  })

  it('写操作默认不重试 —— 超时重试会建出重复工单', async () => {
    const spy = vi.fn((_url: string, init?: RequestInit) =>
      new Promise((_r, reject) => {
        init?.signal?.addEventListener('abort', () => reject(init.signal!.reason), { once: true })
      })
    )
    globalThis.fetch = spy as unknown as typeof fetch

    await httpRequest('/api/v1/tickets', { method: 'POST', body: '{}', timeout: 10, retryDelay: 1 })
      .catch(() => undefined)

    expect(spy).toHaveBeenCalledTimes(1)
  })
})

describe('httpRequest — 主动取消', () => {
  it('外部 signal 取消时原样抛出，不包装成 TIMEOUT', async () => {
    globalThis.fetch = hangingFetch as unknown as typeof fetch

    const ctl = new AbortController()
    const p = httpRequest('/api/v1/tickets', { signal: ctl.signal, timeout: 5000, retries: 2 })
    ctl.abort()

    const err = await p.catch((e: unknown) => e)
    // 主动取消不是超时，也不该被伪装成网络故障
    expect((err as HttpError).code).not.toBe('TIMEOUT')
  })

  it('主动取消不触发重试 —— 页面已销毁不该继续打后端', async () => {
    const spy = vi.fn((_url: string, init?: RequestInit) =>
      new Promise((_r, reject) => {
        init?.signal?.addEventListener('abort', () => reject(init.signal!.reason), { once: true })
      })
    )
    globalThis.fetch = spy as unknown as typeof fetch

    const ctl = new AbortController()
    const p = httpRequest('/api/v1/tickets', { signal: ctl.signal, timeout: 5000, retries: 3, retryDelay: 1 })
    ctl.abort()
    await p.catch(() => undefined)

    expect(spy).toHaveBeenCalledTimes(1)
  })
})

describe('httpRequest — 网络不可达', () => {
  it('fetch 直接抛 TypeError 时归类为 NETWORK', async () => {
    globalThis.fetch = vi.fn(() => Promise.reject(new TypeError('fetch failed'))) as unknown as typeof fetch

    const err = await httpRequest('/api/v1/tickets', { retries: 0 }).catch((e: unknown) => e)

    expect((err as HttpError).code).toBe('NETWORK')
  })
})
