import { test, expect } from '@playwright/test'

/**
 * AI 助手页冒烟（R0-2）：导航可达性 + 空态 + 输入校验。
 *
 * 刻意不真发 LLM 提问——本机 AI_MODE=REAL 下每次提问消耗真实 API 成本；
 * SSE 事件编排由 useAiAssistant 单测（mock 边界）保护，这里只验装配：
 * 路由/导航/渲染/禁用态这些「组件间」行为。
 */
test.describe('AI 助手页', () => {
  test('侧栏出现「AI 助手」入口并可进入', async ({ page }) => {
    await page.goto('/', { waitUntil: 'domcontentloaded' })
    const link = page.getByRole('link', { name: /AI 助手/ }).first()
    await expect(link).toBeVisible()
    await link.click()
    await expect(page).toHaveURL(/\/assistant$/, { timeout: 15_000 })
    await expect(page.locator('h1.page-title')).toContainText('AI 助手')
  })

  test('空态显示能力说明与预置问题（不点击：点击=真实 LLM 调用）', async ({ page }) => {
    await page.goto('/assistant', { waitUntil: 'domcontentloaded' })
    await expect(page.locator('.empty-title')).toContainText('问我任何运维问题')
    const suggests = page.locator('.suggest-btn')
    await expect(suggests).toHaveCount(3)
    // 只验按钮存在与可点状态，不点击（点击=真实 LLM 调用）
    await expect(suggests.first()).toBeEnabled()
  })

  test('空输入时发送按钮禁用；输入后恢复可用', async ({ page }) => {
    await page.goto('/assistant', { waitUntil: 'domcontentloaded' })
    const sendBtn = page.locator('.composer-send')
    await expect(sendBtn).toBeDisabled()

    await page.locator('.composer-input').fill('测试输入')
    await expect(sendBtn).toBeEnabled()
  })

  test('新会话按钮清空消息列表', async ({ page }) => {
    await page.goto('/assistant', { waitUntil: 'domcontentloaded' })
    // 无消息时点击不报错、列表保持空态（装配级断言）
    await page.locator('.btn-new').click()
    await expect(page.locator('.empty-title')).toBeVisible()
  })
})
