import { test, expect } from '@playwright/test'

/**
 * 全站导航冒烟 E2E（批88 P2）
 *
 * 用 admin 登录态逐页访问主要路由，断言：
 * - 页面标题渲染（组件真实挂载，不是路由空壳）
 * - 无未捕获控制台错误（数据契约断裂最常以 console error 形式先暴露）
 *
 * 顺带在浏览器里验证批88-I 的 SagaCompensation DataStateBoundary 改造
 * （四态组件替换内联 state-box 后页面仍正常渲染）。
 */

const PAGES: Array<{ path: string; title: string }> = [
  { path: '/knowledge', title: '知识库' },
  { path: '/alerts', title: '告警事件' },
  { path: '/dashboard', title: '' }, // 看板标题为图表区，仅验证无致命错误
  { path: '/governance/saga-compensation', title: 'Saga 补偿中心' },
  { path: '/approvals', title: '' },
  { path: '/help', title: '' },
]

test.describe('全站导航冒烟', () => {
  for (const p of PAGES) {
    test(`访问 ${p.path} 渲染正常且无控制台错误`, async ({ page }) => {
      const errors: string[] = []
      page.on('console', (m) => {
        // 只收 error 级；vite HMR/sourcemap 噪音排除
        if (m.type() === 'error' && !m.text().includes('favicon')) errors.push(m.text())
      })
      page.on('pageerror', (e) => errors.push(String(e)))

      await page.goto(p.path)

      if (p.title) {
        await expect(page.locator('.page-title', { hasText: p.title }).first()).toBeVisible({ timeout: 30_000 })
      } else {
        // 无标题断言的页面：等网络空闲 + 主体挂载
        await page.waitForLoadState('networkidle', { timeout: 15_000 }).catch(() => {})
        await expect(page.locator('#app')).not.toBeEmpty()
      }

      expect(errors, `控制台错误:\n${errors.join('\n')}`).toHaveLength(0)
    })
  }

  test('未匹配路由落 404 页', async ({ page }) => {
    await page.goto('/definitely-not-a-route')
    await expect(page.locator('#app')).toContainText(/404|未找到|不存在/, { timeout: 10_000 })
  })
})
