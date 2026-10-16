import { beforeEach, describe, expect, it, vi } from 'vitest'

/**
 * `loginViaForm` 的**失败分支 + 重试路径**单测。
 *
 * 为什么这件事值得单测（而不是等 e2e 腿去跑）：
 * helper 的价值全在「红的时候能不能一眼看出该查哪一层」，而那几条失败分支
 * 在 CI 的 e2e 腿里**永远走不到**——走到了就说明套件红了。也就是说，
 * 只有在这里用假 Page 驱动它们，诊断文本才算被验证过；否则「报错自带现场」
 * 只是一句没人跑过的承诺（2026-10-15 那次红案的教训正是：断言文本没带现场，
 * 而作业日志与截图都在沙箱读不到的域上，只能靠猜）。
 *
 * 第二轮红案（run 38679023602，main）之后新增的覆盖：点击落空要**重试**，
 * 但请求已发出时**不能**重试（会重复提交、还会多打一次限流桶）。
 *
 * 覆盖路径：正常 / 点击落空后重试成功 / ①点满次数仍落空 / ①′请求已发但无响应
 * / ②非 2xx（429 与 401 分别点名）/ ③token 未落盘。
 */

const state = vi.hoisted(() => ({ urlAssertions: [] as { pattern: unknown; opts?: unknown }[] }))

// helper 里用的是 playwright 的 expect(page).toHaveURL —— 该匹配器绑定真实 Page，
// 假对象会被它拒（expectTypes）。这里换成可断言的替身，只替这一个匹配器。
vi.mock('@playwright/test', () => ({
  expect: (_received: unknown) => ({
    toHaveURL: (pattern: unknown, opts?: unknown) => {
      state.urlAssertions.push({ pattern, opts })
      return Promise.resolve()
    },
  }),
}))

import { loginViaForm } from './login'

type Cfg = {
  status?: number
  body?: string
  token?: string | null
  /** 前 N 次点击被吞：不发请求、也不会有响应（模拟 boot 期重渲染吞掉点击） */
  swallowClicks?: number
  /** 请求发出去了但永远没有响应（后端未就绪/挂住） */
  requestWithoutResponse?: boolean
}

function fakePage(cfg: Cfg) {
  const calls: string[] = []
  const handlers: Record<string, ((payload: unknown) => void)[]> = {}
  let clicks = 0
  let waits = 0

  const emit = (ev: string, payload: unknown) => (handlers[ev] ?? []).forEach((h) => h(payload))

  const locator = (sel: string) => ({
    fill: vi.fn(async (v: string) => {
      calls.push(`fill:${sel}=${v}`)
    }),
    click: vi.fn(async () => {
      clicks += 1
      calls.push(`click:${sel}`)
      // 被吞的点击：不发请求。真实浏览器里这正是「点在失效节点上」的表现。
      const swallowed = clicks <= (cfg.swallowClicks ?? 0)
      if (!swallowed) emit('request', { url: () => 'http://localhost:5173/api/v1/auth/login' })
    }),
  })

  return {
    calls,
    get clicks() {
      return clicks
    },
    goto: vi.fn(async (url: string) => {
      calls.push(`goto:${url}`)
    }),
    reload: vi.fn(async () => {
      calls.push('reload')
    }),
    // 记两次：#1 = 清 localStorage（必须在 reload/填表之前），#2 = 回读 token
    evaluate: vi.fn(async () => {
      calls.push(`evaluate#${calls.filter((c) => c.startsWith('evaluate#')).length + 1}`)
      return cfg.token === undefined ? 'tok-abc' : cfg.token
    }),
    locator,
    on: vi.fn((ev: string, h: (payload: unknown) => void) => {
      ;(handlers[ev] ??= []).push(h)
    }),
    off: vi.fn((ev: string, h: (payload: unknown) => void) => {
      handlers[ev] = (handlers[ev] ?? []).filter((x) => x !== h)
    }),
    // helper 先挂 waitForResponse 再 click，故第 N 次等待对应第 N 次点击
    waitForResponse: vi.fn(async () => {
      waits += 1
      const clickNo = waits
      if (clickNo <= (cfg.swallowClicks ?? 0)) throw new Error('Timeout 8000ms exceeded')
      if (cfg.requestWithoutResponse) throw new Error('Timeout 8000ms exceeded')
      return { status: () => cfg.status ?? 200, text: async () => cfg.body ?? '' }
    }),
  }
}

const asPage = <T,>(p: T) => p as never

