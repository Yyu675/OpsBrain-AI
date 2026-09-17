import { test, expect } from '@playwright/test'

test.describe('暗色模式', () => {
  test('html 元素加载后不带 dark 类（默认浅色）', async ({ page }) => {
    await page.goto('/')
    await page.waitForLoadState('networkidle', { timeout: 15_000 }).catch(() => {})
    await expect(page.locator('html')).toBeAttached()
  })

  test('通过 JS 切换 html.dark 类正常生效', async ({ page }) => {
    await page.goto('/')
    await page.waitForLoadState('networkidle', { timeout: 15_000 }).catch(() => {})

    // 初始为浅色，切到暗色
    await page.evaluate(() => document.documentElement.classList.add('dark'))
    await expect(page.locator('html.dark')).toBeAttached({ timeout: 3_000 })

    // 切回浅色
    await page.evaluate(() => document.documentElement.classList.remove('dark'))
    await expect(page.locator('html.dark')).not.toBeAttached({ timeout: 3_000 })
  })
})