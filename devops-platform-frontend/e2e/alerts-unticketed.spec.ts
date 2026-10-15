import { test, expect, request } from '@playwright/test'
import type { APIRequestContext } from '@playwright/test'
import { gotoApp } from './helpers/nav'

/**
 * 「未建单」徽标的**浏览器级**证据（批106，销台账 §七 A5）。
 *
 * ── 为什么单测不够 ────────────────────────────────────────────────────
 * F2 的修法是「后端派生 `unticketedReason`、前端只负责显示」。这条链上有三段
 * 各自都绿、合起来仍可能断的地方：
 *   1. 后端只在「FIRING + 观察级 + 无工单 + 早于补单回溯窗 24h」四条同时成立时
 *      才派生文案（`AlertService.unticketedReason()`）——**任一前提不满足就返回
 *      null，页面什么也不显示**；
 *   2. `AlertRowBadges` 的 `v-if="unticketedReason"` 决定徽标是否渲染；
 *   3. `el-tooltip :content="unticketedReason"` 决定悬浮说明是不是后端原文。
 * `AlertRowBadges.test.ts` 钉住的是第 2、3 段（喂 props 进去看渲染），第 1 段
 * 由后端单测钉住——**但「真库里真有一条这样的告警时，页面上真的看得见」从来没
 * 被验过**。台账 §七 A5 挂的就是这笔账。
 *
 * ── 数据从哪来 ────────────────────────────────────────────────────────
 * `tools/seed/e2e-seed.sql` 第 5 件：一条 P2 / FIRING / 无工单、`last_occurred_at`
 * = now()（列表按它倒序 → 保证在第一页）、`first_occurred_at` = now()-26h（早于
 * 24h 回溯窗）的告警。形状即真实场景：Zabbix 侧网络恢复后重投的历史事件。
 *
 * ── 断言纪律 ──────────────────────────────────────────────────────────
 * 期望文案**不写死在用例里**，而是从 API 取后端的 `unticketedReason` 再比对：
 * 文案改了（比如回溯窗从 24h 调成 48h）用例要跟着变、而不是继续绿着骗人。
 *
 * 依赖 storageState（setup 项目产出的 admin 登录态）。
 */

const API = process.env.E2E_API ?? 'http://localhost:8088/ai'
const SEED_TITLE = 'E2E 种子告警：26 小时前的事件重投（不该再自动建单）'

interface AlertRow {
  id: number | string
  title?: string
  ticketId?: string | null
  unticketedReason?: string | null
}

/** 走后端登录拿 satoken（APIRequestContext，与浏览器 storageState 无关） */
async function apiCtx(): Promise<APIRequestContext> {
  const anon = await request.newContext()
  const r = await anon.post(`${API}/api/v1/auth/login`, {
    data: { username: 'admin', password: 'admin123' },
  })
  const body = await r.json()
  await anon.dispose()
  return request.newContext({ extraHTTPHeaders: { satoken: body.data.token } })
}

/** 第一页告警（与 UI 默认 size=10 同页，便于逐行对照） */
async function firstPage(size = 10): Promise<AlertRow[]> {
  const ctx = await apiCtx()
  const res = await ctx.get(`${API}/api/v1/alerts?page=1&size=${size}`)
  const body = await res.json()
  await ctx.dispose()
  return (body.data?.alerts ?? []) as AlertRow[]
}

test.describe('告警「未建单」标注（F2 · 浏览器级）', () => {
  test('回放来的老事件在列表上显式标「未建单」，悬浮说明逐字来自后端', async ({ page }) => {
    // ① 先问后端：这条种子现在到底该不该有说明（口径由后端说了算）
    const alerts = await firstPage(50)
    const seed = alerts.find((a) => a.title === SEED_TITLE)
    expect(seed, `种子告警应在第一页（种子把 last_occurred_at 设为 now()，列表按它倒序）`).toBeTruthy()
    expect(seed!.ticketId, '种子告警不该已建单——已建单就不需要解释').toBeFalsy()
    const reason = seed!.unticketedReason
    expect(typeof reason, '后端应派生 unticketedReason（四前提缺一即 null，页面就会什么都不说）').toBe('string')
    expect(reason!).toContain('不会再自动建单')

    // ② 浏览器里那一行必须真的挂上徽标
    await gotoApp(page, '/alerts')
    const link = page.locator('a.alert-title-link', { hasText: SEED_TITLE }).first()
    await expect(link).toBeVisible()
    const cell = link.locator('xpath=..')
    const badge = cell.locator('.unticketed-badge')
    await expect(badge).toBeVisible()
    await expect(badge).toHaveText('未建单')

    // ③ 悬浮说明 = 后端原文（el-tooltip 有 200ms show-after，等它真出现）
    await badge.hover()
    const tip = page.locator('.el-popper[role="tooltip"]', { hasText: '不会再自动建单' }).first()
    await expect(tip).toBeVisible()
    await expect(tip).toContainText(reason!)
  })

  test('第一页逐行对照：有徽标 ⇔ 后端给了 unticketedReason（含反向：已建单不该有）', async ({ page }) => {
    const alerts = await firstPage()
    const byId = new Map(alerts.map((a) => [String(a.id), a]))
    // 这一页里必须**同时**存在「该标」和「不该标」的行，否则本用例只是空跑
    const shouldMark = alerts.filter((a) => !!a.unticketedReason)
    const shouldNot = alerts.filter((a) => !a.unticketedReason)
    expect(shouldMark.length, '种子应让第一页至少有一行该标').toBeGreaterThan(0)
    expect(shouldNot.length, '第一页也该有不该标的行（否则反向断言没被行使）').toBeGreaterThan(0)

    await gotoApp(page, '/alerts')
    const links = page.locator('a.alert-title-link')
    await expect(links.first()).toBeVisible()
    const n = await links.count()
    expect(n, '列表应渲染出行').toBeGreaterThan(0)

    let checked = 0
    for (let i = 0; i < n; i++) {
      const href = await links.nth(i).getAttribute('href')
      const id = String(href ?? '').split('/').pop() ?? ''
      const api = byId.get(id)
      if (!api) continue // 分页/刷新导致的错位行，跳过而不是误判
      const hasBadge = (await links.nth(i).locator('xpath=..').locator('.unticketed-badge').count()) > 0
      expect(
        hasBadge,
        `行 ${id}（${api.title ?? ''}）徽标与后端 unticketedReason 不一致：` +
          `后端=${JSON.stringify(api.unticketedReason)} 页面徽标=${hasBadge}`,
      ).toBe(!!api.unticketedReason)
      checked++
    }
    expect(checked, '应至少对照上若干行（对照 0 行等于没测）').toBeGreaterThan(0)
  })
})
