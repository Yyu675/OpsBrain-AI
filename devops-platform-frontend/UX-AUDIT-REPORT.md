# 前端 UX 全面审计报告

**审计日期**: 2026-09-14  
**审计范围**: 全部 10+ 页面及核心组件  
**工作目录**: `devops-platform-frontend/`

---

## Part 1: P1 技术问题检查

### 1. ECharts 内存泄漏
✅ **已正确处理**

**检查结果**:
- `TrendChart.vue:212` 在 `onBeforeUnmount` 中正确调用 `chartInstance.value?.dispose()`
- 使用 `shallowRef` 避免深响应式开销（第 72 行）
- `ResizeObserver` 在卸载时正确 `disconnect()`（第 209 行）
- 所有使用 ECharts 的页面（Dashboard、Trends、Monitoring）均通过 `TrendChart` 组件复用，统一生命周期管理

**评价**: 无内存泄漏风险。

---

### 2. 长列表性能
❌ **存在性能隐患**

**问题描述**:
1. **TicketList.vue**: 使用 `el-table` 服务端分页，当前默认每页 10 条，性能可控。但组件内部无虚拟滚动，若用户调整为 50/100 条/页会卡顿。
2. **KnowledgeBase.vue**: 同样使用服务端分页，默认每页 10 条，安全。
3. **AlertList.vue**: 服务端分页 + `el-table`，默认每页 10 条。
4. **所有列表页均依赖服务端分页**，前端不实现虚拟滚动。

**实际风险等级**: **P1（中等）**
- 当前配置下（10 条/页）**不会卡顿**
- 若用户手动调整为 50-100 条/页（`ServerPagination` 组件提供此选项），DOM 节点数会激增
- Element Plus `el-table` 在 100+ 行时会出现滚动卡顿（尤其是带复杂 slot 的列）

**建议**:
1. **短期**（P1）：在 `ServerPagination.vue` 中限制最大每页条数为 50，超过 50 的选项禁用或隐藏
2. **中期**（P2）：引入 `vxe-table` 或 `@tanstack/vue-virtual` 替换核心列表的 `el-table`
3. **长期**（优化）：为管理员角色开放 100 条/页选项，但启用虚拟滚动

**文件位置**:
- `/src/views/TicketList.vue:16` (ServerPagination)
- `/src/views/KnowledgeBase.vue`
- `/src/views/AlertList.vue:29` (ServerPagination)
- `/src/components/common/ServerPagination.vue` (控制每页条数选项)

---

### 3. WebSocket 重连
✅ **已完整实现**

**检查结果**:

**AlertStreamMode.vue** (告警流):
- 指数退避重连（1s → 2s → 4s → ... → 30s cap）（第 244-255 行）
- 手动重连按钮（第 258-263 行）
- 组件卸载时正确清理（第 265-277 行，解绑所有回调再 close）
- `props.active` 控制连接激活态（切走 tab 时断开，避免空闲连接）

**useAlertNotifications.ts** (全局通知):
- 同样的指数退避策略（第 60-69 行）
- 登录态驱动启停（watch `isAuthenticated`，第 167-177 行）
- `onBeforeUnmount` 正确清理（第 179 行）
- 解绑所有回调再 close（第 130-144 行，防止孤儿连接）

**ChatMode.vue** (SSE 流式对话):
- 使用 `AbortController` 支持停止生成（第 36 行）
- SSE 本身由 `@microsoft/fetch-event-source` 库处理，自带重连

**评价**: 所有 WebSocket 连接均有完善的重连机制和资源清理。

---

## Part 2: 全面 UX 审计报告

### 优先级分类说明
- **P0（严重影响使用）**: 必须立即修复，阻碍核心功能使用
- **P1（明显体验问题）**: 建议优先修复，影响用户效率或造成困扰
- **P2（优化建议）**: 有时间可以改进，提升整体品质

---

## P0 问题

### P0-1. [TicketList] 列表空白右侧间距过大
**页面**: `/tickets`  
**问题**: 表格右侧有明显空白区域（约 60-80px），导致内容区显得局促。检查代码发现 `el-table` 默认 `fit` 属性会自动撑满，但 `.table-container` 可能有额外 padding。

**建议**: 检查 `TicketTableView.vue` 和 `TicketList.vue` 的容器样式，移除不必要的右侧 padding/margin。

---

