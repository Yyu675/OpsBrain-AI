import { test } from '@playwright/test'
import { mkdirSync } from 'node:fs'

/**
 * 暗色主题验收走查（任务「暗色主题验收」）：强制 html.dark 截屏关键页。
 *
 * 用途不是断言（视觉对比度无法断言），是**产出截图供人工/AI 走查**——
 * 逐页强制 html.dark，全页截图落到 e2e/.artifacts/dark/，再逐张核对
 * 对比度与令牌（--surface/--text 是否整套切换、有没有漏迁的旧 --color-* 残留）。
 *
 * 跑一次即可：`pnpm exec playwright test e2e/visual-dark.spec.ts`
 */
const PAGES = [
  { path: '/', name: 'home' },
  { path: '/tickets', name: 'tickets' },
  { path: '/alerts', name: 'alerts' },
  { path: '/disposal', name: 'disposal' },
  { path: '/monitoring', name: 'monitoring' },
  { path: '/effectiveness', name: 'effectiveness' },
]

test.describe('暗色主题截屏走查', () => {
  test('关键页强制 html.dark 全页截图', async ({ page }) => {
    const outDir = 'e2e/.artifacts/dark'
    mkdirSync(outDir, { recursive: true })

    for (const p of PAGES) {
      await page.goto(p.path)
      await page.waitForLoadState('networkidle', { timeout: 20_000 }).catch(() => {})
      // 强制暗色（绕过系统偏好，直接钉死验收态）
      await page.evaluate(() => document.documentElement.classList.add('dark'))
      // 给令牌切换/图表重绘留一帧
      await page.waitForTimeout(1200)
      await page.screenshot({ path: `${outDir}/${p.name}.png`, fullPage: true })
    }
  })
})
