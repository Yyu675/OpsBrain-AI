import { test, expect, type Page } from '@playwright/test'

/**
 * 鉴权与路由守卫 E2E（批88 P2）
 *
 * 覆盖 vitest 组件测试覆盖不到的「真实浏览器」鉴权链路。
 * 断言全部对照真实实现（router/index.ts beforeEach + Login.vue + stores/app.ts）：
 *
 * - 受保护页未登录 → 路由守卫弹 ElMessageBox「请先登录」（非静默重定向）：
 *   确认「前往登录」→ /login?redirect=来源页；取消 → 回落首页（初始导航无页可留）
 * - /login 表单提交 → app.login 写 token(opsbrain-token) → replace 到 redirect 目标
 * - 错误凭据 → handleServerError 提示，停留登录页且不写 token
 * - 直接落地登录页（非重定向）登录 → 跳首页 '/'
 *
 * 已知实现行为（如实断言，非缺陷掩盖）：/login 是 public 路由，守卫直接放行、
 * 不做 restoreSession，Login.vue onMounted 只看内存态 isAuthenticated——
 * 因此「带 token 直达 /login」不会自动跳走（只有经守卫的非公开路由才恢复会话）。
 *
 * 账号：admin/admin123（Login.vue 登录提示中明示的默认账号）。
 */

const TOKEN_KEY = 'opsbrain-token'

/** 在登录页完成一次真实登录（表单流），返回后 token 已写入 */
async function loginViaForm(page: Page, user = 'admin', pass = 'admin123') {
  await page.locator('input[placeholder="用户名"]').fill(user)
  await page.locator('input[placeholder="密码"]').fill(pass)
  await page.locator('button[type="submit"].login-btn').click()
}

test.describe('鉴权与路由守卫', () => {
  test.beforeEach(async ({ page }) => {
    // 每个用例从干净 localStorage 开始（避免上一用例登录态泄漏）
    await page.addInitScript((key) => localStorage.removeItem(key), TOKEN_KEY)
  })

  test('未登录访问受保护页 → 弹「请先登录」→ 前往登录带 redirect', async ({ page }) => {
    await page.goto('/tickets')

    // 守卫弹 ElMessageBox 确认框（非静默重定向——用户决定去向）
    const box = page.locator('.el-message-box')
    await expect(box).toBeVisible({ timeout: 15_000 })
    await expect(box).toContainText('需要先登录')

    await box.locator('button', { hasText: '前往登录' }).click()
    // 实测：query 值不做百分号编码（page.url() 返回 redirect=/tickets）
    await expect(page).toHaveURL(/\/login\?redirect=\/tickets/)
    await expect(page.locator('input[placeholder="用户名"]')).toBeVisible()
  })

  test('未登录访问受保护页 → 留在当前页 → 初始导航回落首页', async ({ page }) => {
    await page.goto('/tickets')

    const box = page.locator('.el-message-box')
    await expect(box).toBeVisible({ timeout: 15_000 })
    await box.locator('button', { hasText: '留在当前页' }).click()

    // 初始导航（直接输地址）没有"当前页"可留 → 守卫回落首页，不白屏
    await expect(page).toHaveURL(/localhost:5173\/$/)
  })

  test('redirect 回跳：登录成功写入 token 并回到来源页', async ({ page }) => {
    await page.goto('/tickets')
    const box = page.locator('.el-message-box')
    await expect(box).toBeVisible({ timeout: 15_000 })
    await box.locator('button', { hasText: '前往登录' }).click()
    await expect(page).toHaveURL(/\/login\?redirect=/)

    await loginViaForm(page)

    // 回跳来源页 + token 落 localStorage（app.login → setAuthToken）
    await expect(page).toHaveURL(/\/tickets/, { timeout: 15_000 })
    await expect
      .poll(async () => page.evaluate((k) => localStorage.getItem(k), TOKEN_KEY))
      .not.toBeNull()
  })

  test('错误密码：停留登录页且不写 token', async ({ page }) => {
    await page.goto('/login')
    await loginViaForm(page, 'admin', 'wrong-password-xyz')

    await expect(page).toHaveURL(/\/login/)
    const token = await page.evaluate((k) => localStorage.getItem(k), TOKEN_KEY)
    expect(token).toBeNull()
  })

  test('直接落地登录页：登录成功跳首页', async ({ page }) => {
    await page.goto('/login')
    await loginViaForm(page)

    // 无 redirect 参数 → redirectTarget() 回落 '/'
    await expect(page).toHaveURL(/localhost:5173\/$/, { timeout: 15_000 })
    await expect
      .poll(async () => page.evaluate((k) => localStorage.getItem(k), TOKEN_KEY))
      .not.toBeNull()
  })
})