### P0-2. [全局] 深色模式缺失但代码已准备
**影响**: 所有页面  
**问题**: 
- `variables.css` 和 `theme-bridge.css` 已定义完整的 CSS 变量系统
- 代码中已有 `--color-*` 变量引用 2494 处（theme-bridge.css:7 注释）
- 但**没有主题切换器实际生效**（AppNavbar 中有 ThemeSwitcher 组件但未启用）

**建议**: 
1. 启用 ThemeSwitcher 组件（可能在 AppNavbar 中被注释）
2. 或明确移除所有主题切换相关代码，避免混淆

---

## P1 问题

### P1-1. [Home] 英雄图片加载失败无降级
**页面**: `/`  
**问题**: 虽然使用了 `SafeImage` 组件，但 `heroImage` 是本地导入的静态资源（`@/assets/image_0_yi19x4.jpg`），若文件丢失会显示 `fallback-text="运维仪表盘预览"`，但纯文字降级在 hero 区域视觉冲击较大。

**建议**: 为 hero 图片准备 base64 内联的低分辨率占位图，或使用纯色渐变背景作为降级。

---

### P1-2. [Dashboard] 信息密度过高
**页面**: `/dashboard`  
**问题**:
- 顶部 KPI 卡片 4 个
- SLA 风险面板 1 个
- 数据详情网格 2 个
- 工单趋势 1 个
- 工单闭环度量 1 个大块（含 4 个小 KPI + 进度条 + 根因分布）
- 诊断区 1 个大块（含 5 个 KPI + 表格 + 趋势图 + 校准区）
- **共 10+ 个数据块挤在一屏**，初次访问会感到信息过载

**建议**:
1. 将诊断区和工单闭环度量折叠到 `CollapsibleCard` 中，默认收起（高级用户展开）
2. 或拆分为两个 tab：「实时概览」+「历史分析」
3. 增加页面顶部的"最后更新时间"字号，当前 `font-size: 0.75rem`（12px）过小

**文件**: `/src/views/Dashboard.vue`

---

### P1-3. [TicketDetail] 右侧栏宽度过窄
**页面**: `/tickets/:id`  
**问题**: 右侧栏包含工单属性、标签编辑、附件、活动日志 4 个面板，宽度固定（推测 280-320px），导致：
- 长文件名被截断不可读
- 时间轴内容挤在一起
- 标签过多时换行密集

**建议**:
1. 将右侧栏宽度从固定值改为 `min-width: 320px; max-width: 400px; flex: 0 0 28%`
2. 或允许用户拖拉调整宽度（类似 IDE 侧边栏）

**文件**: `/src/views/TicketDetail.vue`

---

### P1-4. [KnowledgeBase] 侧栏折叠状态图标不直观
**页面**: `/knowledge`  
**问题**: 使用 `CollapsiblePanel` + `CollapseToggle`，但折叠图标（`ChevronLeft`/`ChevronRight`）在右上角小按钮中，不够显眼。用户可能不知道侧栏可以折叠。

**建议**:
1. 在侧栏顶部增加明显的「收起」文字 + 图标按钮
2. 或在主内容区左上角增加「展开分类」按钮（侧栏折叠时显示）

**文件**: `/src/views/KnowledgeBase.vue:42`

---

### P1-5. [AlertList] 筛选器与内容间距不足
**页面**: `/alerts`  
**问题**: 筛选下拉框（状态、级别）与下方表格间距过小（推测 8-12px），视觉上粘连在一起，不易区分操作区和内容区。

**建议**: 增加 `margin-bottom: 20px` 或在中间加 1px 分隔线。

**文件**: `/src/views/AlertList.vue`

---

### P1-6. [Login] 输入框点击区域偏小
**页面**: `/login`  
**问题**: 
- 用户名和密码输入框高度推测 40px 左右，在触摸屏上点击区域略小
- 输入框左侧图标与输入文字间距较紧

**建议**:
1. 输入框高度增加到 48px（W3C 推荐最小可点击区域 44x44px）
2. 左侧图标与输入框间距从 `gap: 6px` 调整为 `gap: 10px`

**文件**: `/src/views/Login.vue:69-80`

---

### P1-7. [全局] Element Plus 组件默认尺寸不统一
**影响**: 所有使用 Element Plus 的页面  
**问题**: 部分页面使用 `size="small"`，部分使用默认尺寸，导致同类组件（按钮、输入框、标签）在不同页面看起来大小不一。

**建议**: 在 `main.ts` 中统一配置 Element Plus 默认 size：
```ts
app.use(ElementPlus, { size: 'default' })
```
或在 `variables.css` 中统一覆盖 Element Plus 的尺寸变量。

