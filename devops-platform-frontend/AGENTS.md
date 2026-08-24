# AGENTS.md — OpsBrain AI 前端开发约定

> 本文件是前端的**硬约束清单**。根级通用约定见仓库根目录 `AGENTS.md`。
> 只写「本项目独有、违反就会出问题」的规则。

---

## 一、技术栈

| 类别 | 技术 |
| :-- | :-- |
| 框架 | Vue 3.5（`<script setup>` + Composition API）、TypeScript 6 |
| 构建 | Vite 8、unplugin-auto-import、unplugin-vue-components |
| UI | Element Plus 2.14（按需自动引入）、SCSS 变量体系、lucide-vue-next 图标 |
| 服务端状态 | **@tanstack/vue-query 5** |
| 客户端状态 | Pinia 3 |
| 路由 | Vue Router 5（`meta` 驱动的权限守卫） |
| 图表 | ECharts 6 |
| 富文本 | md-editor-v3 / @wangeditor（**待收敛为一套**） |
| 流式 | @microsoft/fetch-event-source（SSE，支持自定义请求头） |
| 净化 | DOMPurify + marked |
| 质量 | ESLint 10(flat) + vue-tsc + Vitest 4 + @vue/test-utils + knip |

```bash
npm ci
npm run dev          # 5173，/ai 与 /ai/ws 已代理到后端 8088
npm run verify       # typecheck + lint + test —— 提交前必跑
npm run knip         # 死代码/死导出/死依赖
npm run build        # vue-tsc -b && vite build
```

---

## 二、目录与文件组织

```
src/
├── api/          HTTP 调用与 DTO 转换
│   ├── queries/      TanStack Query 的 useQuery/useMutation 封装
│   ├── services/     领域服务（组合多个请求）
│   ├── types/        接口类型
│   └── utils/        dto-converter（后端 DTO ↔ 前端模型）
├── components/
│   ├── common/       跨领域通用组件
│   ├── ai/ ticket/ knowledge/ dashboard/   领域组件
├── composables/  可复用逻辑（useXxx）
├── config/       api / queryKeys / queryClient / navigation
├── constants/    枚举与常量
├── directives/   permission 等自定义指令
├── stores/       Pinia（仅客户端状态）
├── utils/        纯函数工具
└── views/        路由页面
```

### 硬约束

- **单个 `.vue` 文件不得超过 300 行。**
  已超标的历史文件（`TicketDetail.vue` 2655、`TicketList.vue` 2552、`KnowledgeEditor.vue` 1970、
  `KnowledgeDetail.vue` 1730、`KnowledgeBase.vue` 1357、`TicketFormDialog.vue` 1073）
  **修改时不得让行数净增长**，至少拆出一个子组件或 composable。
- 页面组件只做**布局装配与数据编排**，业务逻辑下沉到 composable，展示逻辑下沉到子组件。
- 表格的列定义抽成**纯 TS 数据**（`xxx-columns.ts`），不要写在模板里。
- 组件文件 PascalCase；composable / 工具 / 类型文件 camelCase 或 kebab-case。
- 测试放在被测模块同级的 `__tests__/` 目录，**禁止与源码平铺在同一层**。

---

## 三、数据获取与状态

### 3.1 TanStack Query 是服务端数据的唯一入口

- 读用 `useQuery`，写用 `useMutation`。**禁止**在组件里直接 `await api.xxx()` 拿列表数据后塞进 `ref`。
- **`queryKey` 必须取自 `src/config/queryKeys.ts`，禁止在任何地方手写字符串数组。**
  > 这是本项目踩过多次的坑：查询用的 key 与 `invalidateQueries` 的 key 差一层或差一个参数，
  > 会**静默失效不了**，表现为"改完数据列表没更新"，且没有任何报错可循。
  > key 层级约定 `[领域, 子资源, 参数]`，前缀失效天然生效。
- 写操作成功后必须 `invalidateQueries` 相关 key；新增查询时必须在 `queryKeys.ts` 里登记。

### 3.2 Query 层不重试

`config/queryClient.ts` 已把 `retry` 关掉，**不要打开**：
- `utils/http.ts` 已内建重试（GET 指数退避 2 次；**写操作 0 次——重试会建出重复工单**）。
  两层叠加会让一次 GET 最坏发出 9 个请求。
- 业务错误（`code !== 0`）由 `unwrapBiz` 抛成 `HttpError(status=200, code='BIZ')`，
  重试只会重复失败。若将来确需 Query 层重试，判据必须排除 `code === 'BIZ'`。

### 3.3 Pinia 只放客户端状态

用户偏好、UI 折叠态、通知已读、草稿 —— 这些放 Pinia。
**服务端数据不要在 Pinia 里再存一份**，否则会出现 Query 缓存与 store 两个真相。

持久化统一走 `utils/persist.ts`（已内建 try/catch 降级、版本号与 `Migrator`）。
**禁止**在组件里直接 `localStorage.setItem` —— 隐私模式 / 配额超限会抛错中断整条保存链。

---

## 四、错误处理

- 所有 HTTP 走 `utils/http.ts`。它负责：附带 `satoken` 头、超时、重试、401 派发
  `auth:unauthorized` 自定义事件（**不要在 http 层直接跳路由**，由 App 监听后用 router 跳，避免丢 SPA 状态）。
