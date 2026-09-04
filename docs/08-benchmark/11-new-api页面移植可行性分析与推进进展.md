# 11 · new-api 页面移植可行性分析 + 推进进展

> 日期：2026-08-24
> 产出：`60501c9`（组件测试）、`820a7fa`（契约自动化）
> 测试：717 → **748**

---

# 第一部分：能否直接复制 new-api 的页面？

## 结论先行

**不能直接复制，但其中两个页面的「产品设计」值得照搬，且后端数据已经就绪。**

原因不是"不想抄"，而是**技术栈完全不同**——这不是改改就能用的差异：

| 维度 | new-api | OpsBrain AI |
|---|---|---|
| 框架 | **React 19** | **Vue 3.5** |
| 路由 | TanStack Router（文件路由 + 代码生成） | Vue Router 5 |
| UI 库 | base-ui + 自建 62 个 shadcn 风格组件 | Element Plus |
| 图表 | VisActor VChart | ECharts |
| 表格 | TanStack Table + Virtual | el-table |
| 表单 | react-hook-form + zod | 手写 validate |
| 样式 | Tailwind | SCSS + 设计令牌 |

**`.tsx` 文件在 Vue 项目里一行都跑不了。** 所谓"复制"实际是**照着重写**。

## 工作量实测

我克隆了 new-api 仓库逐个统计：

| 模块 | 文件数 | 代码行数 |
|---|---|---|
| playground（游乐场） | 49 | 6,186 |
| usage-logs（使用日志） | 37 | 8,806 |
| dashboard（数据看板） | 37 | 9,805 |
| profile（个人资料） | 34 | 6,146 |
| **合计** | **157** | **30,943** |

这四个模块还引用了 **80 个** features 目录之外的共享组件/hooks/lib，
其中 31 个来自 `@/components/ui/`（共 62 个 shadcn 组件）。

**要"复制"就得先把这 62 个 UI 基础件用 Vue 重写一遍**，
而项目已经有 Element Plus 了——等于同时维护两套 UI 体系。

## 更关键的：业务不匹配

new-api 是 **AI API 网关计费系统**，OpsBrain 是 **智能运维平台**。
在 `usage-logs` 模块里 grep 业务名词：

```
channel  35 次    quota  26 次    token  20 次
model    16 次    billing 10 次
```

这些是"渠道/配额/令牌/计费"——OpsBrain 后端**没有任何对应概念**。
new-api 的 `usage-logs` 调 `/api/log`、`/api/task`，也不存在于 OpsBrain。

照搬这些页面会得到一堆**没有数据能填的空壳**。

---

## 逐页判断与建议

### ❌ 游乐场（playground）—— 不建议做

new-api 的 playground 是"选模型 → 调参数 → 试对话 → 看计费"，
面向的是**用 API 的开发者**。

OpsBrain 的用户是**运维工程师**，他们要的不是"试模型"，
而是"解决线上问题"。项目已有的 `/ai-chat` 就是这个场景的正确形态。

**做 playground 等于把产品往"AI 网关"方向拽，偏离运维定位。**

如果真正的需求是"调 prompt / 比较模型效果"，那是**内部工具**，
不该混进面向运维的产品里。

### ⚠️ 数据看板（dashboard）—— 已有，建议增强而非重做

OpsBrain 的 `/dashboard` 已经有：AI 调用 KPI、缓存命中率、成本趋势、
工单趋势、闭环度量（MTTA/MTTM/MTTR）、根因分类、SLA 风险面板。

**覆盖面并不比 new-api 差**，只是图表类型少一些。

可借鉴的是它的**信息密度组织**：
- 指标卡支持环比（本项目只有绝对值，看不出"是变好还是变坏"）
- 图表支持时间窗口切换（本项目 Dashboard 固定 7 天）

建议：**增量加两个能力**（约 1 天），不重做。

### ✅ 使用日志 / 任务日志 —— 建议做，且数据已就绪

这是四个里**最值得做的**，因为 OpsBrain 后端**已经有两张表**：

| 表 | 用途 | 关键字段 |
|---|---|---|
| `sys_agent_call_log` | AI 调用审计 | trace_id、model_name、is_cached、latency_ms、cost_rmb、operation_type、operator_id、affected_resources |
| `sys_operation_audit`（v25） | 通用写操作审计 | trace_id、actor_id、action、target_type/id、http_method/path、status_code |

**数据一直在写，但前端没有任何页面能看。** 这是实打实的缺口：
- 出了问题想查"谁在什么时候改了这张工单" → 只能连数据库
- 想知道"这周 AI 花了多少钱、哪些查询最慢" → Dashboard 只有聚合值，无法下钻

而且两张表都有 `trace_id`——**可以串成完整链路**，
这正是运维平台该有的能力，比 new-api 的按用户计费列表更有价值。

**建议方案（约 2-3 天）**：

```
/governance/audit-logs   操作审计（sys_operation_audit）
  筛选：操作者 / 动作类型 / 目标类型 / 时间范围 / 状态码
  下钻：点 trace_id → 关联的 AI 调用 + 工单变更

/governance/ai-calls     AI 调用日志（sys_agent_call_log）
  筛选：模型 / 是否命中缓存 / 耗时区间 / 成本区间 / 时间范围
  列表：耗时、成本、是否缓存、影响资源
  下钻：点 trace_id → 完整问答 + 引用来源
```