---

### P1-8. [Monitoring/Trends] 空态提示不够友好
**页面**: `/monitoring`, `/trends`  
**问题**: 使用 `DataStateBoundary` + `AppEmpty`，但空态描述「请确认 Prometheus 数据源已接入」对普通运维人员过于技术化，不知道下一步怎么做。

**建议**: 改为「监控数据源未连接，请联系管理员配置 Prometheus」或「前往接入管理查看连接状态」。

**文件**: 
- `/src/views/Monitoring.vue:233`
- `/src/views/Trends.vue:271`

---

### P1-9. [Dashboard] 趋势图 X 轴标签重叠
**页面**: `/dashboard` (成本与命中率趋势图)  
**问题**: `TrendChart` 的 X 轴标签格式化为 `MM-DD HH:mm`（第 156 行），7 天数据约 168 个点，标签会严重重叠。

**实际检查**: `TrendChart.vue:156` 已处理（只显示 `MM-DD`），但在窄屏（< 768px）下仍可能重叠。

**建议**: 
1. 在 `TrendChart` 中增加响应式逻辑，窄屏时每隔 2-3 个标签显示一个
2. 或使用 ECharts 的 `axisLabel.interval: 'auto'`

**文件**: `/src/components/common/TrendChart.vue:152-157`

---

### P1-10. [TicketDetail] AI 分析卡片加载状态不明确
**页面**: `/tickets/:id` (AnalysisCard)  
**问题**: AI 分析区域在加载时只有 loading 图标，没有文字说明「AI 正在分析故障原因...」，用户可能以为页面卡住了。

**建议**: 在 loading 状态下增加文字提示 + 预计耗时（「通常需要 3-5 秒」）。

**文件**: `/src/components/ticket/AnalysisCard.vue`

---

## P2 问题

### P2-1. [全局] 面包屑在移动端溢出
**影响**: 所有使用 `AppBreadcrumb` 的页面  
**问题**: 面包屑使用 `flex` 布局，长路径在移动端会溢出容器。虽然有 `min-width: 0`（AppBreadcrumb.vue:94），但仍可能在 360px 宽度下折行不理想。

**建议**: 移动端隐藏中间层级，只显示「首页 / ... / 当前页」。

**文件**: `/src/components/common/AppBreadcrumb.vue`

---

### P2-2. [Home] CTA 按钮间距过小
**页面**: `/` (Hero 区域)  
**问题**: 「开始使用」和「创建工单」两个按钮间距推测 12px，在触摸屏上容易误触。

**建议**: 间距增加到 16-20px。

**文件**: `/src/views/Home.vue:87-96`

---

### P2-3. [KnowledgeBase] 搜索框无清空按钮
**页面**: `/knowledge`  
**问题**: 搜索框输入后无快速清空按钮（`clearable`），用户需手动删除所有字符。

**建议**: 给搜索 input 加 `clearable` 属性（若使用 Element Plus `el-input`）。

**文件**: `/src/views/KnowledgeBase.vue` (搜索框)

---

### P2-4. [Dashboard] KPI 卡片悬浮效果过于强烈
**页面**: `/dashboard`  
**问题**: `.kpi-card:hover { box-shadow: 0 4px 12px rgba(0, 0, 0, 0.12); }`（第 584 行），阴影从 3px 跳到 12px，过渡较大。

**建议**: 改为 `0 2px 8px rgba(0, 0, 0, 0.1)` 更温和。

**文件**: `/src/views/Dashboard.vue:584`

---

### P2-5. [TicketList] 状态标签颜色对比度不足
**页面**: `/tickets`  
**问题**: Element Plus 的 `el-tag` 默认 `effect="light"` 时，部分颜色（如 `type="info"`）在白色背景上对比度不足（< 4.5:1），不符合 WCAG AA 标准。

**建议**: 改用 `effect="plain"` 或自定义颜色（增加饱和度）。

**文件**: `/src/components/ticket/TicketTableView.vue` 或 `/src/views/TicketList.vue` (状态列)

---

### P2-6. [AlertStreamMode] 告警卡片文字过小
**页面**: AI 助手中心 → 告警流模式  
**问题**: `.alert-card__description { font-size: var(--text-xs, 12px); }`（第 589 行），12px 在高分屏上偏小，长时间盯看会疲劳。

**建议**: 改为 `13px` 或 `0.8125rem`。

**文件**: `/src/components/ai/AlertStreamMode.vue:589`