describe('loginViaForm 诊断', () => {
  beforeEach(() => {
    state.urlAssertions.length = 0
  })

  it('正常：跳到首页且 token 已落盘——并且 URL 断言确实挂上（不是把断言写没了）', async () => {
    const page = fakePage({ status: 200, token: 'tok-abc' })
    await loginViaForm(asPage(page), 'admin', 'admin123')
    expect(state.urlAssertions).toHaveLength(1)
    expect(String(state.urlAssertions[0].pattern)).toContain('localhost:5173')
    expect(page.clicks).toBe(1) // 一次就成，不做多余重试
  })

  it('顺序纪律：先清 localStorage 再填表（否则可能拿上一个账号的登录态冒充）', async () => {
    const page = fakePage({})
    await loginViaForm(asPage(page), 'e2e_operator', 'E2E_OP_Pwd2026!')
    const order = page.calls.join(' → ')
    expect(order).toMatch(/goto:\/login → evaluate#1 → reload → fill:input\[placeholder="用户名"\]/)
    expect(order).toMatch(/fill:input\[placeholder="密码"\]=E2E_OP_Pwd2026!/)
  })

  it('点击落空后自动重试：第 1 次被吞、第 2 次成功——不该红，且重试时重填了表单', async () => {
    const page = fakePage({ swallowClicks: 1, status: 200, token: 'tok-abc' })
    await loginViaForm(asPage(page), 'e2e_viewer', 'E2E_VW_Pwd2026!')
    expect(page.clicks).toBe(2)
    // 重渲染会把值绑到新节点，所以每次重试都要重填：2 轮 × 2 个输入框
    expect(page.calls.filter((c) => c.startsWith('fill:'))).toHaveLength(4)
    expect(page.calls.filter((c) => c === 'fill:input[placeholder="密码"]=E2E_VW_Pwd2026!')).toHaveLength(2)
    expect(state.urlAssertions).toHaveLength(1)
  })

  it('原因①：点满 3 次都没产生请求——报错点名"未产生请求"与重试次数，不指向鉴权', async () => {
    const page = fakePage({ swallowClicks: 99 })
    const err = await loginViaForm(asPage(page), 'e2e_viewer', 'pwd').catch((e: Error) => e)
    expect(String(err.message)).toMatch(/未产生请求/)
    expect(String(err.message)).toMatch(/原因①/)
    expect(String(err.message)).toMatch(/点击 3 次/)
    expect(String(err.message)).not.toMatch(/鉴权失败|密码错误/)
    expect(page.clicks).toBe(3)
  })

  it("原因①′：请求已发出但无响应——点名后端，且**不重试**（重试等于重复提交）", async () => {
    const page = fakePage({ requestWithoutResponse: true })
    const err = await loginViaForm(asPage(page), 'e2e_operator', 'pwd').catch((e: Error) => e)
    expect(String(err.message)).toMatch(/请求已发出/)
    expect(String(err.message)).toMatch(/后端未就绪|挂住/)
    expect(page.clicks).toBe(1) // 关键：不重试
  })

  it('原因②-429：报错带状态码与限流成因（同 IP 共用 login 桶）', async () => {
    const page = fakePage({ status: 429, body: 'Too Many Requests' })
    await expect(loginViaForm(asPage(page), 'admin', 'admin123')).rejects.toThrow(
      /登录被拒（admin）：HTTP 429.*限流/s,
    )
  })

  it('原因②-401：报错带状态码与响应体片段，且不冤枉成限流', async () => {
    const page = fakePage({ status: 401, body: '{"code":40100,"message":"用户名或密码错误"}' })
    const err = await loginViaForm(asPage(page), 'admin', 'wrong').catch((e: Error) => e)
    expect(String(err.message)).toContain('HTTP 401')
    expect(String(err.message)).toContain('用户名或密码错误')
    expect(String(err.message)).not.toContain('限流')
  })

  it('原因③：登录 2xx 但 token 未落盘——点出"会被守卫弹回"这层因果', async () => {
    const page = fakePage({ status: 200, token: null })
    await expect(loginViaForm(asPage(page), 'admin', 'admin123')).rejects.toThrow(/token 未落盘/)
    await expect(
      loginViaForm(asPage(fakePage({ status: 200, token: null })), 'admin', 'admin123'),
    ).rejects.toThrow(/守卫弹回/)
  })

  it('监听器收尾：无论成败都 off 掉 request 监听（否则跨用例会互相污染）', async () => {
    const page = fakePage({ status: 200, token: 'tok-abc' })
    await loginViaForm(asPage(page), 'admin', 'admin123')
    expect(page.on).toHaveBeenCalledWith('request', expect.any(Function))
    expect(page.off).toHaveBeenCalledWith('request', expect.any(Function))
    expect(page.off.mock.calls[0][1]).toBe(page.on.mock.calls[0][1]) // 摘掉的正是挂上的那个
  })
})
