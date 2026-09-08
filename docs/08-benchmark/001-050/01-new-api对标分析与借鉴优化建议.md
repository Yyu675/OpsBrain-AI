# new-api 对标分析与 OpsBrain AI 借鉴优化建议

> 对标对象：[QuantumNous/new-api](https://github.com/QuantumNous/new-api) —— 统一 AI 模型网关（Go 1.26 + Gin + GORM / React 19 + Rsbuild + Base UI + Tailwind）
> 本项目：OpsBrain AI（Spring Boot 3.5 + LangChain4j 1.1 + JPA / Vue 3.5 + Element Plus + TanStack Query）
> 分析日期：2026-08-24 ｜ 分析基准：new-api main 分支最新快照、OpsBrain AI `arena/01a031f6-opsbrain-ai`

---

## 零、先回答你的四个问题（结论先行）

| 问题 | 结论 | 理由摘要 |
| :-- | :-- | :-- |
| **new-api 哪些能借鉴/复制到 OpsBrain AI？** | **能借鉴的是「工程治理模式」，不是代码。** 约 8 类模式高价值可移植，2 类不适用 | 技术栈完全不同（Go/Gin/GORM vs Java/Spring/JPA），**逐行复制代码 = 0 价值**；但它的错误码体系、配置热更新、审计中间件、混合缓存、CI 门禁、data-table 组件契约，是与语言无关的架构范式 |
| **前端可借鉴什么？** | **6 项高价值**：i18n 体系、features 垂直切片目录、DataTable 抽象层、URL 即状态、表单 schema 校验（Zod/VeeValidate）、统一 `handleServerError` | 你的前端已经有 TanStack Query + queryKeys + knip，基础比很多项目好；短板是**巨型 SFC**（TicketDetail 2655 行 / TicketList 2552 行）、**零 i18n**、**无表格抽象**、**无 URL 状态** |
| **后端可借鉴什么？** | **7 项高价值**：类型化 ErrorCode 体系、RequestId/MDC 全链路、审计中间件、DB 配置热更新、HybridCache、Adaptor 接口解耦、限流中间件 | 你的后端 Clean Architecture 分层其实**比 new-api 更规范**（它是扁平 Go 包）；短板是**可观测性缺失**（无 MDC、无 RequestId、无 OpenAPI）、**无限流**、**测试 0 个后端用例** |
| **要不要重点参考它的 AGENTS.md？** | **要，但只参考「结构与写法」，不要抄内容。** 优先级：★★★★☆ | 它的 AGENTS.md 是**业界少见的高质量样本**——不写"要写好代码"这种废话，而是写**「这个项目独有的、违反就会出事故的硬约束」**（如 quota 不得裸转 int、GORM v2 的 FOR UPDATE 陷阱）。这个「反抽象、反通用」的写法值得你 100% 学习 |
| **要不要给本项目生成 AGENTS.md？** | **强烈需要，而且是当前投入产出比最高的一件事。** | 你现在的 `CLAUDE.md` 是 **3206 行 / 280KB**，作为 AI 协作契约已经**严重超载**：AI 每次读取都要消耗巨量上下文，且规范、决策记录、进度报告、历史缺陷全混在一起。必须拆分：`AGENTS.md`（薄、稳定、约束）+ `CLAUDE.md`（决策记录归档） |

---

## 一、两个项目的可比性评估（先划清边界）

### 1.1 相似之处（可对标基础）

| 维度 | new-api | OpsBrain AI |
| :-- | :-- | :-- |
| 形态 | 前后端分离 + 单体后端 + SPA | 同 |
| 核心域 | LLM 调用编排、多渠道路由、流式 SSE | LLM Agent 编排、大小模型路由、流式 SSE |
| 关键机制 | 多级缓存、限流、重试、配额计费 | 语义缓存、幻觉防护、工具重试、成本配额（`CostQuotaManager`） |
| 数据 | PG/MySQL/SQLite + Redis | PG(pgvector) + Redis + MinIO |
| 管理端 | 用户/渠道/日志/看板 | 工单/知识库/告警/看板 |

> **关键相似点**：你们都有「**LLM 调用是不可靠外部依赖**」这个核心矛盾——它靠"多渠道重试 + 渠道熔断"解，你靠"四层幻觉防护 + 工具重试 + 降级"解。**它在这条链路上的工程化程度显著领先**，这是最值得挖的矿。

### 1.2 不可比之处（不要生搬）

| new-api 的做法 | 为何不适用于 OpsBrain AI |
| :-- | :-- |
| **三数据库兼容**（SQLite/MySQL/PG 同时支持） | 你强依赖 **pgvector**，向量检索是核心能力，兼容 SQLite 毫无意义。**不要引入这个约束**，它会把你的 Repository 层复杂度翻倍 |
| **relaykit 独立子模块**（`GOWORK=off go build` 单独可构建） | Go 多模块特有；Java 侧对应物是 Maven 多模块。你项目规模（158 个 Java 文件）**还不到拆多模块的临界点**，过早拆分只会增加构建负担。等 L2 引入事件网关时再考虑 |
| **billingexpr 表达式计费引擎** | 它是 SaaS 计费产品；你是内部运维平台，`CostQuotaManager` 目前的简单阈值足够。**L5 商业化再说** |
| **40+ 渠道 Adaptor** | 你只有阿里云百炼一家 + Mock。**但 Adaptor 接口思想仍值得学**（见 §3.6） |
| **AGPL 版权头 + 品牌保护条款** | 你是个人求职作品，无需。但**版权头脚本化**的思路可用于统一 Javadoc 头 |

---

## 二、前端可借鉴项（按 ROI 排序）

### ★★★★★ 2.1 巨型 SFC 拆分 —— 学 features 垂直切片

**现状诊断（你的问题）**

```
views/TicketDetail.vue      2655 行   ← 严重超标
views/TicketList.vue        2552 行   ← 严重超标
views/KnowledgeEditor.vue   1970 行
views/KnowledgeDetail.vue   1730 行
views/KnowledgeBase.vue     1357 行
components/ticket/TicketFormDialog.vue  1073 行
```

**new-api 的做法**：`web/src/features/<feature>/` 垂直切片，单文件超 200 行即拆。以 `features/keys` 为例：

```
features/keys/
├── api.ts                      # 该 feature 的 HTTP 调用
├── constants.ts                # 枚举 + i18n labelKey
├── types.ts                    # 该 feature 的类型
├── index.tsx                   # 入口页
├── lib/
│   ├── api-key-form.ts         # Zod schema + 表单逻辑
│   └── __tests__/
└── components/
    ├── api-keys-table.tsx      # 表格装配
    ├── api-keys-columns.tsx    # 列定义（纯数据）
    ├── api-keys-cells.tsx      # 单元格渲染器
    ├── api-keys-mutate-drawer.tsx
    ├── api-keys-delete-dialog.tsx
    ├── api-keys-provider.tsx   # feature 级 context
    ├── data-table-row-actions.tsx
    └── __tests__/              # 测试与源码同目录但独立子目录
```

**移植到 Vue3 的形态**（不需要照抄 React 写法）：

```
src/features/ticket/
├── api.ts                  # 从 api/tickets.ts 迁入
├── queries.ts              # 从 api/queries/tickets.query.ts 迁入
├── constants.ts            # 从 constants/ticket.ts 迁入
├── types.ts
├── TicketListPage.vue      # ≤ 250 行：只做布局装配
├── TicketDetailPage.vue    # ≤ 250 行
├── components/
│   ├── TicketTable.vue
│   ├── ticket-columns.ts        # 列定义抽成纯 TS 数据
│   ├── TicketFilterBar.vue
│   ├── TicketTimeline.vue
│   ├── TicketReplyPanel.vue
│   ├── TicketSlaBadge.vue
│   └── __tests__/
└── composables/
    ├── useTicketFilters.ts
    ├── useTicketActions.ts
    └── __tests__/
```

**为什么这是 ROI 最高项**：2655 行的 SFC 意味着——① AI 协作时单文件就吃掉几万 token；② 任何改动都要通读全文才敢下手；③ 无法写有意义的组件测试。**这一条不解决，后面所有优化都事倍功半。**

**落地建议**：不要一次性重构。按「**改到哪拆到哪**」的规则，在 AGENTS.md 里写死硬约束：
> 新增 `.vue` 文件不得超过 300 行；修改既有超标文件时，必须至少拆出一个子组件或 composable，不得让行数净增长。

---

### ★★★★★ 2.2 DataTable 抽象层 —— 消除表格代码复制

**new-api 的做法**：`web/src/components/data-table/` 是一个有明确 README 和公共 API 边界的内部包：

```
data-table/
├── README.md          # 明确声明「feature 专属的列/操作/弹窗留在 feature 目录」
├── index.ts           # 唯一公共出口
├── core/              # 渲染原语：column-header / row / pagination / skeleton
│                      #            column-pinning / truncated-cell / badge-cell
├── layout/            # 页面级组合：desktop table + mobile card list + bulk actions
├── toolbar/           # 筛选/搜索/视图选项/批量操作条
├── static/            # 本地静态数组的轻量渲染（不用 TanStack state）
└── hooks/             # use-data-table / use-debounced-column-filter / view-mode
```

**你的现状**：`TicketList.vue`(2552) / `AlertList.vue`(687) / `KnowledgeBase.vue`(1357) / `ActionItemBoard.vue`(451) 各自手写 el-table + 分页 + 筛选 + 空态 + 骨架 + 批量选择。**同一套逻辑至少复制了 4 遍**。

**建议**：抽 `src/components/data-table/`：

| 文件 | 职责 |
| :-- | :-- |
| `DataTablePage.vue` | 页面级布局壳：Toolbar + Table + Pagination + 移动端卡片列表切换 |
| `DataTableToolbar.vue` | 搜索框 + faceted 筛选 + 列显隐 + 视图切换 |
| `DataTableBulkActions.vue` | 选中行数 + 批量操作按钮组 |
| `columns.ts` 类型 | `interface ColumnDef<T> { key; title; width?; render?; sortable?; pinned? }` |
| `useDataTable.ts` | 整合 `useServerPagination`（你已有）+ 排序 + 筛选 + 选中态 |
| `useTableUrlState.ts` | **见 2.3** |

已有资产可直接复用：`ServerPagination.vue`、`useServerPagination.ts`、`AppSkeleton.vue`、`EmptyState.vue`、`ApiErrorState.vue`。你缺的只是**把它们组装成一个统一契约**。

---

### ★★★★☆ 2.3 URL 即状态（use-table-url-state）

**new-api 的做法**：`hooks/use-table-url-state.ts` 把分页/筛选/排序全部同步到 URL query，并把 pageSize 持久化到 localStorage（跨表格记忆）：

```ts
const PAGE_SIZE_STORAGE_KEY = 'page-size'
function getStoredPageSize(): number | undefined {
  try {
    const n = parseInt(localStorage.getItem(PAGE_SIZE_STORAGE_KEY) ?? '', 10)
    return n > 0 ? n : undefined   // n > 0 同时拒绝了 NaN
  } catch { return undefined }
}
```

**为什么对你特别重要**：运维场景的核心动作就是**"把这个筛选结果甩给同事看"**。
- 现状：`/tickets` 筛完 P0+未分配+近 24h，刷新即丢失，也无法分享链接。
- 改造后：`/tickets?status=OPEN&priority=P0&assignee=none&page=2&sort=-createdAt`，刷新保留、可分享、浏览器前进后退可用。

**落地**：新增 `composables/useUrlState.ts`，与 `queryKeys.ts` 的参数结构对齐（你的 `TicketListParams` 已经定义得很好，直接作为 URL schema 的来源）。

---

### ★★★★☆ 2.4 国际化（i18n）—— 从零到有

**new-api 的做法**（前后端双侧）：
- 前端 `i18next` + `react-i18next` + `browser-languagedetector`，7 种语言，**扁平 JSON，key 就是英文原文**（`t('Something went wrong!')`），配 `bun run i18n:sync` 自动同步缺失 key。
- 后端 `go-i18n/v2`，`i18n/keys.go` 里把消息 key 定义成常量（`MsgInvalidParams = "common.invalid_params"`），**禁止硬编码字符串**。
- 规范里明确了一条极容易被忽略的坑：**常量文件里的 `SUCCESS_MESSAGES` / `ERROR_MESSAGES` 只是 i18n key，展示时必须过 `t()`**，禁止 `toast.success(SUCCESS_MESSAGES.xxx)`。

**你的现状**：`grep -rl "i18n|\$t("` → **0 命中**。全站中文硬编码，包括后端 `ApiResponse.error(40103, "权限不足：该操作需要「" + role + "」角色")` 这种拼接。

**建议（分级采纳）**：

| 级别 | 动作 | 适用时机 |
| :-- | :-- | :-- |
| **P0（现在就做）** | **后端错误消息 key 化**：新建 `common/i18n/MessageKeys.java`，把 40101/40103/40009/40021 等业务码的文案抽成常量。**即使只有中文一种语言，也消除了"同一个错误在三处措辞不同"** | 立刻 |
| **P1（求职作品加分项）** | 前端接 `vue-i18n`，先做 zh-CN 一种 locale，把硬编码文案迁到 `locales/zh-CN.json` | 拆分巨型 SFC 时顺手做 |
| **P2** | 补 en，做语言切换器 | 有余力时 |

> **求职视角**：面试官看到「国际化」不会加太多分，但看到「**后端错误码 + 消息 key + 前端映射表**三者一一对应的错误契约」会明显加分——这是企业级系统的标志。

---

### ★★★☆☆ 2.5 表单 Schema 校验（Zod → Vue 侧对应物）

**new-api 的做法**：React Hook Form + Zod，schema 定义在 `features/<f>/lib/*.ts`，用 `z.infer` 导出表单类型，服务端字段级错误映射回表单字段。

**你的现状**：`TicketFormDialog.vue` 1073 行，校验规则用 Element Plus 的 `rules` 对象内联在 SFC 里，类型与校验规则**两套真相**。

**建议**：引入 `zod` + `@vee-validate/zod`（或仅 zod + 手写 adapter）：
```ts
// features/ticket/lib/ticket-form.schema.ts
export const ticketFormSchema = z.object({
  title: z.string().min(4, '标题至少 4 个字').max(120),
  priority: z.enum(['P0','P1','P2','P3']),
  service: z.string().min(1, '请选择所属服务'),
  description: z.string().min(10),
})
export type TicketFormValues = z.infer<typeof ticketFormSchema>
```
收益：① 类型与校验单一真相；② 可在单测里直接测 schema（不用挂载组件）；③ 后端 400 的字段级错误可统一 `setErrors` 回填。

---

### ★★★☆☆ 2.6 统一 handleServerError + 前端错误消息表

**new-api**：`lib/handle-server-error.ts` 一个函数接管所有服务端错误 → `lib/server-error-message.ts` 把后端消息映射成 i18n key → `toast.error(t(key))`。React Query 全局 `onError` 与 axios 拦截器都接这一个入口。

**你的现状**：其实**已经做得不错**——`utils/http.ts` 里的 `toFriendlyError()` 返回 `{title, detail, hint}` 三段，比 new-api 的单行 toast 更友好。`notify.ts` 还做了冷却去重。

**唯一缺口**：`toFriendlyError` 是 `switch (e.code)` 的大 switch，随业务码增长会膨胀，且**没有与后端业务码常量表联动**。

**建议**：
1. 后端新建 `common/dto/BizCode.java`（枚举），把 40009/40021/40101/40103 等集中定义并**生成一份 JSON**；
2. 前端 `constants/bizCode.ts` 与之对齐，`toFriendlyError` 改为查表 + 兜底；
3. 加一个测试断言「后端枚举与前端表 key 集合一致」——这就是 new-api 说的"protect a real contract"。

---

### 2.7 前端不建议照抄的部分

| new-api 做法 | 不建议原因 |
| :-- | :-- |
| 换 Bun / Rsbuild | 你的 Vite 8 + npm 工作良好，换构建工具是纯成本 |
| 换 Tailwind CSS | 你已重度使用 Element Plus + SCSS 变量体系（`element-variables.scss`），中途换样式方案是灾难 |
| 换 TanStack Router | Vue Router 5 的 `meta` 权限守卫你写得很完整（`roles/permissions/stage/public`），无需迁移 |
| oxlint 替换 ESLint | 你的 eslint flat config 注释详尽、规则取向清晰，无必要 |

---

## 三、后端可借鉴项（按 ROI 排序）

### ★★★★★ 3.1 全链路 RequestId + MDC —— 当前最大的可观测性缺口

**new-api 的做法**（`middleware/request-id.go`，18 行）：

```go
func RequestId() func(c *gin.Context) {
    return func(c *gin.Context) {
        id := common.NewRequestId()
        c.Set(common.RequestIdKey, id)
        ctx := context.WithValue(c.Request.Context(), common.RequestIdKey, id)
        c.Request = c.Request.WithContext(ctx)
        c.Header(common.RequestIdKey, id)   // ← 关键：回写到响应头
        c.Next()
    }
}
```
配合 `logger.LogInfo(c, ...)` —— **所有日志强制传 gin.Context**，日志行自动带 requestId。

**你的现状（严重问题）**：
- `TraceContext.java` 用 ThreadLocal 存 traceId，但 **`grep -rn "MDC" src/main/java` → 0 命中**，日志里根本没有 traceId；
- `ApiResponse.generateTraceId()` 是 **`Long.toHexString(System.nanoTime()).substring(0,8)`** —— 每次调用都生成一个**新的**随机值！这意味着：
  - 同一请求的 success 响应和日志里的 traceId **对不上**；
  - `error(code, message)` 和 `error(code, message, data)` 两个重载生成的 traceId 也不同；
  - **traceId 字段目前是纯装饰，完全无法用于排障**。

**这是必须修的 Bug 级设计缺陷。** 建议：

```java
// common/context/TraceContext.java —— 改为唯一真相，并接 MDC
public final class TraceContext {
    public static final String TRACE_ID = "traceId";
    public static void begin(String incoming) {
        String id = (incoming == null || incoming.isBlank())
            ? UUID.randomUUID().toString().replace("-", "").substring(0, 16)
            : incoming;
        MDC.put(TRACE_ID, id);       // ← logback 的 %X{traceId} 即可打印
    }
    public static String get() { return MDC.get(TRACE_ID); }
    public static void clear() { MDC.remove(TRACE_ID); }
}
```

```java
// common/web/TraceIdFilter.java —— 新增 OncePerRequestFilter（当前项目一个 Filter 都没有）
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {
    @Override protected void doFilterInternal(HttpServletRequest req,
            HttpServletResponse resp, FilterChain chain) throws ... {
        TraceContext.begin(req.getHeader("X-Request-Id"));
        resp.setHeader("X-Request-Id", TraceContext.get());   // 回写，前端可展示/上报
        try { chain.doFilter(req, resp); } finally { TraceContext.clear(); }
    }
}
```

`ApiResponse` 的 `generateTraceId()` 删掉，改读 `TraceContext.get()`。
logback pattern 加 `[%X{traceId}]`。
前端 `HttpError` 里已有 `url`，再加一个 `traceId`（从响应头读），`toFriendlyError` 的 hint 里展示 —— **用户截图报错时，你能直接凭 traceId grep 到后端全链路日志**。这是面试可讲的硬实力点。

> ⚠️ **异步/SSE 陷阱**：MDC 基于 ThreadLocal，你的 SSE (`SseEmitter`) 与 `@Async` 调度器（`ColdMemoryArchiveScheduler` 等）会切线程。需要配 `TaskDecorator` 复制 MDC，否则异步日志丢 traceId。这一条要写进 AGENTS.md。

---

### ★★★★★ 3.2 类型化错误码体系（ErrorCode + 语义分类）

**new-api 的做法**（`relaykit/types/error.go`）：错误码是**带命名空间的字符串常量**，按来源分类：

```go
type ErrorCode string
const (
    // new api error（自身故障）
    ErrorCodeCountTokenFailed   ErrorCode = "count_token_failed"
    ErrorCodeGetChannelFailed   ErrorCode = "get_channel_failed"
    // channel error（上游渠道故障，可重试/可熔断）
    ErrorCodeChannelNoAvailableKey       ErrorCode = "channel:no_available_key"
    ErrorCodeChannelResponseTimeExceeded ErrorCode = "channel:response_time_exceeded"
    // client request error（用户错误，不重试）
    ErrorCodeReadRequestBodyFailed ErrorCode = "read_request_body_failed"
    ErrorCodeAccessDenied          ErrorCode = "access_denied"
    // response error（上游响应异常）
    ErrorCodeBadResponseStatusCode ErrorCode = "bad_response_status_code"
    ErrorCodePromptBlocked         ErrorCode = "prompt_blocked"
)
```
配套 `types.ErrOptionWithSkipRetry()` —— **错误自身携带"是否可重试"语义**，重试循环只需 `if types.IsSkipRetryError(err) { break }`，不用在调用点写 if-else 判断状态码。

**你的现状**：错误码是**散落的魔法数字**——`40101`/`40103`/`40009`/`40021` 直接硬编码在 `GlobalExceptionHandler` 和各 Service 里，前端 `toFriendlyError` 也硬编码同样的数字。没有单一真相，没有分类，没有可重试语义。

**建议**：新建 `common/error/BizError.java`：

```java
public enum BizError {
    // 4xx 客户端
    INVALID_PARAM      (40000, HttpStatus.BAD_REQUEST,   Retry.NEVER,  "参数不合法"),
    NOT_LOGIN          (40101, HttpStatus.UNAUTHORIZED,  Retry.NEVER,  "未登录或登录已失效"),
    NO_PERMISSION      (40103, HttpStatus.FORBIDDEN,     Retry.NEVER,  "权限不足"),
    OPTIMISTIC_LOCK    (40009, HttpStatus.CONFLICT,      Retry.CLIENT, "数据已被他人修改"),
    DUPLICATE_CONTENT  (40021, HttpStatus.CONFLICT,      Retry.NEVER,  "内容重复"),
    // 5xx 自身
    RAG_RETRIEVE_FAILED(50010, HttpStatus.INTERNAL_SERVER_ERROR, Retry.SAFE, "知识检索失败"),
    // 上游 LLM
    LLM_TIMEOUT        (50210, HttpStatus.BAD_GATEWAY,   Retry.SAFE,   "模型响应超时"),
    LLM_RATE_LIMITED   (50211, HttpStatus.TOO_MANY_REQUESTS, Retry.BACKOFF, "模型限流"),
    LLM_CONTENT_BLOCKED(50212, HttpStatus.BAD_GATEWAY,   Retry.NEVER,  "内容被安全策略拦截");
    // ... code / httpStatus / retryPolicy / defaultMessage
}
```

收益（三重）：
1. **Agent 重试逻辑变声明式**：`ToolRuntimeManager` / `DevOpsAgentEngine` 里的重试判断从"看异常类型 + 看消息字符串"变成 `error.retryPolicy()`。你的 `ToolFailureType.java` 已经有这个雏形，**把它提升为全局错误体系**。
2. **前后端契约可测**：导出枚举为 JSON，前端 `bizCode.ts` 对齐，加一致性测试。
3. **面试可讲**：「错误码携带重试语义」是很多候选人讲不出来的设计。

---

### ★★★★☆ 3.3 审计中间件（谁在什么时候改了什么）

**new-api 的做法**（`middleware/audit.go`）：一个包装 `gin.ResponseWriter` 的中间件，自动记录所有写操作：
- 用**有上限的缓冲区**复制响应体（避免大响应吃内存），解析 `success` 字段判断业务成败；
- 用 `auditRouteActions` map 把「HTTP 方法 + 路由模板」映射为**语言无关的 action 标识**（`"DELETE /api/user/:id/reset_passkey" → "user.reset_passkey"`），未命中的写操作兜底为 `generic`；
- 前端按 action 做 i18n 展示。

**你的现状**：`TicketActivity` 实体记录了工单操作流水，但那是**业务级、手工埋点、只覆盖工单域**。知识库删除、分类调整、审批通过、告警确认、用户角色变更**都没有统一审计**。

**建议**：新增 `common/audit/AuditInterceptor.java`（HandlerInterceptor 或 `@Around` AOP）：

| 记录字段 | 来源 |
| :-- | :-- |
| `traceId` | TraceContext |
| `actorId` / `actorName` | `StpUtil.getLoginId()` |
| `action` | 路由模板映射表，如 `knowledge.doc.delete` |
| `targetType` / `targetId` | 路径变量 |
| `success` | 响应体 `code == 0` |
| `ip` / `ua` / `durationMs` | 请求 |
| `payloadDigest` | 请求体摘要（**脱敏后**，不存全文） |

只拦 `POST/PUT/PATCH/DELETE`，异步写库（复用你已有的 `AgentCallLog` 落库模式）。

> **对你的场景是刚需**：L3/L4 阶段 AI 会执行自愈动作，「**AI 在 03:17 自动重启了 order-service 的 pod-3**」这条审计记录是**合规底线**，不是加分项。现在补，L3 时就不用返工。

---

### ★★★★☆ 3.4 限流中间件（当前完全缺失）

**new-api 的做法**：`middleware/rate-limit.go` + `model-rate-limit.go`，基于 Redis LIST 的滑动窗口（`LLen` + `LIndex(-1)` 比较时间戳 + `Expire`），且**区分"总请求数"和"成功请求数"**两个计数器（`MRRL` / `MRRLS`）—— 失败请求不占用户配额，这个细节很讲究。

**你的现状**：`grep -rln "RateLimit|Bucket4j"` → 只在 `DingTalkNotifier`（钉钉自身限流）和 `ToolFailureType`（枚举名）里出现。**API 层零限流**。

**风险（你的 `docs/03-quality-assurance/全路径异常闭环与综合审查报告.md` 自己也提到了"高并发防刷"）**：
- `/api/v1/chat/stream` 是 SSE + 真实 LLM 调用，**单请求成本高、耗时长、占用 Tomcat 线程**。一个脚本循环调用就能打爆额度和连接池；
- `/api/v1/alerts/webhook` 是**免鉴权**端点（在 WebConfig 白名单里！），Alertmanager 告警风暴或恶意构造可以无限写库。

**建议**（分两层）：
1. **全局层**：`RateLimitInterceptor`，Redis 滑动窗口，按 `loginId`（未登录按 IP）限流。写进 `application.yml`：
   ```yaml
   devops.ratelimit:
     chat-stream: { capacity: 20, window: 60s }     # 每用户每分钟 20 次 AI 对话
     webhook:     { capacity: 300, window: 60s }    # 告警 webhook 按来源 IP
     default:     { capacity: 300, window: 60s }
   ```
2. **成本层**：你已有 `CostQuotaManager`，把它接到限流之后 —— **限流挡住"频率"，配额挡住"总花费"**，两者互补。

---

### ★★★★☆ 3.5 配置热更新（DB-backed 配置中心）

**new-api 的做法**（`setting/config/config.go` + `model/option.go`）：
- `ConfigManager` 用 `Register(name, structPtr)` 注册配置模块，反射把 DB 的 `options` 表（`key/value` 两列）加载进结构体；
- 按模块前缀分组（`ratio.xxx` / `console.xxx`）；
- 管理端改配置 → 写 `options` 表 → 广播到所有实例内存 → **无需重启**。

**你的现状**：全部 `@Value("${devops.ai.xxx}")` 静态注入，改任何参数都要**重启服务**。而你的 `application.yml` 里恰恰有大量**运营期需要调的旋钮**：

```yaml
devops.ai.semantic-cache.similarity-threshold: 0.85    # ← 调一次要重启
devops.ai.hallucination.min-similarity-score: 0.73     # ← 调一次要重启
devops.ai.retrieval.hybrid-enabled: false              # ← 调一次要重启
devops.ai.retrieval.vector-weight: 0.65                # ← 调一次要重启
devops.ai.mode: MOCK|REAL                              # ← 调一次要重启
```

**建议**（Spring 原生方案，无需引 Nacos）：
```java
@Component
@ConfigurationProperties(prefix = "devops.ai")
@RefreshScope   // 或自建 SettingRegistry
public class AiRuntimeSettings { ... }
```
更轻的做法：新建 `sys_setting(key, value, updated_at)` 表 + `SettingService`（带 Caffeine 本地缓存 + Redis pub/sub 失效广播），管理端 `/api/v1/settings` 暴露读写。

> **面试杀手锏升级**：你现在讲「相似度阈值 0.85 让缓存命中率 85%」，面试官会问「**这个 0.85 你怎么调出来的？**」。有了热更新 + 命中率看板，你可以答「灰度调阈值 → 观察 15 分钟命中率与幻觉率曲线 → 二分收敛」。**这就是从"背数字"变成"讲方法论"。**

---

### ★★★☆☆ 3.6 Adaptor 接口 —— 模型厂商解耦

**new-api 的做法**（`relay/channel/adapter.go`）：一个 `Adaptor` 接口定义 15 个方法（`GetRequestURL` / `SetupRequestHeader` / `ConvertOpenAIRequest` / `DoRequest` / `DoResponse` / `GetModelList`...），40+ 厂商各实现一份，上层完全不知道下游是谁。

**你的现状**：`AiModelConfig.java` 硬编码阿里云百炼，通过 `@ConditionalOnProperty(devops.ai.mode)` 切 Real/Mock。你的架构文档声称「底层大模型连接池与具体厂商 100% 解耦」——**实际上只解耦了 LangChain4j 层面，配置层是硬绑定的**（`devops.ai.alibaba.*` 前缀写死）。

**建议（轻量版，不要照抄 15 方法接口）**：

```java
public interface LlmProvider {
    String name();                                    // "alibaba" / "deepseek" / "mock"
    ChatModel chatModel(ModelTier tier);              // TURBO / REASONER
    StreamingChatModel streamingModel(ModelTier tier);
    EmbeddingModel embeddingModel();
    int embeddingDimension();                         // ← 维度铁律由 provider 自报
    default boolean supportsToolCalling() { return true; }
}
```
配置改为：
```yaml
devops.ai:
  provider: ${AI_PROVIDER:mock}      # mock | alibaba | deepseek | openai
  providers:
    alibaba: { api-key: ..., base-url: ..., turbo-model: qwen-plus, ... }
    deepseek: { api-key: ..., base-url: ..., turbo-model: deepseek-chat, ... }
```

**收益**：① 换厂商改一行配置；② **你的 README 声称支持 deepseek 但代码只有阿里云——这个不一致会在面试被戳穿**，实现 Provider 抽象后名副其实；③ 可做"主备厂商自动 failover"（阿里云 429 → 自动切 deepseek），这正是 new-api 渠道重试的精髓，也是 L4 高可用的基础。

---

### ★★★☆☆ 3.7 HybridCache（Redis 挂了也不崩）

**new-api 的做法**（`pkg/cachex/hybrid_cache.go`）：泛型混合缓存，Redis 可用时走 Redis，不可用/未启用时**自动降级到进程内 hot cache**，且带命名空间与 codec 抽象，Redis 操作有独立超时（读 2s / scan 30s / del 10s）。

**你的现状**：`SemanticCacheService`(469 行) + `HotMemoryStore` 已经有"热/冷"两层的雏形。缺的是：**Redis 不可用时的显式降级路径与超时约束**。运维平台在故障期最需要可用，而故障期恰恰是 Redis 最可能出问题的时候——**"排障工具在故障时挂掉"是最讽刺的失败模式**。

**建议**：给 Redis 操作统一包一层 `resilientRedis(op, fallback, timeout)`，Redis 异常时降级到本地 Caffeine 并打 WARN（带 traceId），不抛给上层。

---

### ★★★☆☆ 3.8 OpenAPI 文档（当前缺失）

new-api 维护 `docs/openapi/api.json` + `relay.json`。你的项目 **`grep -c springdoc pom.xml` → 0**，93 个 REST 端点（`TicketController` 单个就 34 个）**没有任何机器可读的 API 文档**，只有手写的 `docs/05-development-design/03-API接口设计.md`（必然与代码漂移）。

**建议**：加 `springdoc-openapi-starter-webmvc-ui`（一个依赖 + 零代码即可出 Swagger UI），然后：
- 在 CI 里导出 `openapi.json` 并 commit，**diff 即 API 变更评审**；
- 前端可用它生成 TS 类型（`openapi-typescript`），消灭 `api/types.ts` 手工维护的漂移。

---

### 3.9 后端不建议照抄的部分

| new-api 做法 | 不建议原因 |
| :-- | :-- |
| 多数据库兼容 + 手写 SQL 方言分支 | 你强依赖 pgvector，兼容成本极高收益为零 |
| `common.Marshal` 包裹所有 JSON | Go 因为要换 sonic 加速才这么做；Java 侧 Jackson 由 Spring 统一管理，无需再包一层 |
| 计费表达式引擎 | 过度设计，L5 再说 |
| 独立 relaykit 子模块 | 规模未到，过早拆分 |

---

## 四、关于 AGENTS.md：我的判断

### 4.1 new-api 的 AGENTS.md 为什么值得学？

它做对了**三件绝大多数项目做错的事**：

**① 只写「本项目独有 + 违反即出事故」的约束，不写通用道理**

对比一下典型的坏 AGENTS.md（"要写可读的代码"、"注意性能"）和它的写法：

> - 标准 `SELECT ... FOR UPDATE` 行锁**必须**用 `lockForUpdate(tx)`。不要用 GORM v1 的 `tx.Set("gorm:query_option", "FOR UPDATE")`，因为 **GORM v2 会静默忽略它，锁根本没加上**。
> - 绝不要用裸转换把配额转成 int（`int(float64(quota) * ratio)`）。所有配额舍入集中在 `common/quota_math.go`。**饱和边界是 int32，因为配额列在数据库里是 32 位整数**。

**每一条都是"血泪教训 + 为什么"**。AI（和新人）读完能立刻避坑。

**② 明确「什么样的测试是垃圾测试」**

这是它最独特的部分，我从没在其他项目见过写得这么细的：

> - 不要添加只为提高覆盖率数字、只证明代码能跑、或锁定实现细节的测试。
> - 避免用随机输入、大循环、sleep、时间比较、只断言日志构造的假 fuzz/压力/冒烟/性能测试。
> - 避免同一分支换个名字测两遍但没有新不变量的重复测试。
> - 组件交互测试要从用户视角查询元素；**禁止直接断言组件内部 state、私有函数调用次数或无用户意义的 DOM 层级**。
> - **禁止 mock 被测模块自身**，也不要把生产逻辑复制到测试里算期望值。

对**你**尤其对症：你后端 `src/test` 只有 12 个测试类、**0 个 Controller 层测试**，前端 20 个测试文件。接下来一定会补测试，**先立好"什么不算测试"的标准，比补 100 个空转测试重要得多**。

**③ 明确 AI 协作的诚信要求**

它在 PR 规范里写：如果当前 git user 不是历史核心开发者，**PR 描述里必须显式声明代码是 AI 生成/AI 辅助的**。并且配了 CI（`peakoss/anti-slop`）自动关闭"纯 AI 生成、无人工参与"的 PR。

> 这一点你**不需要照抄**（你是个人项目），但它反映的价值观值得吸收：**AI 产出必须经人工验证后才能声称完成**。你的 CLAUDE.md 里"铁律"章节其实是同一个意思。

### 4.2 但有两点它做得不好，你不要学

| 问题 | 说明 |
| :-- | :-- |
| **开头一句 `DO NOT send optional commentary`** | 这是把"对话风格偏好"塞进项目规范文件。规范文件应该只讲**项目约束**，交互偏好属于个人配置。 |
| **"Protected project information" 品牌保护条款** | 用规范文件禁止 AI 修改品牌标识，这是防御性条款，不是工程规范。你的项目不需要。 |

### 4.3 你的项目**必须**生成 AGENTS.md —— 而且这是当前 ROI 最高的动作

**核心理由：你的 `CLAUDE.md` 已经崩了。**

| 指标 | 你的 CLAUDE.md | new-api 的 AGENTS.md | 健康值 |
| :-- | :-- | :-- | :-- |
| 行数 | **3206** | 195 | < 300 |
| 体积 | **280 KB** | 15.7 KB | < 25 KB |
| 章节结构 | **有两个"七"、两处编号冲突** | 线性清晰 | — |
| 内容构成 | 规范 + 决策记录 + 进度报告 + 历史缺陷清单 + 面试话术，**全混在一起** | 纯约束 | 纯约束 |
| 「六、技术决策记录」 | **第 158 行 → 第 1042 行，占 884 行** | — | 应独立归档 |

**后果**：
1. **AI 每轮对话读它就消耗数万 token**，挤压真正干活的上下文预算；
2. **信噪比极低** —— 真正的硬约束（如"向量维度必须全链路一致"）淹没在进度报告里；
3. **必然过期** —— 混了进度信息的文档，一周不更新就有一半是错的，AI 会照着过期信息干活。

而且，**同一个 `CLAUDE.md` 名字下放了两个完全不同的东西**：
- 根目录 `CLAUDE.md`（3206 行）= 全栈工作契约
- `devops-platform-frontend/CLAUDE.md`（107 行）= **一份前端兜底加固清单（14 项 P0/P1/P2 已全部 ✅）**，这根本不是规范文件，是一份已完成的任务清单。

**建议的文档架构重整**：

```
AGENTS.md                          ← 【新建】≤ 250 行，根级硬约束，AI 每次必读
devops-platform-frontend/AGENTS.md ← 【新建】≤ 200 行，前端专属规范
CLAUDE.md                          ← 【瘦身】改为 20 行的指针文件，指向 AGENTS.md
docs/09-decisions/
  ├── ADR-index.md                 ← 从 CLAUDE.md「六、技术决策记录」884 行迁入
  └── ADR-0001..00NN.md            ← 每个决策一个文件，带 状态/背景/决策/后果
docs/06-implementation-progress/   ← 从 CLAUDE.md「七、当前工作状态」迁入（已有此目录）
docs/10-archive/
  └── frontend-hardening-checklist.md  ← 前端 CLAUDE.md 归档（14 项已完成）
```

> **注意**：主流 AI 编码工具（Claude Code / Codex / Cursor / Copilot Workspace）现在都优先读 `AGENTS.md`（这是 2025 年后逐渐形成的跨工具约定），`CLAUDE.md` 是 Claude 专属。**用 AGENTS.md 做主文件、CLAUDE.md 做软链/指针**，是当前最优解。

我已按此建议生成了 `AGENTS.md` 与 `devops-platform-frontend/AGENTS.md`（见本次改动），内容 100% 基于你项目**真实存在的代码与真实踩过的坑**，没有一条通用废话。

---

## 五、其他可行性优化建议（超出 new-api 对标范围）

### 5.1 ⚠️ 已发现的真实缺陷（建议优先修）

| # | 缺陷 | 位置 | 影响 | 修复成本 |
| :-- | :-- | :-- | :-- | :-- |
| **D1** | **traceId 每次调用都重新生成**，同一请求响应与日志的 traceId 不一致，且日志根本没打 traceId | `ApiResponse.generateTraceId()` | 排障能力为 0，字段是纯装饰 | 半天（见 §3.1） |
| **D2** | **`/api/v1/alerts/webhook` 免鉴权且无限流** | `WebConfig.excludePathPatterns` | 任意人可无限写告警库；告警风暴可打爆服务 | 半天（限流 + 共享密钥校验） |
| **D3** | **CORS `allowedOriginPatterns("*") + allowCredentials(true)`** | `WebConfig.addCorsMappings` | 注释里写"生产如需收紧" —— 但这就是生产配置文件生效的路径，等于任意站点可带凭证调你的 API | 1 小时（按 profile 区分，prod 走白名单） |
| **D4** | **后端 0 个 Controller 层测试**，93 个端点无契约测试 | `src/test/` | 任何重构都可能静默破坏 API 契约 | 持续（先给核心 8 个端点补 `@WebMvcTest`） |
| **D5** | **无 CI**（`.github/` 目录不存在） | 仓库根 | typecheck/lint/test 全靠人工记得跑 | 2 小时（见 §5.2） |
| **D6** | **无 Dockerfile**（只有 `docker-compose.dev.yml` 起中间件） | 仓库根 | README 承诺"Docker 容器化一键部署"（Day10 计划）**尚未兑现** | 半天 |
| **D7** | README 与代码不一致：README/CLAUDE.md 多处写 `deepseek-chat / deepseek-reasoner`，实际代码是阿里云 `qwen-plus / qwen-max` | README.md / CLAUDE.md / AiModelConfig | 面试时被问到会露馅 | 10 分钟（改文档）或 1 天（做 Provider 抽象，见 §3.6） |

### 5.2 建议新增 CI（对标 new-api `.github/workflows/ci.yml`）

new-api 的 CI 极简但有效：后端 `go vet` → `go build` → `make test`；前端 `bun install --frozen-lockfile` → `typecheck` → `test`。

你的对应版本（`.github/workflows/ci.yml`）：

```yaml
name: CI
on:
  pull_request:
  push: { branches: [main] }
concurrency:
  group: ci-${{ github.ref }}
  cancel-in-progress: true
jobs:
  backend:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: '21', cache: maven }
      - run: ./mvnw -B -ntp verify        # compile + test
  frontend:
    runs-on: ubuntu-latest
    defaults: { run: { working-directory: devops-platform-frontend } }
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with: { node-version: '22', cache: npm,
                cache-dependency-path: devops-platform-frontend/package-lock.json }
      - run: npm ci
      - run: npm run typecheck
      - run: npm run lint
      - run: npm run test
      - run: npm run knip            # 死代码检测，你已配好 knip.config.ts
      - run: npm run build           # 保证产物可构建
```

> 你 `package.json` 里已经有 `verify` 脚本（`typecheck && lint && test`），**CI 只是把它自动化**。成本 2 小时，收益是从此不会再有"合并后才发现 typecheck 挂了"。
>
> **求职加分**：GitHub 仓库首页那个绿色 ✅ CI badge，对应届生简历的可信度提升非常直观。

### 5.3 建议补齐 Dockerfile（兑现 Day10 承诺）

对标 new-api 的多阶段构建（前端 → 后端 → 精简运行时）：

```dockerfile
# 阶段 1：构建前端
FROM node:22-alpine AS web
WORKDIR /build/web
COPY devops-platform-frontend/package*.json ./
RUN npm ci
COPY devops-platform-frontend/ ./
RUN npm run build

# 阶段 2：构建后端（前端产物打进 static，单容器交付）
FROM maven:3.9-eclipse-temurin-21 AS api
WORKDIR /build
COPY pom.xml .mvn/ ./
RUN mvn -B -ntp dependency:go-offline
COPY src ./src
COPY --from=web /build/web/dist ./src/main/resources/static
RUN mvn -B -ntp clean package -DskipTests

# 阶段 3：运行时
FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache tzdata curl && ln -sf /usr/share/zoneinfo/Asia/Shanghai /etc/localtime
WORKDIR /app
COPY --from=api /build/target/*.jar app.jar
EXPOSE 8088
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s \
  CMD curl -fsS http://localhost:8088/ai/api/v1/health/live || exit 1
ENTRYPOINT ["java","-XX:MaxRAMPercentage=75","-jar","/app/app.jar"]
```

> 注意：你 `server.servlet.context-path: /ai`，健康检查路径要带前缀。你已有 `HealthCheckController`（3 个端点），确认其中有 liveness/readiness 区分。

### 5.4 前端体积与首屏（你已有 manualChunks，可再进一步）

你的 `vite.config.ts` 已经做了不错的 `manualChunks`（echarts / element / icons / query / vue 分离）。可再优化：

| 项 | 现状 | 建议 |
| :-- | :-- | :-- |
| ECharts 全量引入 | `echarts: ^6.1.0` 整包 | 改按需 `echarts/core` + 只注册用到的 `LineChart/BarChart/PieChart` + `CanvasRenderer`，通常能省 60%+ |
| md-editor-v3 + wangEditor **两个富文本编辑器** | 同时依赖 `@wangeditor/editor` 和 `md-editor-v3` | **二选一**。你的 `KnowledgeRichEditor.vue` 与 `KnowledgeEditor.vue`(1970 行) 需要确认是否真的两套都在用，若是，这是明显的重复依赖 |
| 路由懒加载 | ✅ 已做，且带 retry + skeleton（写得很好） | 保持 |
| 构建产物体积监控 | 无 | CI 里加 `size-limit` 或简单的 `du -sh dist` 阈值断言 |

### 5.5 Agent 链路的可观测性（你的差异化优势点）

new-api 有 `pkg/perf_metrics`（TTFT、生成耗时、成功率的热桶聚合 + 定期 flush）。**你的场景更需要**，因为 Agent 链路比单次转发复杂得多：

建议给每次 Agent 调用记录一条结构化 trace（可扩展现有 `AgentCallLog`）：

```
traceId | intent | routedModel(turbo/reasoner) | cacheHit(true/false)
retrievalMs | topK | maxSimilarity | hallucinationLayer(L1~L4 触发情况)
toolCalls[] | toolRetries | ttftMs | totalMs | promptTokens | completionTokens | costCny
```

然后 Dashboard 上加一个 **「Agent 健康度」** 面板：模型分流比例、缓存命中率、幻觉拦截率、P95 TTFT、单次平均成本。

> **这是把你 README 里那些静态数字（"命中率 >85%"、"幻觉率 4.2%"、"1.8s"）变成实时看板的关键**。面试时"我这里有实时数据"和"我文档里写了"是天壤之别。

### 5.6 数据库迁移工具化

你现在有 `sql/init.sql` + `migration_v11 ~ v23` 共 13 个手工迁移脚本，**靠人记得按顺序执行**。建议引入 **Flyway**（Spring Boot 集成一行配置）：

```
src/main/resources/db/migration/
  V1__init.sql
  V11__knowledge_category.sql
  ...
  V23__approval_request.sql
```
收益：启动时自动按版本执行、有 `flyway_schema_history` 表可查、CI 里能用 Testcontainers 起真 PG 验证迁移。**成本约 1 小时（改文件名 + 加依赖），收益是彻底消除"忘记跑迁移导致启动报错"这类问题。**

### 5.7 文档治理（你的文档已经比代码还重）

现状：`docs/` 下 **60+ 个 markdown**，命名规则混乱（`01-project-governance/` 目录 与 `01-AI-Interview-QA-Manual.md` 文件同级同号；`02-architecture-design/` 与 `02-LawFirm-AI-Enhancement-Plan.md`；根目录还散落 6 个中文名报告）。而且有 `docs/文档状态清单.md` 说明你自己也意识到了。

**建议**：
1. ~~根目录 6 个中文报告（`工单模块*.md`、`前后端联调测试指南.md`）→ 移入 `docs/06-implementation-progress/`~~
   —— **2026-08-27 已处理**：核查后直接删除而非移动。它们是 2026-07 的一次性过程记录
   （「已修复 6 个问题」「待规划/待执行」），引用的 `sql/ticket_extensions.sql` 与
   `devops-platform-backend/` 路径均已不存在，且零文档引用。移进 docs/ 只是把过期内容
   换个位置继续误导——正是本条第 4 点要防的事；
2. 编号目录与编号文件不要混用，散落文件归入对应目录；
3. `docs/README.md` 做导航索引（README 里那个目录树已过时——它写的是 `/home/user/` 下只有 4 个 docs 子目录，实际有 7 个）；
4. **归档已完成的方案文档**，避免 AI 读到过期方案照着实现。

---

## 六、优先级路线图（建议执行顺序）

| 阶段 | 事项 | 预估 | 价值 |
| :-- | :-- | :-- | :-- |
| **P0 · 本周** | ① 生成 AGENTS.md + 拆分 CLAUDE.md（**已随本次交付**） | 已完成 | AI 协作效率立刻翻倍 |
| | ② 修 D1 traceId（Filter + MDC + logback pattern + 响应头） | 0.5d | 排障能力从 0 到 1 |
| | ③ 修 D2/D3（webhook 限流+密钥、CORS 按 profile 收紧） | 0.5d | 消除真实安全洞 |
| | ④ 加 CI（`.github/workflows/ci.yml`） | 2h | 质量门禁 + 仓库 badge |
| **P1 · 两周内** | ⑤ `BizError` 枚举错误码体系 + 前端 bizCode 对齐 + 一致性测试 | 1d | 前后端契约单一真相 |
| | ⑥ 限流中间件（Redis 滑动窗口） | 1d | 保护 LLM 成本与线程池 |
| | ⑦ Dockerfile 多阶段 + 补 `docker-compose.yml`（生产版） | 1d | 兑现 Day10 承诺 |
| | ⑧ springdoc OpenAPI | 0.5d | 93 个端点终于有机器可读文档 |
| | ⑨ Flyway 迁移 | 1h | 消除手工迁移风险 |
| **P2 · 一个月内** | ⑩ `data-table` 抽象层 + 用它重写 AlertList（最小的那个）验证 | 2d | 为拆分巨型 SFC 铺路 |
| | ⑪ 拆 TicketList / TicketDetail 到 `features/ticket/` | 3d | 消除最大技术债 |
| | ⑫ URL 即状态（useUrlState） | 1d | 运维场景刚需 |
| | ⑬ 审计中间件 | 1d | L3/L4 合规前置 |
| | ⑭ 配置热更新（sys_setting + SettingService） | 2d | 阈值可调优，面试可讲方法论 |
| | ⑮ Agent 健康度看板 | 2d | 把静态数字变实时指标 |
| **P3 · 有余力** | ⑯ LlmProvider 抽象 + 主备 failover | 2d | 名副其实的厂商解耦 |
| | ⑰ vue-i18n | 2d | 国际化 |
| | ⑱ Zod 表单 schema | 1d | 类型/校验单一真相 |
| | ⑲ 文档治理与归档 | 0.5d | 减少 AI 读到过期方案 |

---

## 七、一句话总结

> **new-api 值得你借鉴的不是它的代码，而是它「把每一次事故都固化成一条可执行约束」的工程文化** —— 这个文化的唯一载体就是那份 AGENTS.md。
>
> 你项目的架构分层（六层 Clean Architecture）其实**比 new-api 的扁平 Go 包更规范**，真正的短板在**可观测性（traceId 形同虚设）、质量门禁（无 CI、后端零 Controller 测试）、前端组件化（2655 行的 SFC）** 三处。
>
> 先把 AGENTS.md 立起来、把 traceId 修好、把 CI 跑起来 —— 这三件事加起来不到两天，但会让后面所有工作的质量下限直接抬一个台阶。