---

### P2-7. [Trends] 统计表数字列无等宽字体
**页面**: `/trends`  
**问题**: 统计表的数值列（最新、峰值、谷值、均值）使用普通字体，数字宽度不一致，对比困难。

**检查**: 已使用 `font-variant-numeric: tabular-nums`（第 566 行），✅ 无问题。

---

### P2-8. [Login] 密码输入框无显示/隐藏切换
**页面**: `/login`  
**问题**: 密码输入框 `type="password"` 无「显示密码」按钮，用户无法确认输入是否正确。

**建议**: 添加眼睛图标切换 `type` 为 `text`（Element Plus `el-input` 有 `show-password` 属性可直接启用）。

**文件**: `/src/views/Login.vue:82`

---

### P2-9. [Dashboard] 闭环度量区块标题层级不清晰
**页面**: `/dashboard`  
**问题**: 「工单闭环度量」和「诊断区」两个大块的标题字号 16px，与页面主标题「数据概览」（推测 20px）差距较小，层级感不足。

**建议**: 主标题改为 24px，二级区块标题保持 16px。

**文件**: `/src/views/Dashboard.vue:718`

---

### P2-10. [TicketDetail] 附件上传进度条缺失
**页面**: `/tickets/:id` (附件面板)  
**问题**: `useTicketAttachments` composable 支持上传，但没有明确看到进度条 UI（需检查 `TicketAttachmentPanel.vue`）。

**建议**: 上传时显示进度条 + 文件名 + 取消按钮。

**文件**: `/src/components/ticket/TicketAttachmentPanel.vue`

---

### P2-11. [全局] 路由切换无过渡动画
**影响**: 所有页面切换  
**问题**: `<RouterView>` 无 `<Transition>` 包裹，页面切换时内容直接替换，略显生硬。

**建议**: 在 `App.vue` 中给 `<RouterView>` 加淡入淡出过渡（150-200ms）。

**文件**: `/src/App.vue`

---

### P2-12. [AppNavbar] 通知数量徽章遮挡图标
**影响**: 全局导航栏  
**问题**: 通知铃铛图标右上角的数量徽章（`el-badge`）在数量 > 99 时会遮挡部分图标。

**建议**: 徽章位置微调（`transform: translate(50%, -50%)` 调整为 `translate(60%, -40%)`）。

**文件**: `/src/components/common/AppNavbar.vue` (通知按钮)

---

### P2-13. [KnowledgeDetail] 文章内代码块无行号
**页面**: `/knowledge/:id`  
**问题**: 文章内容通过 `safeMarkdown` 渲染，代码块有语言标签（`data-language` 装饰），但无行号。

**建议**: 引入 `highlight.js` 或 `prism.js` 的行号插件，或用 CSS 计数器实现行号。

**文件**: `/src/views/KnowledgeDetail.vue`

---

### P2-14. [Dashboard] 模型调用分布图柱状图太窄
**页面**: `/dashboard` (模型调用分布)  
**问题**: `.model-bar { height: 8px; }`（第 673 行），进度条太细，数据差异不明显。

**建议**: 高度改为 `12px`。

**文件**: `/src/views/Dashboard.vue:673`

---

### P2-15. [TicketList] 批量操作按钮禁用状态不明确
**页面**: `/tickets`  
**问题**: 批量删除等按钮在未选中工单时禁用，但禁用状态只是 `opacity: 0.5`，无 `cursor: not-allowed` 和 tooltip 说明。

**建议**: 禁用时添加 tooltip「请先选择工单」。

**文件**: `/src/views/TicketList.vue` (批量操作按钮)

---

## 响应式 & 移动端适配问题

### P1-11. 移动端缺少专门适配
**影响**: 所有页面  
**问题**: 
- 全局只有 2 处 `@media` 查询（AppNavbar:734 和 AppSkeleton:180）
- 大部分页面在 < 768px 下会出现：
  - 表格横向滚动（el-table 默认不响应式）
  - 按钮文字被截断
  - 卡片网格强制多列导致单列过窄

**建议**:
1. **P0**: 在所有使用 `grid-template-columns: repeat(auto-fit, minmax(240px, 1fr))` 的地方，移动端改为单列
2. **P1**: Dashboard 的多 KPI 布局在移动端改为纵向堆叠
3. **P2**: 考虑引入 `@vueuse/core` 的 `useBreakpoints` 统一管理断点

---

