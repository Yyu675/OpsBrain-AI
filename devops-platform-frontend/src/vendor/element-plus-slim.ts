/**
 * Element Plus 精简入口（构建期别名 target，2026-10-13）
 *
 * 问题：`unplugin-vue-components` 的 `ElementPlusResolver` 对每个组件生成的导入路径
 * 都是 **barrel** `element-plus/es`（见 resolvers.mjs 的 `resolveComponent`），
 * 而 barrel 的 `es/index.mjs` 顶层执行 `makeInstaller([...全部组件])`——
 * 这是有副作用的顶层表达式，tree-shaking 无法剔除它引用的每一个组件。
 * 结果：项目只用了 20 多个组件，产物里却出现 cascader / color-picker / tour /
 * watermark 等**源码中零引用**的组件，`vendor-element` 单块 966KB，
 * 同时超过总量预算（4600KB）与单件预算（900KB）。
 *
 * 方案：用**精确正则别名**（`/^element-plus$/`、`/^element-plus\/es$/`，见 vite.config.ts）
 * 把 barrel 换成本文件——显式列出项目真正使用的组件，全部走深度入口
 * （`element-plus/es/components/<comp>/index.mjs`）。深度入口本身没有顶层副作用，
 * 未列出的组件不会进入依赖图。与本仓库 `@codemirror/language-data` 换精简注册表
 * （`src/vendor/codemirror-language-data-slim.ts`）是同一套路：别名是构建期行为，
 * 升级依赖不会被覆盖，也不需要 patch node_modules。
 *
 * 维护约定：
 *   - 新增 `<el-xxx>` 用法时，构建会在此处报「not exported」，把该组件加进来即可
 *     （子组件与其父组件同入口，如 ElTableColumn 在 table 入口、ElOption 在 select 入口）；
 *   - 类型导入不受影响：tsc/vue-tsc 仍按 tsconfig 解析真实 `element-plus`，
 *     别名只作用于打包器；
 *   - 样式不受影响：resolver 注入的 `element-plus/es/components/<comp>/style/css`
 *     是更深路径，不会被精确别名命中。
 */

export { ElTable, ElTableColumn } from 'element-plus/es/components/table/index'
export { ElTag } from 'element-plus/es/components/tag/index'
export { ElButton } from 'element-plus/es/components/button/index'
export { ElDescriptions, ElDescriptionsItem } from 'element-plus/es/components/descriptions/index'
export { ElDialog } from 'element-plus/es/components/dialog/index'
export { ElForm, ElFormItem } from 'element-plus/es/components/form/index'
export { ElSelect, ElOption } from 'element-plus/es/components/select/index'
export {
  ElDropdown,
  ElDropdownItem,
  ElDropdownMenu,
} from 'element-plus/es/components/dropdown/index'
export { ElInput } from 'element-plus/es/components/input/index'
export { ElTooltip } from 'element-plus/es/components/tooltip/index'
export { ElDrawer } from 'element-plus/es/components/drawer/index'
export { ElSwitch } from 'element-plus/es/components/switch/index'
export { ElInputNumber } from 'element-plus/es/components/input-number/index'
export { ElRadio, ElRadioGroup } from 'element-plus/es/components/radio/index'
export { ElUpload } from 'element-plus/es/components/upload/index'
export { ElIcon } from 'element-plus/es/components/icon/index'
export { ElDivider } from 'element-plus/es/components/divider/index'
export { ElCheckbox } from 'element-plus/es/components/checkbox/index'
// 服务式 API（业务代码里以具名导入使用）
export { ElMessage } from 'element-plus/es/components/message/index'
export { ElMessageBox } from 'element-plus/es/components/message-box/index'
export { ElNotification } from 'element-plus/es/components/notification/index'
// 指令：`v-loading` 由 resolver 生成 `ElLoadingDirective`（resolvers.mjs 的 resolveDirective）
export { ElLoading, ElLoadingDirective } from 'element-plus/es/components/loading/index'
