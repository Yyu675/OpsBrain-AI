# 前端对标 new-api 的可视化升级方案

> 目标：让 OpsBrain AI 的界面观感与交互质感达到 new-api 水准
> 状态：**阶段 1 已落地并可运行**（`/design-system` 可实时验收），后续阶段为规划
> 日期：2026-08-24

---

## 一、先说清楚：差距到底在哪

把两个项目的前端拆开对比后，差距**不在"用了什么组件库"**，而在四个层面。这点很重要——很多人看到 new-api 好看就想换 Tailwind + shadcn，那是误判。

| 层面 | new-api | OpsBrain 现状 | 是否影响观感 |
| :-- | :-- | :-- | :-- |
| **① 令牌层** | 语义令牌 + 4 个正交轴（配色/明暗/圆角/密度），oklch | 单套静态变量，无暗色，639 处硬编码 hex | 🔴 决定性 |
| **② 组件抽象** | `data-table` 内部包，README 划清边界 | 4 个列表页各写一套表格 | 🟠 明显 |
| **③ 页面结构** | features 垂直切片，单文件 ≤200 行 | 巨型 SFC（2655 / 2552 / 1970 行） | 🟠 明显 |
| **④ 动效与状态** | motion 令牌统一节奏、骨架屏、空/错/加载态一致 | 各页各写，节奏不一 | 🟡 细节 |

**其中 ① 是根因。** 组件再精致，只要颜色是硬编码的，就永远做不了暗色、做不了密度切换、做不了主题；而暗色和密度恰恰是运维系统「显得专业」的最大单点。

> **一个反直觉的结论**：new-api 好看，不是因为它用了 shadcn，而是因为它**所有组件只消费语义令牌，没有一个组件知道"蓝色是 #1B4F9C"**。这个约束和技术栈无关，Vue + Element Plus 完全能做到。

---

## 二、已落地的部分（阶段 1）

**不换技术栈，不改业务代码，先把令牌层建起来。** 已提交并可运行。

### 2.1 四轴设计令牌 `src/assets/styles/theme.css`

```
明暗    html.dark
配色    html[data-theme="default|graphite|nord"]
圆角    html[data-radius="none|sm|md|lg"]
密度    html[data-density="compact|default|comfortable"]
```

四轴**完全正交**，可任意组合。典型的 NOC 大屏配置就是「暗色 + 石墨 + 直角 + 紧凑」。

几个刻意的设计决策：

**为什么是语义命名而非颜色命名。** 现状 `--color-bg-elevated: #FFFFFF` 这个名字本身就假定了浅色，暗色下把它改成深色会让名实矛盾；更麻烦的是「卡片背景」和「弹层背景」共用同一个变量，暗色下无法分别调整（弹层需要比卡片更亮才有层次）。新令牌用 `--surface-0/1/2/3` 表达**层级**，`--text-1/2/3` 表达**主次**。

**为什么用 oklch。** 它是感知均匀色彩空间：把 L 从 0.55 调到 0.65，在蓝色和黄色上"看起来变亮的幅度"是一致的，hsl 做不到。这让暗色反转和色阶派生能按公式推导，而不是逐个手调再肉眼比对。兼容性 Chrome 111+ / Safari 15.4+，2026 年可放心用。

**单列 P0–P3 告警色。** 它们不等同于通用状态色——P0 需要比 `danger` 更红更饱和，才能在密集表格里一眼跳出来。这是运维系统的领域特性，通用设计系统不会有。

**图表色板进令牌。** ECharts 直接读 `--chart-1..6`，暗色自动跟随。否则每个图表配置里都写死一串颜色，换主题要改几十处。

**无障碍不是可选项。** `prefers-reduced-motion` 下动效归零——前庭功能障碍用户会因动画眩晕，这是 WCAG 硬要求。

### 2.2 桥接层 `theme-bridge.css` —— 这一步是关键

前端有 **2494 处** `var(--color-*)` 引用。逐个改成新令牌是几千行改动、评审困难、必然改漏。

桥接的做法是：**保留旧变量名，把它们的值重新指向新令牌**。

```css
--color-bg-elevated: var(--surface-1);
--color-text-primary: var(--text-1);
--el-bg-color: var(--surface-1);        /* Element Plus 一并接管 */
```

于是 `background: var(--color-bg-elevated)` 这行代码**一个字都不用动**，但它现在会随明暗自动变化。同时覆盖约 40 个 `--el-*` 变量，整个 Element Plus 组件库跟着变——运行时 CSS 变量优先级高于 SCSS 编译期默认值，无需重新编译主题。

> 这是典型的适配器策略：用一层薄映射换零成本迁移。代价是变量名暂时两套，新代码用语义令牌、旧代码渐进替换，引用清零后删掉桥接层即可。

### 2.3 主题控制与验收页

