import { beforeEach, describe, expect, it, vi } from 'vitest'

/**
 * `loginViaForm` 的**失败分支**单测。
 *
 * 为什么这件事值得单测（而不是等 e2e 腿去跑）：
 * helper 的价值全在「红的时候能不能一眼看出该查哪一层」，而那三条失败分支
 * 在 CI 的 e2e 腿里**永远走不到**——走到了就说明套件红了。也就是说，
 * 只有在这里用假 Page 驱动它们，诊断文本才算被验证过；否则「报错自带现场」
 * 只是一句没人跑过的承诺（2026-10-15 那次红案的教训正是：断言文本没带现场，
 * 而作业日志与截图都在沙箱读不到的域上，只能靠猜）。
 *
 * 覆盖的四条路径：正常 / ①点击落空 / ②非 2xx（429 与 401 分别点名）/ ③token 未落盘。
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

type Cfg = { status?: number; body?: string; noResponse?: boolean; token?: string | null }

function fakePage(cfg: Cfg) {
  const calls: string[] = []
  const locator = (sel: string) => ({
    fill: vi.fn(async (v: string) => {
      calls.push(`fill:${sel}=${v}`)
    }),
    click: vi.fn(async () => {
      calls.push(`click:${sel}`)
    }),
  })
  return {
    calls,
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
    waitForResponse: vi.fn(async () => {
      if (cfg.noResponse) throw new Error('Timeout 15000ms exceeded')
      return { status: () => cfg.status ?? 200, text: async () => cfg.body ?? '' }
    }),
    // 只读断言用：登录响应等待是否被挂上（点击前挂监听，才不会漏掉早返回的响应）
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
  })

  it('顺序纪律：先清 localStorage 再填表（否则可能拿上一个账号的登录态冒充）', async () => {
    const page = fakePage({})
    await loginViaForm(asPage(page), 'e2e_operator', 'E2E_OP_Pwd2026!')
    const order = page.calls.join(' → ')
    expect(order).toMatch(/goto:\/login → evaluate#1 → reload → fill:input\[placeholder="用户名"\]/)
    expect(order).toMatch(/fill:input\[placeholder="密码"\]=E2E_OP_Pwd2026!/)
  })

  it('原因①：点击落空（没产生登录请求）——报错点名"未产生请求"，不指向鉴权', async () => {
    const page = fakePage({ noResponse: true })
    await expect(loginViaForm(asPage(page), 'e2e_viewer', 'pwd')).rejects.toThrow(/未产生请求/)
    await expect(loginViaForm(asPage(fakePage({ noResponse: true })), 'e2e_viewer', 'pwd')).rejects.toThrow(
      /原因①/,
    )
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
    await expect(loginViaForm(asPage(fakePage({ status: 200, token: null })), 'admin', 'admin123')).rejects.toThrow(
      /守卫弹回/,
    )
  })
})
