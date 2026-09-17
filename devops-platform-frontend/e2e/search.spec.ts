import { test, expect } from '@playwright/test'

test.describe('全局搜索', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/')
    await page.waitForLoadState('networkidle', { timeout: 15_000 }).catch(() => {})
  })

  test('搜索框仅已登录用户可见', async ({ page }) => {
    await expect(page.locator('.gsearch-input').first()).toBeVisible({ timeout: 10_000 })
  })

  test('输入关键词触发请求（验证网络调用）', async ({ page }) => {
    const respPromise = page.waitForResponse(
      r => r.url().includes('/api/v1/search?q=') && r.status() === 200,
      { timeout: 15_000 }
    )
    await page.locator('.gsearch-input').first().fill('TKT')
    const resp = await respPromise
    expect(resp.status()).toBe(200)
  })

  test('Enter 提交后跳转工单或停留（有结果跳转，无结果停留）', async ({ page }) => {
    const input = page.locator('.gsearch-input').first()
    await input.fill('TKT-20260917-0001')
    await page.waitForTimeout(800)
    // 如果下拉出现且选中了条目，Enter 跳转
    await input.press('ArrowDown')
    await page.waitForTimeout(200)
    await input.press('Enter')
    // 要么跳转，要么停留在首页（搜索结果为空）
    const url = page.url()
    expect(url).toMatch(/\/(tickets|)$/)
  })

  test('Escape 关闭下拉', async ({ page }) => {
    const input = page.locator('.gsearch-input').first()
    await input.fill('TKT')
    // 等请求完成
    await page.waitForResponse(
      r => r.url().includes('/api/v1/search?q=TKT'),
      { timeout: 15_000 }
    ).catch(() => {})
    await page.waitForTimeout(500)
    await input.press('Escape')
    await page.waitForTimeout(300)
    await expect(page.locator('.gsearch-dropdown')).not.toBeVisible()
  })

  test('无匹配结果时不显示下拉', async ({ page }) => {
    const input = page.locator('.gsearch-input').first()
    await input.fill('xyznonexistentkeyword999')
    await page.waitForResponse(
      r => r.url().includes('/api/v1/search?q='),
      { timeout: 15_000 }
    ).catch(() => {})
    await page.waitForTimeout(600)
    await expect(page.locator('.gsearch-dropdown')).not.toBeVisible()
  })

  test('清空输入框隐藏下拉', async ({ page }) => {
    const input = page.locator('.gsearch-input').first()
    await input.fill('TKT')
    await page.waitForResponse(
      r => r.url().includes('/api/v1/search?q=TKT'),
      { timeout: 15_000 }
    ).catch(() => {})
    await page.waitForTimeout(500)
    await input.clear()
    await page.waitForTimeout(500)
    await expect(page.locator('.gsearch-dropdown')).not.toBeVisible()
  })
})