- `composables/useTheme.ts` — 四轴状态、localStorage 持久化、`system` 模式**持续跟随**（不是初始化读一次就固化）
- `index.html` 内联同步脚本 — 防首屏白闪。异步都来不及，必须同步内联
- `components/common/ThemeSwitcher.vue` — 四轴切换面板
- **`/design-system`** — 展示页，含 KPI 卡、告警等级、数据表格、状态提示、按钮、图表色板。切任意轴整页实时响应

这一页的意义不只是演示：它是**验收基准**（改令牌先看这页）、**对照样板**（新组件照此写）、以及「组件只用语义令牌」的示范（全文无一个硬编码色值）。

### 2.4 顺带修掉的两个问题

- **`vite allowedHosts`** — 云端预览域名此前被 Vite 的 Host 校验 403 拒绝，预览打不开
- **tsconfig 拆分** — 新增 `tsconfig.vitest.json` 让测试文件获得 node 类型。没有直接往 `tsconfig.app.json` 加 `"node"`，那会让浏览器业务代码误用 `fs`/`path` 也不报错，要到运行时才炸

**验证**：typecheck ✅ / lint 0 error ✅ / **571 tests** ✅ / build ✅ / 开发服务器实测渲染正常。

---

## 三、后续阶段（按 ROI 排序）

### 阶段 2 · 去硬编码（约 2 天）★★★★★

**现状**：639 处硬编码 hex + 62 处硬编码 rgba。它们在暗色下**不会变**，会出现「卡片变暗了但里面的标签还是浅色」这种破碎感。

集中在少数文件：

| 文件 | 硬编码数 |
| :-- | :-- |
| `TicketDetail.vue` | 165 |
| `TicketList.vue` | 58 |
| `AnalysisCard.vue` | 37 |
| `Dashboard.vue` | 33 |
| `ActionItemBoard.vue` | 27 |

**做法**：按文件逐个替换为语义令牌，每替换一个文件就在 `/design-system` 与该页之间对照检查。

**建议同时加一条 lint 规则**，防止回潮：
```js
// eslint.config.ts
'no-restricted-syntax': [{
  selector: "Literal[value=/^#[0-9a-fA-F]{3,8}$/]",
  message: '禁止硬编码色值，请使用 theme.css 的语义令牌'
}]
```
先设 `warn`，存量清零后改 `error`。

---

### ⚠️ 阶段 3 · DataTable 抽象 —— **经核查后撤销此建议**

> **2026-08-24 更正**：实施前逐页核查，发现原判断不成立。
>
> 原文说「4 个列表页各写一套表格，同一逻辑复制 4 遍」。实际情况：
> - `KnowledgeBase` 是**卡片/网格**视图，`ActionItemBoard` 是**看板**，都不用表格
> - 真正用 `el-table` 的只有 `TicketList`(266 行列定义) 与 `AlertList`(116 行)，
>   两者列差异极大，属页面特有展示逻辑而非重复
> - 分页/错误态/空态**早已抽出**（`ServerPagination`、`useServerPaginationFrom`、
>   `ApiErrorState`）并被两页共用——`AlertList` 里甚至有注释写明「与 TicketList 共用」
>
> 把两份差异很大的列定义硬塞进一个组件，只会为了抽象而抽象、更难维护。
> **真正的重复在 URL 状态**（三处实现不一致），已改为实现 `useUrlFilters`。
>
> 教训：抽象前先量化重复量。「看起来像重复」和「真的重复」是两回事。

<details><summary>原建议内容（保留供参考）</summary>

#### 原：DataTable 抽象（约 3 天）

**现状**：`TicketList`(2552) / `AlertList`(687) / `KnowledgeBase`(1357) / `ActionItemBoard`(451) 各自手写表格 + 分页 + 筛选 + 空态 + 骨架 + 批量选择。同一套逻辑复制了 4 遍。

**对标 new-api 的 `data-table` 内部包**（它有 README 明确划分边界：feature 专属的列/操作/弹窗留在 feature 目录，只有跨 feature 复用的才进这个包）。

建议结构：
```
src/components/data-table/
├── README.md              # 明确边界，防止它膨胀成垃圾桶
├── DataTablePage.vue      # 页面壳：Toolbar + Table + Pagination + 移动端卡片
├── DataTableToolbar.vue   # 搜索 + faceted 筛选 + 列显隐 + 视图切换
├── DataTableBulkActions.vue
├── types.ts               # ColumnDef<T>
└── useDataTable.ts        # 整合既有 useServerPagination + 排序/筛选/选中
```

**可直接复用的现成资产**：`ServerPagination.vue`、`useServerPagination.ts`、`AppSkeleton.vue`、`EmptyState.vue`、`ApiErrorState.vue`。你缺的只是**把它们组装成统一契约**。

**验证路径**：先用最小的 `AlertList`(687 行) 改造验证，再推广。

</details>

---

