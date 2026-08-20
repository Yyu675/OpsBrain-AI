# DevOps 智能运维平台 · 兜底 & 加固清单

本文档是本项目的兜底机制路线图。按 P0 / P1 / P2 优先级排列，每项含现状 · 建议 · 成本。修复顺序建议：**1 → 2 → 5 → 4 → 7 → 9 → 8 → 14 → 13 → 6 → 10 → 3 → 11 → 12**。

---

## P0 · 低成本、防翻车

### 1. 表单未提交时的离开保护
- **现状**：`TicketFormDialog.vue` 与 `ArticleFormDialog.vue` 已用 `draftStorage.ts` 本地暂存，但用户手动点关闭 / 刷新 / 关闭标签页时没有二次确认。
- **建议**：加 `beforeunload` 监听与 `router.beforeEach` 拦截，检测到 dirty 状态时弹 `ElMessageBox.confirm('有未保存内容，确定离开？')`；同时在 dialog 关闭按钮上做同样兜底。
- **成本**：约 40 行代码，可复用为 `useDirtyGuard` composable。

### 2. localStorage 写入失败兜底
- **现状**：`persist.ts` 在 `savePersisted` 里假设 `localStorage.setItem` 一定成功。
- **风险**：隐私模式 / 空间超限（QuotaExceededError）/ Safari ITP 会抛错，当前会中断保存链并可能污染整个 store。
- **建议**：包 try/catch，触发一次 `ElMessage.warning('设置无法本地保存')` 后退化到内存态；同时在读取时增加 `JSON.parse` catch，返回默认值而不是让整个应用挂掉。
- **成本**：10 行改动，集中在 persist.ts。

### 3. 数据 schema 版本迁移策略
- **现状**：`STORE_VERSION = 1`，读到版本不匹配的数据直接丢弃。
- **建议**：引入 `migrations: Record<number, (old: any) => any>`，逐版本升级；至少保留一份最后已知的旧数据到 `<key>__backup`，避免用户配置一次升级全部丢失。
- **成本**：30 行迁移框架 + 每次版本升级写一段 migrator。

### 4. 图片 / 头像加载失败
- **现状**：Home 页 hero 图、AppNavbar avatar 字母、KPI 图标等都直接渲染，无 `@error` 兜底。
- **建议**：把 `<img>` 统一封装成 `<SafeImage>` 组件，`@error` 时切到占位色块 + 首字母；对头像单独封装 `<AvatarFallback :name="user.name">`。
- **成本**：新建一个 40 行组件即可全局替换。

---

## P1 · 常见但被忽视

### 5. 全局 404 / 未匹配路由
- **现状**：router 只有 `/403`，未定义 catch-all。
- **建议**：追加 `{ path: '/:pathMatch(.*)*', component: NotFound }`，复用 Forbidden 的样式做 404 页。
- **成本**：一个新页面文件 + 一行路由。

### 6. 长列表虚拟化 / 分页保护
- **现状**：`TicketList.vue` 全表 `store.tickets.filter + slice` 在每次输入都跑一遍，`KnowledgeBase.vue` 同理。当前数据量小没问题，若 mock 数据涨到 500+ 就会掉帧。
- **建议**：a) 把 `filtered` 拆成两级 computed（先按静态筛选，再按搜索词），减少重算；b) 一旦 pageSize 上调到 50/100，改用 `@vueuse/core` 的 `useVirtualList`。
- **成本**：a) 15 分钟；b) 半小时套壳。

### 7. AppErrorBoundary 的降级信息与恢复入口
- **现状**：`AppErrorBoundary.vue` 能捕获渲染错误，但用户看到红页只能刷新。
- **建议**：加"重试"按钮（新挂载 `<component>`）、"回首页"按钮，并把错误摘要复制到剪贴板（复用 `clipboard.ts`）方便反馈。
- **成本**：20 行模板 + 一个 `key` ref 强制重挂载。

### 8. NetworkBanner 的重连状态与轮询
- **现状**：`NetworkBanner.vue` 只监听 `online/offline` 事件，无法应对"网络已连接但接口打不通"的情况。
- **建议**：断网时启动一个 5~10s 心跳 `fetch('/favicon.ico', { cache: 'no-store' })`，恢复后主动 `ElMessage.success('网络已恢复')` 并触发挂起的写操作重放。
- **成本**：composable `useNetworkHeartbeat`，约 30 行。

