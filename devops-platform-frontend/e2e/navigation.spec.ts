import { test, expect } from '@playwright/test'

/**
 * 全站导航冒烟 E2E（批88 P2）
 *
 * 用 admin 登录态逐页访问主要路由，断言：
 * - 页面标题渲染（组件真实挂载，不是路由空壳）
 * - 无未捕获控制台错误（数据契约断裂最常以 console error 形式先暴露）
 */

const PAGES: Array<{ path: string; title: string }> = [
  { path: '/', title: '' }, // 首页=值班工作台（Dashboard 内容），无 .page-title 约定
  { path: '/knowledge', title: '知识库' },
  { path: '/alerts', title: '告警事件' },
  { path: '/monitoring', title: '监控中心' },
  { path: '/settings', title: '' }, // 标签页（默认「常规」），无 .page-title 约定
  { path: '/disposal', title: '' }, // 处置中心（审批+自愈标签页），无 .page-title 约定
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

  // 2026-09-26 信息架构整合：旧路由全部重定向，分享出去的链接不能死
  test('旧路由 /trends 重定向到监控中心并保留筛选参数', async ({ page }) => {
    await page.goto('/trends?range=7d&metric=memory.usage')
    await expect(page).toHaveURL(/\/monitoring\?range=7d&metric=memory\.usage/)
    await expect(page.locator('.page-title', { hasText: '监控中心' })).toBeVisible({ timeout: 30_000 })
  })

  test('治理旧路由重定向到设置页对应标签', async ({ page }) => {
    await page.goto('/model-channels')
    await expect(page).toHaveURL(/\/settings\?tab=model-channels/)

    await page.goto('/governance/audit-logs')
    await expect(page).toHaveURL(/\/settings\?tab=audit-logs/)
  })

  // 2026-09-27 二次收敛：数据概览升格首页、审批/自愈合并处置中心
  test('旧路由重定向：/dashboard 与审批/自愈入口', async ({ page }) => {
    await page.goto('/dashboard')
    await expect(page).toHaveURL(/\/$/)

    await page.goto('/approvals')
    await expect(page).toHaveURL(/\/disposal\?tab=approvals/)

    await page.goto('/self-healing/tasks')
    await expect(page).toHaveURL(/\/disposal\?tab=healing/)
  })
})