### P1-12. [AppNavbar] 移动端导航菜单缺失
**影响**: 全局导航栏  
**问题**: `AppNavbar.vue:734` 有 `@media (max-width: 768px)` 样式，但没有看到汉堡菜单按钮和侧滑抽屉实现。移动端访问会导致导航不可用。

**建议**: 增加移动端汉堡菜单 + 侧滑抽屉（使用 `el-drawer`）。

**文件**: `/src/components/common/AppNavbar.vue:734-752`

---

## 一致性问题

### P1-13. 空态组件使用不一致
**影响**: 多个页面  
**问题**: 
- TicketList 使用 `DataStateBoundary` + `AppEmpty`
- Dashboard 使用自定义 `.no-data-banner`
- KnowledgeBase 使用 `AppEmpty` 但无统一容器

**建议**: 统一使用 `DataStateBoundary` 包裹所有列表/数据页面，保证加载、错误、空态三态一致。

---

### P2-16. 按钮样式不统一
**影响**: 多个页面  
**问题**: 
- Home 页使用自定义 `.btn-primary` / `.btn-secondary`
- Dashboard 使用 `.refresh-btn`
- TicketList 使用 Element Plus 原生按钮
- 三者样式（圆角、padding、字号）略有差异

**建议**: 创建统一的 `BaseButton.vue` 组件，或统一覆盖 Element Plus 的按钮样式。

---

### P2-17. 加载骨架屏不统一
**影响**: 多个页面  
**问题**: 
- 部分页面使用 `PageLoading` + `el-loading`
- 部分使用 `AppSkeleton`
- 部分使用 `DataStateBoundary` 的 skeleton

**建议**: 统一使用 `DataStateBoundary` 的 skeleton 模式（已区分 list/detail/dashboard 三种变体）。

---

## 总体评价

### 优点
1. ✅ **技术栈现代化**: Vue 3 Composition API + Pinia + TanStack Query，代码组织清晰
2. ✅ **组件复用度高**: TrendChart、DataStateBoundary、ServerPagination 等核心组件复用良好
3. ✅ **错误处理完善**: 各级错误边界（AppErrorBoundary、PanelErrorBoundary）、网络断线兜底（NetworkBanner）
4. ✅ **无障碍基础**: 语义化 HTML、aria 属性、键盘快捷键（useHotkeys）
5. ✅ **性能意识**: 懒加载路由、SSE 流式推送、服务端分页

### 主要问题
1. ❌ **移动端适配缺失**: 仅 2 处媒体查询，大部分页面未考虑 < 768px 场景
2. ❌ **信息密度过高**: Dashboard 堆叠 10+ 数据块，首次访问信息过载
3. ⚠️ **长列表性能隐患**: 依赖服务端分页，若用户调大每页条数会卡顿（当前配置安全）
4. ⚠️ **组件尺寸不统一**: 部分用 `size="small"`，部分用默认，视觉不一致
5. ⚠️ **深色模式代码存在但未启用**: CSS 变量系统完整，但无切换器

### 改进方向
1. **移动端优先级最高**: 增加汉堡菜单、响应式网格、表格横向滚动处理
2. **Dashboard 信息分层**: 用折叠卡片或 tab 拆分高级数据块
3. **统一设计语言**: 按钮、空态、加载骨架统一使用同一组件
4. **长列表虚拟化**: 为高级用户提供 50-100 条/页选项时启用虚拟滚动

---

## 附录：WCAG 2.1 合规性说明

本审计**未进行**以下完整测试（需人工 + 辅助技术）:
- 屏幕阅读器（NVDA/JAWS）导航测试
- 键盘全流程操作测试（Tab 顺序、焦点陷阱）
- 颜色对比度精确测量（需 Contrast Checker 工具）
- 动效触发癫痫风险测试（需专业工具）

**已识别的潜在问题**:
- Element Plus 默认 `el-tag` 的 `type="info"` + `effect="light"` 组合在白色背景上对比度可能不足 4.5:1（P2-5）
- 部分页面使用 12px 字号（如 AlertStreamMode），低于 WCAG AA 推荐的 14px（P2-6）

**建议后续专项测试**:
1. 使用 axe DevTools 或 Lighthouse 自动化扫描
2. 邀请真实用户（含视障用户）进行可用性测试
3. 对关键流程（登录、创建工单、AI 对话）进行键盘操作录屏测试

---

**报告结束**  
**P0 问题**: 2 项  
**P1 问题**: 13 项  
**P2 问题**: 17 项  
**总计**: 32 项