### 阶段 4 · 拆分巨型 SFC（约 5 天）★★★★☆

`TicketDetail.vue` 2655 行、`TicketList.vue` 2552 行。这个体量下：AI 协作单文件就吃掉几万 token、任何改动都要通读全文、无法写有意义的组件测试。

按 new-api 的 features 垂直切片重组（Vue 版形态）：
```
src/features/ticket/
├── api.ts / queries.ts / constants.ts / types.ts
├── TicketListPage.vue      # ≤250 行，只做布局装配
├── TicketDetailPage.vue    # ≤250 行
├── components/
│   ├── ticket-columns.ts   # 列定义抽成纯 TS 数据
│   ├── TicketFilterBar.vue / TicketTimeline.vue / TicketReplyPanel.vue
│   └── __tests__/
└── composables/
    ├── useTicketFilters.ts / useTicketActions.ts
    └── __tests__/
```

**不要一次性重构**。AGENTS.md 已写死约束：新文件 ≤300 行；改超标文件时不得让行数净增长。按「改到哪拆到哪」推进。

---

### 阶段 5 · 动效与状态一致性（约 2 天）★★★☆☆

new-api 有 `lib/motion.ts` 统一动效令牌（`EASE_OUT_CUBIC` + fast/normal/slow 三档 + pageEnter/fadeIn/slideUp 等预设变体）。

我们的 `theme.css` 已经有 `--duration-*` 与 `--ease-*`，还需要：
- 路由切换过渡（`pageEnter`）
- 列表项入场错峰（`tableRow`，stagger 20ms）
- 骨架屏统一（现有 `AppSkeleton` 已不错，需推广到所有异步区域）
- Toast/Drawer/Dialog 的进出场统一节奏

**注意**：全部要走 `--duration-*` 变量，这样 `prefers-reduced-motion` 生效时自动归零。

---

### 阶段 6 · 布局壳升级（约 3 天）★★★☆☆

new-api 用了 Vercel/Cloudflare 的 **drill-in 侧边栏**：点「系统设置」不是把子菜单堆在树里展开，而是整个侧边栏**切换成该模块的上下文工作区**，带「← 返回」。深层导航时信息密度显著更好。

配套还有：命令面板（`⌘K` 全局搜索跳转）、面包屑、移动端 drawer。

你已有 `useHotkeys` 和 `HotkeysDialog`，加命令面板成本不高，且对运维场景很实用（快速跳工单号）。

---

## 四、明确不建议做的

| 想法 | 为何不建议 |
| :-- | :-- |
| 换 Tailwind CSS | 你已重度使用 Element Plus + SCSS 变量体系。中途换样式方案是全站重写，而**观感差距的根因是令牌层不是 CSS 方案** |
| 换 shadcn/Base UI | 那是 React 生态。Vue 侧对应物（shadcn-vue）需要连带换掉 Element Plus，成本极高收益有限 |
| 换 Rsbuild / Bun | 纯工具链成本，用户看不到任何差别 |
| 引入 React | 不必解释 |

> 判断标准：**只有当"现有技术无法达成目标"时才换栈**。暗色、多主题、密度切换、表格抽象、动效统一——这些 Vue + Element Plus 全都能做，已落地的阶段 1 就是证明。

---

## 五、预期效果与投入

| 阶段 | 工作量 | 用户可感知的变化 |
| :-- | :-- | :-- |
| ✅ 1 令牌系统 | 已完成 | 暗色可用、三套配色、密度可调、圆角可调 |
| 2 去硬编码 | 2d | 暗色**完整**无破绽（当前部分区域仍会露白） |
| ~~3 DataTable~~ | — | **已撤销**：核查后发现重复量不足以支撑抽象（详见正文） |
| 3' URL 状态 | ✅ 0.5d | 筛选结果可分享/可刷新保留 —— 值班交接高频动作 |
| 4 拆分 SFC | 5d | 用户无感，但后续所有前端工作提速 |
| 5 动效 | 2d | 「顺滑、有品质感」的主观提升主要来自这里 |
| 6 布局壳 | 3d | 深层导航效率、⌘K 快速跳转 |

**最小可交付**：阶段 1（已完成）+ 阶段 2 = 再投入 2 天，即可得到一个**完整可用的暗色运维控制台**，这是观感提升最大的一跳。

---

## 六、怎么验收

1. 启动 `npm run dev`，访问 **`/design-system`**
2. 右侧面板逐个切换四个轴，观察整页实时响应
3. 重点看：暗色下的层次感（卡片 vs 页面底 vs 表头）、P0 徽章是否够跳、紧凑密度下表格行数变化
4. 切到暗色后再逛几个业务页——**能看到的"露白"区域就是阶段 2 要清理的硬编码**

> 这一步很有价值：桥接层已让大部分区域自动适配，剩下不适配的地方一眼可见，正好形成阶段 2 的精确工作清单。