### 9. 时间显示的相对/绝对切换与时区
- **现状**：`createdAt` 直接展示原字符串，用户跨天看到"2025-11-15 03:22"要自己算多久前。
- **建议**：接入 `dayjs` + relativeTime 插件（dayjs 已随 Element Plus 提供），`title` 保留绝对时间，正文显示"3 天前"；跨时区时用 `utc` 转显示时区。
- **成本**：一个 `<RelativeTime :value="...">` 组件 + `utils/time.ts` 工具函数。

---

## P2 · 加分项 / 场景性

### 10. 键盘快捷键与聚焦兜底
- **现状**：Dialog 已有 focus-trap，但列表页无快捷键（如 `/` 聚焦搜索、`Esc` 清空筛选、`n` 新增）。
- **建议**：接入 `@vueuse/core` 的 `useMagicKeys` 或 `onKeyStroke`，在页面级注册；`?` 键弹出快捷键帮助面板。
- **成本**：一个 `useHotkeys` composable + 每页 3-5 行注册。

### 11. 长时间未同步的乐观更新回滚
- **现状**：`store.deleteTicket / bulkDelete` 是纯本地操作 + Undo Toast，未来接后端时 UI 已经更新但接口失败会静默不一致。
- **建议**：接后端时按"乐观锁 + 回滚快照"模式，`showUndoToast` 已经保存了 snapshot 可复用；接口失败时自动 `restoreTicket` 并 `ElMessage.error('删除失败已回退')`。
- **成本**：改造 store action，每个约 15 行。

### 12. 富文本 / Markdown XSS 净化
- **现状**：如果 `ArticleFormDialog.vue` 后续支持 Markdown 编辑，`v-html` 渲染会有 XSS 风险。
- **建议**：引入 `DOMPurify` 包一层，只允许白名单标签；或用 `marked` + 自定义 renderer。
- **成本**：装包 + 5 行 sanitize helper。

### 13. 通知系统的读写持久化
- **现状**：`AppNavbar.vue` 通知列表是硬编码数组，刷新后"全部已读"状态丢失。
- **建议**：把 notifications 挪到 store，用同一套 `loadPersisted / savePersisted` 持久化 read/unread；后续接推送后同 store 合并。
- **成本**：新建 `stores/notifications.ts`，约 40 行。

### 14. 空态一致性
- **现状**：不同页的空态样式各写各的（TicketList 有 empty-cell、KnowledgeBase 有 empty-state、Dashboard 无兜底）。
- **建议**：抽 `<EmptyState :icon :title :description :action>` 组件，Dashboard 图表数据为空时也用它，避免 ECharts 渲染空数组时留出诡异的空白。
- **成本**：一个复用组件 30 行 + 各页替换。

---

## 修复进度

| # | 项目 | 状态 | 落地文件 |
| - | - | - | - |
| 1 | useDirtyGuard | ✅ | `src/composables/useDirtyGuard.ts` |
| 2 | persist 兜底 | ✅ | `src/utils/persist.ts` |
| 3 | schema migrations | ✅ | `src/utils/persist.ts`（`Migrator` + `__backup`），各 store 已接入 `MIGRATIONS` 入口 |
| 4 | SafeImage / AvatarFallback | ✅ | `src/components/common/SafeImage.vue` `AvatarFallback.vue` |
| 5 | 404 兜底路由 | ✅ | `src/views/NotFound.vue` + router 中的 `pathMatch(.*)*` |
| 6 | 列表两级 computed | ✅ | `src/views/TicketList.vue` `src/views/KnowledgeBase.vue`（`staticFiltered` + 搜索/排序层） |
| 7 | ErrorBoundary 复制错误 | ✅ | `src/components/common/AppErrorBoundary.vue` |
| 8 | 网络心跳探测 | ✅ | `src/composables/useNetworkHeartbeat.ts` |
| 9 | 相对时间组件 | ✅ | `src/components/common/RelativeTime.vue` `utils/time.ts` |
| 10 | 快捷键 | ✅ | `src/composables/useHotkeys.ts` `HotkeysDialog.vue` |
| 11 | 乐观回滚 | ✅ | `src/stores/tickets.ts`（所有写方法均乐观更新 + 回滚快照：appendReply/updateStatus/updateTicket/transferTicket/updateTags） |
| 12 | Markdown XSS | ✅ | `src/components/ticket/KnowledgeSinkDrawer.vue` / `src/views/KnowledgeDetail.vue`（v-html 均经 DOMPurify 白名单净化） |
| 13 | 通知持久化 | ✅ | `src/stores/notifications.ts` |
| 14 | EmptyState 统一空态 | ✅ | `src/components/common/EmptyState.vue` |
