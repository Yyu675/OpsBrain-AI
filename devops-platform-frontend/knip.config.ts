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
    // 路由懒加载的视图不被静态 import，需显式声明为入口
    'src/router/index.ts',
    'src/views/**/*.vue',
    // 测试文件是自身的入口（main.ts/test-setup 由 knip vue/vitest 模式自动识别，hints 已确认 redundant）
    'src/**/*.{test,spec}.ts',
    // 项3 Phase B：字段集契约互证是编译期自证文件（vue-tsc 执行，无运行时消费方）；
    // orval 生成物按快照整目录入库，未被 import 的孪生类型属正常形态
    'src/api/fieldContract.ts',
  ],
  project: ['src/**/*.{ts,vue}'],
  ignore: [
    'src/api/generated/**',
    /**
     * 误报：本组件只在模板中以 <PanelErrorBoundary> 使用，由
     * unplugin-vue-components 自动注册，无 import 语句，knip 因此判为未使用。
     * 已核实真实生效——构建产物 TrendChart-*.css 中含其 .panel-err 样式，
     * Dashboard / TicketInsights 模板均在用。
     */
    'src/components/common/PanelErrorBoundary.vue',
    /**
     * 误报（同 PanelErrorBoundary 先例）：仅经 unplugin-vue-components
     * 模板自动注册（components.d.ts 已登记 AlertFiringSummary/AlertRowActions），
     * 宿主 AlertList.vue 模板真实在用，AlertList.pagination.test.ts 有 props 断言。
     */
    'src/components/alert/AlertFiringSummary.vue',
    'src/components/alert/AlertRowActions.vue',
  ],
  vue: true,
}

export default config
