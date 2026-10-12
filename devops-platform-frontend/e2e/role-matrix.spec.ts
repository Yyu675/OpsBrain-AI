import { test, expect, type Page } from '@playwright/test'

/**
 * 角色权限矩阵走查（R0-3）
 *
 * 保护的真实行为：非管理员登录后——
 * 1. 导航不再出现 admin-only 入口（处置中心），直接敲 URL 被守卫拦到 403；
 * 2. viewer（只读账号）看到的关键写按钮呈禁用态（v-permission disable 模式），
 *    而不是"能点下去再吃一个 403"；
 * 3. operator（OPS）的常规写入口保持可用（门控不误伤）。
 *
 * 账号前提：dev 库种子 e2e_operator(OPS)/e2e_viewer(VIEWER)——库重建后需重放
 * 种子 SQL（见 PROGRESS/报告记录；INSERT 幂等，ON CONFLICT 可重复执行）。
 * 后端实况备忘：viewer 在后端无写守卫（工单/告警写仅校验登录），
 * 本套件断言的是**前端防线**；后端补齐 viewer 守卫前不要据此以为服务端也拦了。
 */

const loginAs = async (page: Page, username: string, password: string) => {
  await page.goto('/login', { waitUntil: 'domcontentloaded' })
  await page.evaluate(() => localStorage.clear())
  await page.reload({ waitUntil: 'domcontentloaded' })
  await page.locator('input[placeholder="用户名"]').fill(username)
  await page.locator('input[placeholder="密码"]').fill(password)
  await page.locator('button[type="submit"].login-btn').click()
  await expect(page).toHaveURL(/localhost:5173\/$/, { timeout: 15_000 })
}

test.describe('角色权限矩阵', () => {
  test.describe('operator（OPS 运维工程师）', () => {
    test.beforeEach(async ({ page }) => {
      await loginAs(page, 'e2e_operator', 'E2E_OP_Pwd2026!')
    })

    test('导航无处置中心入口；直达 /disposal 被拦到 403', async ({ page }) => {
      await expect(page.getByRole('link', { name: /处置中心/ })).toHaveCount(0)

      await page.goto('/disposal', { waitUntil: 'domcontentloaded' })
      await expect(page.locator('h1')).toContainText('403', { timeout: 15_000 })
    })

    test('常规写入口可用：工单创建按钮未因权限被禁', async ({ page }) => {
      await page.goto('/tickets', { waitUntil: 'domcontentloaded' })
      const createBtn = page.locator('button.btn-create')
      await expect(createBtn).toBeVisible()
      await expect(createBtn).toBeEnabled()
    })
  })

  test.describe('viewer（只读账号）', () => {
    test.beforeEach(async ({ page }) => {
      await loginAs(page, 'e2e_viewer', 'E2E_VW_Pwd2026!')
    })

    test('导航无处置中心；/disposal 与写操作面被门控', async ({ page }) => {
      await expect(page.getByRole('link', { name: /处置中心/ })).toHaveCount(0)

      await page.goto('/disposal', { waitUntil: 'domcontentloaded' })
      await expect(page.locator('h1')).toContainText('403', { timeout: 15_000 })
    })

    test('工单创建按钮呈权限禁用态（不是能点下去吃 403）', async ({ page }) => {
      await page.goto('/tickets', { waitUntil: 'domcontentloaded' })
      const createBtn = page.locator('button.btn-create')
      await expect(createBtn).toBeVisible()
      // v-permission.disable：disabled 属性 + 提示 title 由指令自己加，
      // 与业务禁用态互不覆盖
      await expect(createBtn).toBeDisabled()
      await expect(createBtn).toHaveAttribute('title', '无操作权限')
    })

    test('知识库写入口（上传/新建文档）呈权限禁用态', async ({ page }) => {
      await page.goto('/knowledge', { waitUntil: 'domcontentloaded' })
      const uploadBtn = page.getByRole('button', { name: /上传文档/ })
      await expect(uploadBtn).toBeDisabled()
      const createBtn = page.getByRole('button', { name: /新建文档/ })
      await expect(createBtn).toBeDisabled()
    })

    test('读路径不受门控影响：告警列表正常渲染', async ({ page }) => {
      await page.goto('/alerts', { waitUntil: 'domcontentloaded' })
      await expect(page.locator('h1.page-title')).toContainText('告警')
      // 只读账号也应有处置按钮的"可见但禁用"形态（有数据行时）而非整列消失
      const ackBtn = page.locator('.action-btn-primary').first()
      if (await ackBtn.count()) {
        await expect(ackBtn).toHaveAttribute('aria-disabled', 'true')
      }
    })
  })
})
