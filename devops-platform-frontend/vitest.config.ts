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
    include: ['src/**/*.{test,spec}.ts'],
    coverage: {
      provider: 'v8',
      reporter: ['text', 'html'],
      // 只统计被测目标所在的逻辑层，视图层暂不纳入覆盖率考核
      include: ['src/utils/**', 'src/api/utils/**', 'src/composables/**', 'src/constants/**'],
    },
  },
})
