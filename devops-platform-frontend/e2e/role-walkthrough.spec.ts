/**
 * 角色走查（真实浏览器 + 真实后端）：按真实用户视角走核心链路，
 * 检查每页渲染、数据是否真实、交互是否顺。产出观察记录，不断言成败——
 * 目的是发现设计问题，不是守护回归。
 */
import { test, expect, type Page } from '@playwright/test'

const shot = async (page: Page, name: string) => {
  await page.screenshot({ path: `e2e/.walkthrough/${name}.png`, fullPage: false })
}

test.describe('值班工程师视角：告警到工单到诊断到知识', () => {
  test('告警列表 → 详情 → 关联工单', async ({ page }) => {
    await page.goto('/alerts', { waitUntil: 'domcontentloaded' })
    await expect(page.locator('h1')).toContainText('告警')
    await page.waitForTimeout(800)
    await shot(page, '01-alert-list')
    // 管道心跳是否在
    const heartbeat = page.locator('.summary-item', { hasText: '管道' })
    console.log('管道心跳可见:', await heartbeat.count() > 0)
    // 点第一条告警
    const firstRow = page.locator('tbody tr, .el-table__row').first()
    if (await firstRow.count() > 0) {
      await firstRow.click()
      await page.waitForTimeout(800)
      await shot(page, '02-alert-detail')
    }
  })

  test('工单列表 → 详情 → 诊断回放', async ({ page }) => {
    await page.goto('/tickets', { waitUntil: 'domcontentloaded' })
    await page.waitForTimeout(800)
    await shot(page, '03-ticket-list')
    const firstRow = page.locator('tbody tr, .el-table__row').first()
    if (await firstRow.count() > 0) {
      await firstRow.click()
      await page.waitForTimeout(1000)
      await shot(page, '04-ticket-detail')
      // 进度条与操作区
      const stages = page.locator('.cp-step')
      console.log('闭环步骤数:', await stages.count())
    }
  })

  test('效能大盘渲染', async ({ page }) => {
    await page.goto('/effectiveness', { waitUntil: 'domcontentloaded' })
    await page.waitForTimeout(1200)
    await shot(page, '05-effectiveness')
    console.log('效能大盘 KPI 数:', await page.locator('.kpi-card').count())
    console.log('管道卡数:', await page.locator('.pipeline-card').count())
  })
})
