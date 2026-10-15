import { expect, type Page } from '@playwright/test'

/**
 * 表单登录（e2e 公用），并把「停在 /login」的三种互不相同的原因分开点名。
 *
 * ── 为什么要这么写（2026-10-15） ──────────────────────────────────────
 * 原版只有最后一句 `expect(page).toHaveURL(/\/$/)`。CI 上它红过一次
 * （run 38660040500 / a0969fc：`expected /localhost:5173\/$/ received /login`），
 * 而同一 commit 的 PR 腿 58 passed——即偶发。麻烦的是：三种完全不同的故障
 * 在这条断言里长得一模一样，而托管 runner 的作业日志与截图/录像都在沙箱
 * egress 不通的域上（`gh run download` → productionresultssa0.blob.core.windows.net
 * EOF，已实测），于是那次红案**无法定性**，只能猜。
 *
 * 三种原因（现在各自报错文本不同）：
 *   ① 提交没变成请求：本页 boot 期会异步写 html 的 data-theme/data-radius/
 *      data-density 等属性，按钮被重渲染时 click 可能落在失效节点上
 *      （Playwright 不知道这次点击没生效，不会重试）——与鉴权无关。
 *   ② 请求发了但非 2xx：口令错、后端未就绪、或 429（e2e 全部请求同源同 IP，
 *      共用 login 规则那只桶 10/60s，见 src/main/resources/application.yml）。
 *   ③ 登录 2xx 却仍没落盘 token：后续受保护路由会被守卫弹回
 *      （restoreSession 未确立登录态时守卫会弹登录提示，见 src/router/index.ts
 *      的「启动竞态」注释）。
 *
 * 纪律：断言一条不放宽（该跳 / 就必须跳 /、该有 token 就必须有 token），
 * 只是把现场带进报错——断言失败时必须能一眼看出该去查哪一层。
 */
export const loginViaForm = async (page: Page, username: string, password: string) => {
  await page.goto('/login', { waitUntil: 'domcontentloaded' })
  await page.evaluate(() => localStorage.clear())
  await page.reload({ waitUntil: 'domcontentloaded' })
  await page.locator('input[placeholder="用户名"]').fill(username)
  await page.locator('input[placeholder="密码"]').fill(password)

  const respPromise = page
    .waitForResponse((r) => r.url().includes('/api/v1/auth/login'), { timeout: 15_000 })
    .catch(() => null)
  await page.locator('button[type="submit"].login-btn').click()
  const resp = await respPromise

  if (!resp) {
    throw new Error(
      `登录提交未产生请求（${username}）：点击后 15s 内没有 /api/v1/auth/login 响应` +
        '——按钮在 boot 期重渲染导致点击落空（原因①），与鉴权无关',
    )
  }
  if (resp.status() >= 400) {
    const body = (await resp.text().catch(() => '')).slice(0, 300)
    throw new Error(
      `登录被拒（${username}）：HTTP ${resp.status()}` +
        (resp.status() === 429 ? '（限流：同一 IP 共用 login 桶 10/60s）' : '') +
        `｜响应=${body}`,
    )
  }

  await expect(page).toHaveURL(/localhost:5173\/$/, { timeout: 15_000 })

  const token = await page.evaluate(() => localStorage.getItem('opsbrain-token'))
  if (!token) {
    throw new Error(`登录 2xx 且已跳转但 token 未落盘（${username}）：后续受保护路由会被守卫弹回`)
  }
}