- 面向用户的报错一律经 `toFriendlyError()` → `{ title, detail, hint }` 三段，
  再用 `utils/notify.ts` 展示（内建 1s 冷却去重，避免批量失败刷屏）。
  **禁止**直接 `ElMessage.error(String(e))`。
- 后端新增业务码时，`toFriendlyError` 必须同步补分支，否则用户只看到无意义的兜底文案。
- 面板级错误用 `PanelErrorBoundary`，页面级用 `AppErrorBoundary`，
  接口错误态用 `ApiErrorState`，空态统一用 `EmptyState` / `AppEmpty`，加载态用 `AppSkeleton`。
  **不要各页各写一套。**

---

## 五、安全

- **任何 `v-html` 的内容必须先过 `utils/safeMarkdown.ts`（marked + DOMPurify 白名单）。**
  知识库正文、AI 回答、工单富文本回复全部适用 —— 这些内容都可能来自不可信来源（含 LLM 输出）。
- 权限控制走 `directives/permission.ts` 与路由 `meta.roles / meta.permissions`。
  **前端权限只是体验优化，后端必须独立校验**，不要因为前端隐藏了按钮就认为端点安全。
- 禁止在前端存储敏感信息（除 `satoken` 外）。禁止硬编码任何密钥。
- 禁止硬编码 `localhost` / 后端端口 / 后端域名，一律相对路径 + Vite proxy。

---

## 六、代码风格与类型

- **禁止 `as any`**（ESLint 已设为 error）。用具体类型或 `unknown` + 类型守卫。
  确实无解时写 `// eslint-disable-next-line` 并注明原因，不接受静默逃逸。
- 仅类型用途的导入用 `import type`。
- 禁止两层及以上嵌套三元；改用提前返回或抽函数。
- `ref` / `computed` / `watch` 等由 unplugin-auto-import 注入，**不要手写 import**
  （`auto-imports.d.ts` 与 `components.d.ts` 是生成物，不要手改）。
- 改完 TS/Vue **必须**跑 `npm run typecheck` 与 `npm run lint`，**error 一个都不许留**。

---

## 七、性能

- 路由一律懒加载。`router/index.ts` 里的 `lazy()` 已封装重试 + 骨架屏，**新增路由沿用它**，
  不要直接写 `() => import(...)`（会丢掉加载态与失败重试）。
- 列表筛选拆两级 `computed`（先静态筛选，再搜索/排序），避免每次按键重算全量。
- 服务端分页走 `useServerPagination` + `ServerPagination.vue`，
  **禁止**把全量数据拉到前端再 slice。
- `vite.config.ts` 的 `manualChunks` 已按 echarts / element / icons / query / vue 分块，
  **新增大依赖时同步登记**，否则会被打进主包。
- ECharts 若要新增图表类型，优先按需引入而非整包。

---

## 八、测试

测试必须保护**真实用户行为、稳定契约或明确回归路径**。

- **禁止**只为覆盖率、只证明能渲染、只断言内部实现的测试。
- **禁止** sleep / 随机输入 / 计时断言 / 大循环 / 只看日志的"测试"。
- **禁止** mock 被测模块自身；只 mock 网络、时间、随机数、存储、浏览器 API 这些不可控边界，
  且每个用例后恢复。
- 组件测试从**用户视角**查询元素并操作（点击/输入/键盘/焦点），断言可见结果或对外回调；
  **禁止**断言组件内部 state、私有函数调用次数、脆弱的完整 class 字符串或大范围快照。
- 每个用例只保护一个可描述的行为，名称包含**触发条件 + 预期结果**，用 Arrange-Act-Assert 结构。
- 覆盖主成功路径 + 本次变更涉及的边界：空数据、单条、多条、超长文本、无效输入、
  禁用态、异步失败与降级。
- 改布局/尺寸/滚动/焦点/键盘/选中/禁用/加载/空态/错误态/响应式行为时，**必须补回归测试**。
- 修 Bug **先写失败用例**再修。
- 纯函数（`utils/`）与 composable 优先单元测试，成本低价值高。
- 提交前至少跑受影响的测试文件 + `npm run typecheck` + 相关文件 lint。

---

## 九、依赖管理

- 用 npm（有 `package-lock.json`，CI 用 `npm ci`）。
- 新增依赖前评估：维护活跃度、体积、许可、是否与既有能力重复。
  > 当前已存在重复：`@wangeditor/editor` 与 `md-editor-v3` 两套富文本编辑器同时在依赖里。
  > **新增编辑相关功能请复用其中一套，并推动收敛，不要引入第三套。**
- 定期跑 `npm run knip` 清理零引用文件/导出/依赖；误报在 `knip.config.ts` 里加 ignore
  并**写明为什么是误报**（现有条目都有说明，照此办理）。

---

## 十、i18n（规划中）

当前全站中文硬编码，尚未接入 vue-i18n。**新增用户可见文案时**：
- 集中放在 `constants/` 或组件同级的 `constants.ts`，不要散落在模板深处；
- 常量里的 `SUCCESS_MESSAGES` / `ERROR_MESSAGES` 之类，未来接 i18n 时会变成 key，
  现在就按"一个 key 一条文案"的粒度组织，方便后续批量迁移。

---

## 更新日志

- **2026-08-24**：初版。参考 new-api `web/AGENTS.md` 的组织方式，内容基于本项目真实代码与历史缺陷。
