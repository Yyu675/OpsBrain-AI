import { test, expect, type Page, request } from '@playwright/test'

/**
 * 工单列表与创建 E2E（批88 P2）
 *
 * 覆盖真实浏览器里的工单主链路：
 * - 列表页渲染（KPI 卡片 + 表格 + 服务端分页）
 * - 创建工单表单流（必填校验 → 提交 → 列表出现新单）
 * - 搜索过滤（服务端 keyword）
 * - 点击工单号进详情页
 *
 * 数据纪律：测试工单标题带 E2E_PW_ 前缀；afterEach 经后端 API 按前缀清理，
 * 不污染 dev 库（对齐 e2e-business-flow.sh 的自清理原则）。
 * 依赖 storageState（setup 项目产出的 admin 登录态）。
 */

const API = process.env.E2E_API ?? 'http://localhost:8088/ai'
const PREFIX = 'E2E_PW_'

async function apiToken(): Promise<string> {
  const ctx = await request.newContext()
  const r = await ctx.post(`${API}/api/v1/auth/login`, {
    data: { username: 'admin', password: 'admin123' },
  })
  const body = await r.json()
  await ctx.dispose()
  return body.data.token
}

/** 经 API 清理本轮 UI 创建的工单（后端 DELETE 走废弃语义，不物理删） */
async function cleanupTickets() {
  const token = await apiToken()
  const ctx = await request.newContext({ extraHTTPHeaders: { satoken: token } })
  // 列表响应结构是 data.tickets（实测），非 content/list
  const list = await (await ctx.get(`${API}/api/v1/tickets?page=1&size=50&keyword=${PREFIX}`)).json()
  for (const t of list.data?.tickets ?? []) {
    if (String(t.title ?? '').startsWith(PREFIX)) {
      await ctx.delete(`${API}/api/v1/tickets/${t.id}`).catch(() => {})
    }
  }
  await ctx.dispose()
}

test.describe('工单列表与创建', () => {
  test.afterEach(async () => {
    await cleanupTickets().catch(() => {})
  })

  test('列表页渲染：KPI 卡片与表格', async ({ page }) => {
    await page.goto('/tickets')
    await expect(page.locator('.page-title', { hasText: '智能工单' })).toBeVisible()
    await expect(page.locator('.kpi-card')).toHaveCount(4)
    // 表格有数据行（dev 库常驻 ≥1 张工单）
    await expect(page.locator('.el-table__row').first()).toBeVisible({ timeout: 15_000 })
  })

  test('创建工单：必填校验拦截空提交', async ({ page }) => {
    await page.goto('/tickets')
    await page.locator('.btn-create').click()

    const dialog = page.locator('.ticket-form-overlay, .form-dialog, [id="ticket-form-title"]')
    await expect(page.locator('#ticket-form-title', { hasText: '创建工单' })).toBeVisible()

    // 空表单直接提交 → 前端校验拦截，不发请求
    await page.locator('.dialog-footer .btn-primary').click()
    await expect(page.locator('#ticket-form-title')).toBeVisible() // 弹窗仍在
  })

  test('创建工单：完整表单流 → 新单出现在列表', async ({ page }) => {
    const title = `${PREFIX}${Date.now()} 磁盘告警演练`

    await page.goto('/tickets')
    await page.locator('.btn-create').click()
    await expect(page.locator('#ticket-form-title')).toBeVisible()

    await page.locator('input.form-input').first().fill(title)
    await page.locator('textarea.form-textarea').fill('Playwright E2E 创建：/var/log 占满磁盘，需清理轮转日志')

    // 提交前拦截 POST /tickets 拿新单号
    const respPromise = page.waitForResponse(
      (r) => r.url().includes('/api/v1/tickets') && r.request().method() === 'POST',
      { timeout: 20_000 },
    )
    await page.locator('.dialog-footer .btn-primary').click()
    const resp = await respPromise
    expect(resp.ok()).toBeTruthy()

    // 列表刷新后新单可见（store.fetchList 订阅链路——6.x 修复过的"创建成功但列表没有"）
    await expect(page.locator('.el-table__row', { hasText: title }).first()).toBeVisible({ timeout: 15_000 })
  })

  test('搜索过滤：keyword 走服务端', async ({ page }) => {
    await page.goto('/tickets')
    await expect(page.locator('.el-table__row').first()).toBeVisible({ timeout: 15_000 })

    const search = page.locator('input[placeholder*="搜索"], input.search-input').first()
    if (await search.isVisible()) {
      const respPromise = page.waitForResponse((r) => r.url().includes('/api/v1/tickets?'), { timeout: 10_000 })
      await search.fill('告警')
      await page.keyboard.press('Enter')
      const resp = await respPromise
      expect(resp.url()).toContain('keyword=')
    }
  })

  test('点击工单号进入详情页', async ({ page }) => {
    await page.goto('/tickets')
    const firstLink = page.locator('a.ticket-id').first()
    await expect(firstLink).toBeVisible({ timeout: 15_000 })
    const id = (await firstLink.textContent())!.trim()

    await firstLink.click()
    await expect(page).toHaveURL(new RegExp(`/tickets/${id.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}`))
  })
})
