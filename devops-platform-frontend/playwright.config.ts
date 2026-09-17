import { defineConfig, devices } from '@playwright/test'

/**
 * Playwright E2E 配置（批88 审计 P2 产物）
 *
 * ── 定位 ──────────────────────────────────────────────────────────
 * 覆盖 vitest 组件测试覆盖不到的「真实浏览器 + 真实后端」交互链路：
 * 登录鉴权、路由守卫、表单提交、列表渲染、跨页面导航。
 * 组件测试（1834 用例）验证单元逻辑，本套件验证装配后的真实行为。
 *
 * ── 前置依赖 ───────────────────────────────────────────────────────
 * 需要后端(8088/ai) + 前端(5173) + Docker(pgvector) 全部在线。
 * 用 `webServer` 不负责拉起（后端启动重、依赖 Docker），改为外部预启动：
 *   1. docker compose -f docker-compose.dev.yml up -d
 *   2. 后端：bash run-dev.sh（或已有实例）
 *   3. 前端：npx vite --host
 * 配置里仅做 baseURL 连通性依赖，CI 中应加 wait-on。
 *
 * ── 测试数据纪律 ───────────────────────────────────────────────────
 * 用例自带 setup/teardown：创建的工单/文档以 E2E_PW_ 前缀标记，
 * afterEach 经 API 清理，避免污染 dev 库（对齐 e2e-business-flow.sh 的自清理原则）。
 */
export default defineConfig({
  testDir: './e2e',
  testIgnore: ['**/.auth/**'],
  timeout: 60_000,
  expect: { timeout: 10_000 },
  fullyParallel: false, // 共享同一后端与登录态，串行避免数据竞争
  workers: 1,
  retries: 0,
  reporter: [['list'], ['html', { open: 'never' }]],
  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'http://localhost:5173',
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
    actionTimeout: 15_000,
  },
  projects: [
    // 先跑一次真实登录，产出 storageState 供 chromium 项目复用
    { name: 'setup', testMatch: /e2e[\\/].*\.setup\.ts/ },
    {
      name: 'chromium',
      use: {
        ...devices['Desktop Chrome'],
        storageState: 'e2e/.auth/admin.json',
      },
      dependencies: ['setup'],
    },
  ],
})
