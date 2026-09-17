import { test as setup, expect } from '@playwright/test'

/**
 * 全局登录态制备：真实表单登录一次，把 localStorage（token）存为
 * storageState 供后续用例复用——避免每个用例重复走登录流（省 ~2s/用例）。
 */
const AUTH_FILE = 'e2e/.auth/admin.json'

setup('authenticate as admin', async ({ page }) => {
  await page.goto('/login')
  await page.locator('input[placeholder="用户名"]').fill('admin')
  await page.locator('input[placeholder="密码"]').fill('admin123')
  await page.locator('button[type="submit"].login-btn').click()

  // 登录成功跳首页 + token 落盘
  await expect(page).toHaveURL(/localhost:5173\/$/, { timeout: 15_000 })
  await expect
    .poll(async () => page.evaluate(() => localStorage.getItem('opsbrain-token')))
    .not.toBeNull()

  await page.context().storageState({ path: AUTH_FILE })
})
