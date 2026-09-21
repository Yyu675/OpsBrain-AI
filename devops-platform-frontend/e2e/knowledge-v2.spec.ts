/**
 * 知识库 V2（多知识库 + 文件上传）真机联调 E2E。
 *
 * 覆盖的契约：
 *  - 侧栏知识库选择器渲染真实库列表（默认库 + FAQ 库）
 *  - 选库筛选文档列表，且 ?kb= 写回 URL（可分享直达）
 *  - 页头提供「上传文档」「知识库管理」两个 V2 入口
 *  - 上传 Dialog 打开后有拖拽区与知识库选择器
 *  - 管理 Dialog 打开后列出库及其切片参数
 *
 * 前置：后端 8088 已跑 V2（存在 id=1 默认库与 id=2 faq 库），
 * 前端 5173 dev server 已启动。
 *
 * 注意断言作用域：侧栏与 Dialog 会出现同名文本（如库名），
 * 一律用 getByRole('dialog', ...) 收窄，避免 strict mode violation。
 */
import { test, expect } from '@playwright/test'

test.describe('知识库 V2：多知识库 + 上传', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/knowledge')
    await page.waitForLoadState('networkidle', { timeout: 15_000 }).catch(() => {})
  })

  test('侧栏渲染知识库列表（默认库 + FAQ 库）', async ({ page }) => {
    await expect(page.getByRole('button', { name: '全部知识库' })).toBeVisible({ timeout: 10_000 })
    await expect(page.getByRole('button', { name: /默认知识库/ })).toBeVisible({ timeout: 10_000 })
    await expect(page.getByRole('button', { name: /故障FAQ库/ })).toBeVisible({ timeout: 10_000 })
  })

  test('选中 FAQ 库筛选文档并把 kb 写回 URL', async ({ page }) => {
    await page.getByRole('button', { name: /故障FAQ库/ }).click()
    await page.waitForTimeout(500) // 等列表刷新 + URL 防抖（200ms）
    expect(page.url()).toContain('kb=')
    // 库内应看到真机联调上传的文档
    await expect(page.getByText('faq-test-doc').first()).toBeVisible({ timeout: 10_000 })
    // 筛选后不该再报「发生意外错误」（此前 kbId 筛选 500 的回归断言）
    await expect(page.getByText('服务内部异常')).toHaveCount(0)
  })

  test('页头有上传与管理两个入口，上传 Dialog 结构完整', async ({ page }) => {
    await page.getByRole('button', { name: '上传文档' }).click()
    const dialog = page.getByRole('dialog', { name: '上传文档入库' })
    await expect(dialog).toBeVisible({ timeout: 5_000 })
    // 拖拽区与类型说明
    await expect(dialog.getByText('拖拽文件到此处')).toBeVisible()
    await expect(dialog.getByText(/扫描件.*无法提取文本/)).toBeVisible()
    // 知识库选择器（el-select 的 placeholder 是属性不是文本，用表单标签断言）
    await expect(dialog.locator('.el-form-item__label', { hasText: '知识库' })).toBeVisible()
  })

  test('知识库管理 Dialog 列出库与生效切片参数', async ({ page }) => {
    await page.getByRole('button', { name: '知识库管理' }).first().click()
    const dialog = page.getByRole('dialog', { name: '知识库管理' })
    await expect(dialog).toBeVisible({ timeout: 5_000 })
    await expect(dialog.getByText('故障FAQ库')).toBeVisible()
    // FAQ 库的自定义参数 1200/300/50 应原样展示
    await expect(dialog.getByText('1200 / 300 / 50')).toBeVisible()
    // 默认库应标注「默认」且参数为全局默认
    await expect(dialog.getByText('2400 / 600 / 100（默认）')).toBeVisible()
  })
})
