import { fileURLToPath, URL } from 'node:url'
import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vitest/config'

// 独立于 vite.config.ts：测试不需要 AutoImport / Components 解析器，
// 保持配置最小以免插件顺序影响用例稳定性。
// 组件测试所需的 Element Plus 组件请在用例中显式 import。
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test-setup.ts'],
    clearMocks: true,
    restoreMocks: true,
    // 关闭 console 拦截（结构性根除拆卸竞态）：拦截器会把测试 console 转成
    // onUserConsoleLog RPC，环境拆卸窗口内落地的消息撞上 RPC 关闭 →
    // EnvironmentTeardownError，全套 1812/1812 全过却 rc=1（TicketList.filters
    // 2~3 连发实测；onConsoleLog return false 拦不住——拆卸期消息不走该钩子）。
    // 本套零 console 断言（实测 grep），关拦截只损失 reporter 的逐测 console 归属，
    // 换来 rc 语义诚实 + fork 裸 console 直出日志（诊断反而是加成）。
    disableConsoleIntercept: true,
    // 2 fork 钉死（本机 16G 满载 + 生产栈/浏览器/dev 后端并行实测：默认/3 fork
    // 出现 worker 被 OOM 杀（22 文件团灭）、2 fork 全量稳绿，代价约 +1min）。
    maxWorkers: 2,
    include: ['src/**/*.{test,spec}.ts'],
    coverage: {
      provider: 'v8',
      reporter: ['text', 'html'],
      // 只统计被测目标所在的逻辑层，视图层暂不纳入覆盖率考核
      include: ['src/utils/**', 'src/api/utils/**', 'src/composables/**', 'src/constants/**'],
    },
  },
})
