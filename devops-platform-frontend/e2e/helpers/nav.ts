import type { Page, Response } from '@playwright/test'

/**
 * 全 e2e 套件统一的导航入口。
 *
 * ── 为什么要有这么一层（2026-10-15 定案） ─────────────────────────────
 * 本应用**挂载即建 SSE / WebSocket 长连接**，于是 `'load'` 事件可能一直不触发
 * ——而 `page.goto()` 的默认 `waitUntil` 正是 `'load'`。后果有两个：
 *   1. 白等：每条用例都可能在 goto 上耗到 actionTimeout；
 *   2. **抢跑**：守卫在客户端做的跳转（未登录弹「请先登录」后回落首页等）
 *      与还在进行中的 goto 相撞，报 `net::ERR_ABORTED`。main 上偶发红的那一例
 *      （boundary-edge-cases.spec.ts:58）就是这个机制。
 *
 * 所以套件内一律走这里：只等 `domcontentloaded`（DOM 可交互即可，不等长连接）。
 * 同款说明见 e2e/auth.setup.ts。
 *
 * 纪律：**新用例不要再直接写 `page.goto(...)`**——`helpers/conventions.test.ts`
 * 会扫全目录，出现不带 domcontentloaded 的裸 goto 就红（该守卫本身做过变异验证）。
 */
export const gotoApp = async (page: Page, path: string): Promise<Response | null> =>
  page.goto(path, { waitUntil: 'domcontentloaded' })
