import type { KnipConfig } from 'knip'

/**
 * knip 配置：检测零引用的文件、导出与依赖。
 *
 * 引入动因（CLAUDE.md）：
 * - 6.28 P2-1~5：useCancelableChat / 旧 API 方法 / 死 Bean 全靠人工 grep 才发现
 * - 6.49：AIContextPanel 零引用、components.d.ts 残影同样靠人工核查
 * knip 把这类问题变成可在 CI 中报出的构建信号。
 */
const config: KnipConfig = {
  entry: [
    'src/main.ts',
    // 路由懒加载的视图不被静态 import，需显式声明为入口
    'src/router/index.ts',
    'src/views/**/*.vue',
    // 测试文件是自身的入口
    'src/**/*.{test,spec}.ts',
    'src/test-setup.ts',
  ],
  project: ['src/**/*.{ts,vue}'],
  ignore: [
    // unplugin 生成物
    'auto-imports.d.ts',
    'components.d.ts',
    'src/vite-env.d.ts',
    /**
     * 误报：本组件只在模板中以 <PanelErrorBoundary> 使用，由
     * unplugin-vue-components 自动注册，无 import 语句，knip 因此判为未使用。
     * 已核实真实生效——构建产物 TrendChart-*.css 中含其 .panel-err 样式，
     * Dashboard / AnalyticsMode / TicketInsights 三处模板均在用。
     */
    'src/components/common/PanelErrorBoundary.vue',
  ],
  ignoreDependencies: [
    // 由 unplugin-vue-components 按需解析，无显式 import
    'element-plus',
    // vite 预处理器依赖，无代码 import
    'sass',
  ],
  vue: true,
}

export default config
