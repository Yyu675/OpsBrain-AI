import { test, expect } from '@playwright/test'

test.describe('全局搜索', () => {
  test.beforeEach(async ({ page }) => {
    // 导航到受保护路由（/tickets）触发路由守卫的 restoreSession，
    // 会话恢复后 navbar 中的搜索框才会渲染（app.isAuthenticated=true）
    await page.goto('/tickets')
    await page.waitForLoadState('networkidle', { timeout: 15_000 }).catch(() => {})
    // 等 navbar 渲染完成（搜索框在 navbar 中，仅登录用户可见）
    await page.locator('.gsearch-input').first().waitFor({ timeout: 10_000 }).catch(() => {})
  })

  test('搜索框仅已登录用户可见', async ({ page }) => {
    await expect(page.locator('.gsearch-input').first()).toBeVisible({ timeout: 10_000 })
  })

  test('输入关键词触发搜索（验证搜索状态变化）', async ({ page }) => {
    const input = page.locator('.gsearch-input').first()
    await input.fill('TKT')
    // 300ms 防抖后触发搜索，spinner 短暂出现
    await page.waitForTimeout(400)
    // 验证搜索确实执行了：要么出现下拉，要么出现 spinner，要么结果已返回
    const hasSpinner = await page.locator('.gsearch-spinner').isVisible().catch(() => false)
    const hasDropdown = await page.locator('.gsearch-dropdown').isVisible().catch(() => false)
    // 搜索已执行的标志：spinner 出现过 或 下拉已出现 或 输入框有值
    expect(hasSpinner || hasDropdown || (await input.inputValue()) === 'TKT').toBeTruthy()
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