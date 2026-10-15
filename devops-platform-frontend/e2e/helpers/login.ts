import { expect, type Page, type Request, type Response } from '@playwright/test'

const LOGIN_URL_PART = '/api/v1/auth/login'
/** 单次点击后等响应的时长。比原来的 15s 短，好让「点击落空」早点进入重试。 */
const PER_ATTEMPT_MS = 8_000
const CLICK_ATTEMPTS = 3

/**
 * 表单登录（e2e 公用），并把「停在 /login」的几种互不相同的原因分开点名。
 *
 * ── 为什么要这么写（2026-10-15） ──────────────────────────────────────
 * 原版只有最后一句 `expect(page).toHaveURL(/\/$/)`。CI 上它红过一次
 * （run 38660040500 / a0969fc：`expected /localhost:5173\/$/ received /login`），
 * 而同一 commit 的 PR 腿 58 passed——即偶发。麻烦的是：几种完全不同的故障
 * 在这条断言里长得一模一样，而托管 runner 的作业日志与截图/录像都在沙箱
 * egress 不通的域上（`gh run download` → productionresultssa0.blob.core.windows.net
 * EOF，已实测），于是那次红案**无法定性**，只能猜。
 *
 * ── 为什么还要自己重试点击（2026-10-15 第二轮红案） ────────────────────
 * 加了诊断之后，main 上（run 38679023602）这条报错第一次自己说出了成因：
 * 「点击后 15s 内没有 /api/v1/auth/login 响应——按钮在 boot 期重渲染导致点击落空」。
 * 也就是说诊断生效了，但**故障本身还在**：本页 boot 期会异步写 html 的
 * data-theme / data-radius / data-density，按钮被重渲染时 click 可能落在已失效的
 * 节点上——Playwright 的 actionability 检查在点击那一刻是通过的，它无从知道这次
 * 点击没生效，**因此不会自动重试**。所以重试得由我们来做。
 *
 * 纪律：**只在「请求确实没发出去」时重试**。请求已经发出去却没响应，那是后端
 * 那一层的事，再点一次只会重复提交（还会多打一次 login 限流桶）。
 * 每次重试都重填表单——重渲染会把绑定换到新节点，旧节点里填的值可能已随节点丢弃。
 *
 * 失败原因（各自报错文本不同）：
 *   ① 提交没变成请求：点了 CLICK_ATTEMPTS 次都没发出请求（boot 期重渲染吞掉点击）
 *      ——与鉴权无关。
 *   ①′ 请求发出了但一直没响应：后端未就绪/挂住——与前端点击无关。
 *   ② 请求发了但非 2xx：口令错、后端未就绪、或 429（e2e 全部请求同源同 IP，
 *      共用 login 规则那只桶 10/60s，见 src/main/resources/application.yml）。
 *   ③ 登录 2xx 却仍没落盘 token：后续受保护路由会被守卫弹回
 *      （restoreSession 未确立登录态时守卫会弹登录提示，见 src/router/index.ts
 *      的「启动竞态」注释）。
 *
 * 纪律：断言一条不放宽（该跳 / 就必须跳 /、该有 token 就必须有 token），
 * 只是把现场带进报错——断言失败时必须能一眼看出该去查哪一层。
 */
export const submitLoginForm = async (
  page: Page,
  username: string,
  password: string,
  // 有些用例（如错误凭据）本就期待非 2xx，不该由这一层断言，故只负责「点到发出请求为止」
) => {
  let requestSeen = false
  const onRequest = (req: Request) => {
    if (req.url().includes(LOGIN_URL_PART)) requestSeen = true
  }
  page.on('request', onRequest)
  try {
    for (let attempt = 1; attempt <= CLICK_ATTEMPTS; attempt += 1) {
      requestSeen = false
      await page.locator('input[placeholder="用户名"]').fill(username)
      await page.locator('input[placeholder="密码"]').fill(password)

      // 先挂监听再点击，否则早返回的响应会被漏掉
      const respPromise = page
        .waitForResponse((r: Response) => r.url().includes(LOGIN_URL_PART), {
          timeout: PER_ATTEMPT_MS,
        })
        .catch(() => null)
      await page.locator('button[type="submit"].login-btn').click()
      const resp = await respPromise

      if (resp) return { resp, attempts: attempt, requestSeen: true }
      // 请求已出门却没响应 → 不是点击问题，重试无益（且会重复提交）
      if (requestSeen) return { resp: null, attempts: attempt, requestSeen }
    }
    return { resp: null, attempts: CLICK_ATTEMPTS, requestSeen }
  } finally {
    page.off('request', onRequest)
  }
}

export const loginViaForm = async (page: Page, username: string, password: string) => {
  await page.goto('/login', { waitUntil: 'domcontentloaded' })
  await page.evaluate(() => localStorage.clear())
  await page.reload({ waitUntil: 'domcontentloaded' })

  const { resp, attempts, requestSeen } = await submitLoginForm(page, username, password)

  if (!resp) {
    throw new Error(
      requestSeen
        ? `登录请求已发出但 ${PER_ATTEMPT_MS}ms 内没有响应（${username}）：` +
          '后端未就绪或挂住（原因①′）——与前端点击无关，未重试以免重复提交'
        : `登录提交未产生请求（${username}）：点击 ${attempts} 次都没有发出 ${LOGIN_URL_PART}` +
          '——按钮在 boot 期重渲染导致点击落空（原因①），与鉴权无关',
    )
  }
  if (resp.status() >= 400) {
    const body = (await resp.text().catch(() => '')).slice(0, 300)
    throw new Error(
      `登录被拒（${username}）：HTTP ${resp.status()}` +
        (resp.status() === 429
          ? '（限流：e2e 所有请求同源同 IP、共用 login 桶；配额由 LOGIN_RATE_LIMIT_PERMITS 抬过，仍撞 429 说明配额没生效）'
          : '') +
        `｜响应=${body}`,
    )
  }

  await expect(page).toHaveURL(/localhost:5173\//, { timeout: 15_000 })

  const token = await page.evaluate(() => localStorage.getItem('opsbrain-token'))
  if (!token) {
    throw new Error(`登录 2xx 且已跳转但 token 未落盘（${username}）：后续受保护路由会被守卫弹回`)
  }
}