路由已经在 `router/index.ts` 里占位了（`/governance/audit-logs`，
当前指向 `FutureCapability` 占位页），**把占位换成真实页面即可**。

复用现成的：`DataStateBoundary`（四态）、`ServerPagination`、
`useUrlFilters`（筛选可分享）、`RelativeTime`。
**不需要新建任何基础件。**

### ⚠️ 个人资料（profile）—— 建议做精简版

new-api 的 profile 有 6146 行，因为它包含 API 令牌管理、邀请码、
额度充值、OAuth 绑定——这些 OpsBrain 都没有。

OpsBrain 现在只有一个 `ProfileDialog`（弹窗改昵称）。
真正缺的是**改密码**——`sys_user` 表有密码字段，但前端没有入口，
用户只能找管理员。登录页那句"首次登录后请及时改密"目前是**做不到的**。

**建议（约 0.5 天）**：在现有 `ProfileDialog` 里加"修改密码"分区，
不做独立页面。需要后端补一个 `PUT /auth/password` 端点。

---

## 汇总建议

| 页面 | 建议 | 投入 | 理由 |
|---|---|---|---|
| 游乐场 | ❌ 不做 | — | 偏离运维定位，`/ai-chat` 已覆盖真实场景 |
| 数据看板 | ⚠️ 增强 | 1d | 已有且不差，加环比 + 窗口切换即可 |
| **使用/任务日志** | ✅ **做** | 2-3d | **后端数据已就绪、路由已占位、当前完全看不到** |
| 个人资料 | ⚠️ 精简版 | 0.5d | 只补"改密码"，其余功能 OpsBrain 没有对应概念 |

**优先级：使用日志 > 个人资料改密 > 看板增强 > 游乐场（不做）**

**可以借鉴的不是代码，是产品判断**：new-api 把"可观测性"做成一等公民
（日志能筛能下钻），这个思路对运维平台同样成立——只是要用 OpsBrain
自己的数据（审计 + AI 调用）和自己的技术栈实现。

---

# 第二部分：本轮推进进展（建议 1/2/3）

## 建议 1：启用 CI —— ❌ 仍被阻塞

我尝试了直接提交 `.github/workflows/ci.yml`，被服务端拒绝：

```
refusing to allow a GitHub App to create or update workflow
`.github/workflows/ci.yml` without `workflows` permission
```

**这是 GitHub App 的权限限制，我无法绕过。** 已回滚该提交。

需要你在本地执行（30 秒）：

```bash
git pull && mkdir -p .github/workflows
git mv ci/github-actions-ci.yml .github/workflows/ci.yml
git commit -m "ci: 启用工作流" && git push
```

**这仍是唯一硬阻塞**——后端 50+ 文件从未编译过，本轮新增的
`ContractExportTest` 也未运行过。

## 建议 2：组件测试 —— ✅ 首批完成（17 例）

`TicketList` 2597 行此前零测试，而近几轮在它里面发现了三类缺陷。
本轮补了风险最高的两块：

**批量操作（7 例）**——一次影响几十张工单：
- 可达性计算（全不可达置灰 / 部分可达 N/M / void 终态）
- **执行时只对可达项调后端**，不把注定失败的请求打过去
- 跳过项数如实写进提示

**筛选 ↔ URL 同步（10 例）**：
- 带参进入应用筛选、keyword 回填搜索框
- 非法值忽略、默认值不入 URL、空数组不入 URL
- 多项变化合并成一次导航

**已回退验证**：去掉可达性过滤后 3 条立即失败。

**踩坑记录**：写回用例第一版四条全挂，排查后确认**不是代码 bug**——
`useUrlFilters` 用 `flush: 'post'` 且 `router.replace` 异步，
只 await nextTick 拿到的是未更新的 query。已抽 `flushUrlSync` 并注明原因。

## 建议 3：契约自动化 —— ✅ 完成（14 例）

采用"后端反射导出 JSON + 前端消费"方案（~1 天），而非 OpenAPI（3-5 天）。

**后端** `ContractExportTest`：反射读 `@Size`、调 `Status.nextStates()`、
遍历 `BizError`，导出排序后的稳定 JSON。刻意不启动 Spring 上下文
（启动一次十几秒，而这只是导出任务），手写 60 行序列化避免引 Jackson。

**前端** `backendContract.test.ts`：
- 状态机**逐 from×to 穷举 25 组**，漂移时报出具体是哪一对
- 字段长度断言「前端 ≤ 后端」
- **retry 语义逐码比对**——这条最有价值：后端标 NEVER 前端标 SAFE，
  会导致对 40021 内容重复反复自动重试；反过来则本该自动恢复的抖动
  被当成硬失败甩给用户

**已注入假漂移实测**：4 条用例失败并准确报出
`CLOSED→PROCESSING: 后端=false 前端=true`。

**已知限制（写进 README 不隐瞒）**：`ContractExportTest` 尚未实际运行过
（无 JDK）。当前 JSON 按后端源码真实取值生成，字段来源与该测试一致，
CI 启用后首次运行应确认 diff 为空。

---

## 下一步

1. **你启用 CI**（30 秒）→ 我修首次编译错误 + 核对契约 diff
2. 继续补 `TicketDetail` 组件测试（2600 行仍零测试）
3. 若认可第一部分建议，可着手**使用日志页**——后端数据已就绪、
   路由已占位、基础件齐全，是当前性价比最高的功能增量
