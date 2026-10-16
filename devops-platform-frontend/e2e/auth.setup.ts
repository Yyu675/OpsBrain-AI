import { test as setup } from '@playwright/test'

import { loginViaForm } from './helpers/login'

/**
 * 全局登录态制备：真实表单登录一次，把 localStorage（token）存为
 * storageState 供后续用例复用——避免每个用例重复走登录流（省 ~2s/用例）。
 */
const AUTH_FILE = 'e2e/.auth/admin.json'

setup('authenticate as admin', async ({ page }) => {
  // 登录与「跳首页 + token 落盘」的断言统一走公用 helper：失败时报错自带现场
  // （未产生请求 / 非 2xx 带状态码 / token 未落盘），而不是只留一句 URL 不符。
  // 本应用挂载即建 SSE/WebSocket 长连接，'load' 事件可能一直不触发——helper 内
  // 一律用 domcontentloaded（登录表单可交互即可，不等长连接）。
  await loginViaForm(page, 'admin', 'admin123')

  await page.context().storageState({ path: AUTH_FILE })
})
