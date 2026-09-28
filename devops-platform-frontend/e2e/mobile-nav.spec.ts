import { test, expect } from '@playwright/test'

/**
 * 移动端窄视口走查（390px）——对应任务清单「移动端窄视口走查」。
 *
 * 侧栏布局壳（S1）后，≤768px 时桌面侧栏隐藏、导航收进汉堡抽屉。
 * 这里钉死三条窄屏契约：
 *   1. 汉堡按钮在窄屏出现、桌面侧栏隐藏（形态切换正确）；
 *   2. 抽屉能开、点导航项能跳转且抽屉自动关闭（useMobileNavState 的路由 watch）；
 *   3. 首页 390px 下无横向溢出（KPI 折行 / 表格不顶破视口）。
 *
 * 视口固定在 describe 级：三条用例共享同一窄屏前提，不必每条重复 setViewportSize。
 */
test.describe('移动端窄视口（390px）', () => {
  test.use({ viewport: { width: 390, height: 844 } })

  test('窄屏下显示汉堡、隐藏桌面侧栏', async ({ page }) => {
    await page.goto('/')
    await page.waitForLoadState('networkidle', { timeout: 15_000 }).catch(() => {})

    // 窄屏形态：汉堡接管导航，桌面侧栏整体隐藏
    await expect(page.locator('.mobile-nav-toggle')).toBeVisible({ timeout: 10_000 })
    await expect(page.locator('.sidebar')).toBeHidden()
  })

  test('汉堡开抽屉 → 点导航项跳转 → 抽屉自动关闭', async ({ page }) => {
    await page.goto('/')
    await page.waitForLoadState('networkidle', { timeout: 15_000 }).catch(() => {})

    // 开抽屉
    await page.locator('.mobile-nav-toggle').click()
    const drawer = page.locator('.mobile-nav-drawer.open')
    await expect(drawer).toBeVisible({ timeout: 5_000 })

    // 抽屉里点「告警事件」→ 跳转且抽屉关（路由变化触发 close）
    await page.locator('.mobile-nav-item', { hasText: '告警事件' }).click()
    await expect(page).toHaveURL(/\/alerts/, { timeout: 10_000 })
    await expect(page.locator('.mobile-nav-drawer.open')).toHaveCount(0)
  })

  test('首页 390px 无横向溢出（KPI/卡片折行，不顶破视口）', async ({ page }) => {
    await page.goto('/')
    await page.waitForLoadState('networkidle', { timeout: 15_000 }).catch(() => {})

    // 横向溢出 = scrollWidth 超过视口宽（留 2px 容差吃滚动条/四舍五入）
    const overflow = await page.evaluate(() =>
      document.documentElement.scrollWidth - document.documentElement.clientWidth
    )
    expect(overflow, `首页出现 ${overflow}px 横向溢出，窄屏需横滑`).toBeLessThanOrEqual(2)
  })
})
