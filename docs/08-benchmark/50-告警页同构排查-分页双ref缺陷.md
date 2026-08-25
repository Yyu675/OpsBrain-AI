# 50 · 告警页同构排查 · 分页双 ref 缺陷

> 本轮四次提交，CI 全绿。前端 1511 例（+33）。

---

## 一、本轮做了什么

延续上一轮的判断：**同构文件是最高命中率的排查方向**。
`AlertList` / `AlertDetail` 与 `TicketList` / `TicketDetail` 同构，
后者踩过的坑在前者极可能原样存在。

| # | 内容 | 性质 | 产出 |
|---|------|------|------|
| 1 | AlertList 页码双 ref | **修缺陷** | 10 例 |
| 2 | AlertDetail 派生逻辑 | 补测试 | 16 例 |
| 3 | `useServerPaginationFrom` 契约 | 补盲区 | 7 例 |

---

## 二、真实缺陷：页码存了两份 ref

### 现象

`AlertList.vue` 的页码被两个独立的 ref 各存了一份：

```
const pageRef = ref(1)                                   // 传给 Query，进 queryKey
useUrlFilters([... { ref: pageRef, key: 'page' } ...])   // URL 恢复写这个

const pagination = useServerPaginationFrom(...)          // 内部自己 ref(1)
const { currentPage } = pagination                       // 模板分页条读这个
watch(currentPage, (p) => { pageRef.value = p })         // 只有单向同步
```

原注释写的是「让 composable 的 currentPage 与 pageRef 是同一个 ref」，
但 `useServerPaginationFrom` 内部 `const currentPage = ref(1)`——
**注释描述的意图，代码并没有实现。**

### 两个方向都是断的

**方向一：URL 恢复失效。**
`useUrlFilters` 在 setup 阶段把 `?page=3` 写进 `pageRef`，
分页条读的 `currentPage` 仍是 1：
列表显示第 3 页的告警、底部却高亮「1」。
此时点「1」毫无反应——`goToPage(1)` 时 `currentPage` 本来就是 1，
watch 不触发、Query 不重拉。

值班同事把「?status=FIRING&page=3」甩过来是本页 URL 状态存在的全部理由。

**方向二：改筛选不回第 1 页。**
`resetPage()` 把 `currentPage` 设为 1，但它本来就是 1，
watch 同样不触发，`pageRef` 仍停在 3——
换了筛选条件，请求却还在拉第 3 页。

### 为什么 TicketList 没这个问题

`TicketList` 把 `useUrlFilters` 直接绑在 `pagination.currentPage` 上，
全程只有一份 ref。`AlertList` 改用 TanStack Query 时新引入了 `pageRef`，
两份 ref 的结构是那次改造的副产物。

**逐一核对了其余三个分页页面**（`ActionAllowlist` / `AuditLogs` /
`AutomationPolicies`），都是单 ref 写法，**AlertList 是唯一例外**。

### 修法：让「只有一份」成立，而不是再加一条反向 watch

给 `useServerPaginationFrom` 增加可选的 `page` / `size` 入参：
传了就用调用方的，没传才自建（TicketList 行为完全不变）。
共用之后 `useUrlFilters` 写它、`goToPage`/`resetPage` 写它、
queryKey 读它，三方天然一致，**两条同步 watch 都删掉了**。

补一条反向 watch 也能让测试变绿，但那是在两份状态之上再加一层同步——
下一个方向（比如 size）还会再断一次。

---

## 三、AlertDetail：数据层干净，风险在派生展示

同构对比的结论是**数据层没有缺陷**：它用 TanStack Query，
切换 id 自带竞态防护，`TicketDetail` 的「切换详情不重置」在结构上不存在。

风险集中在算错了**不抛异常、只安静显示错误值**的地方：

| 派生项 | 算错的后果 |
|--------|-----------|
| 时间线给未发生节点编造时间 | 用户以为已有人确认，没人跟进；MTTA 被算成 0 |
| 持续时长 | 这是判断「要不要升级」的主要依据 |
| `canAcknowledge` / `canResolve` | 按钮该亮不亮，或对已恢复告警重复操作 |

### 时区那条是重点

`durationText` 必须走 `parseDate` 而非 `new Date`：
后端 `LocalDateTime` 无时区后缀，`new Date('2026-08-26 09:00:00')`
按**浏览器本地时区**解析，而 `Date.now()` 是绝对时刻，
两者混算在非服务器时区下差十几小时——
刚触发 5 分钟的告警可能显示成「8 小时」。

用例把「现在」固定为带时区的绝对时刻（`2026-08-26T09:45:00+08:00`）。
注入验证把 `parseDate` 换成 `new Date`，**4 例同时失败**。

---

## 四、盲区：变体函数在 46 例里一例没有

`useServerPagination.test.ts` 有 46 例，
但全部打在 `useServerPagination` 上，
外部数据源变体 `useServerPaginationFrom` **一例都没有**。

它只在两个页面被用到，而那两个页面当时都没测试——
本轮的分页缺陷正好落在这个交叉盲区里。

**教训**：「这个 composable 有 46 例测试」不等于「它的每个导出都被测了」。
统计口径应落到导出函数级别，而不是文件级别。

---

## 五、注入-还原验证汇总

| 批次 | 注入 | 抓到 | 备注 |
|------|------|------|------|
| AlertList 分页 | 4 | 4 | 第 2 项首版漏网，补 `?size=` 用例后抓到 |
| AlertDetail 派生 | 6 | 6 | 时区那项触发 4 例失败 |
| 分页 composable | 2 | 2 | 注入用「复制值」而非「直接忽略」的隐蔽形态 |

### 一处值得记录的注入设计

分页 composable 的注入没有写成「直接忽略传入的 page」，
而是 `ref(options.page?.value ?? 1)`——**它读了传入值，
看起来像是支持了，实际仍是两份 ref**。
这比「完全忽略」更接近真实会犯的错误，也更能检验断言是否到位
（值相等但对象不同，只比较值的断言会放过它）。

### 两处测试自身的错（未误改产品代码）

- AlertList 首轮 9 例全挂在 `Cannot destructure property 'row'`——
  `el-table-column` 作用域插槽拿不到 row，AGENTS.md 3.8 已记录的坑
- 「total 跟随数据源」用 `let total = 35` 写，computed 不会重新求值，
  改用 `ref` 后通过

---

## 六、当前进度

| | 文件 | 行数 | 测试文件 | 用例 |
|---|---|---|---|---|
| 后端 | 180 | 34,823 | 66 | 878 |
| 前端 | 148 | 47,7xx | 79 | **1,511** |

### 零测试页面（已按行数排序）

| 页面 | 行数 | 判断 |
|------|------|------|
| `Dashboard.vue` | 660 | 🟡 首屏，本轮改过 5 处主题令牌 |
| `HelpCenter.vue` | 645 | 🟢 静态内容为主，优先级低 |
| `Home.vue` | 597 | 🟡 改过 4 处主题令牌 |
| `ActionItemBoard.vue` | 457 | 🟡 |
| `ApprovalCenter.vue` | 312 | 🟡 审批入口，权限敏感 |
| `Login.vue` | 232 | 🟡 |

**1000 行以上的零测试页面已全部清零**
（`KnowledgeBase` / `AlertList` / `AlertDetail` 本轮及上轮补齐）。

---

## 七、下一步建议

1. **`Dashboard.vue` + `Home.vue` 渲染冒烟** —— 两者是首屏，
   且各有 5 处、4 处刚改过的主题令牌，目前无任何断言兜底。

2. **`ApprovalCenter.vue`** —— 312 行不大，但它是高危动作放行的入口，
   权限与状态判定错了后果重。已有后端角色边界测试，
   前端侧「按钮该不该亮」尚无覆盖。

3. **统计口径改进** —— 本轮暴露的盲区说明「文件有测试」不等于
   「导出都被测」。建议加一个脚本，列出**有导出但从未在测试里被 import**
   的函数，接进 `tools/audit/run_audit.py`。这比继续按文件补测试更能找到缺口。

4. **仍需你操作**：`run_audit.py` 接入 CI（我无 workflows 权限）：

   ```yaml
         - name: 代码模式扫描
           run: python3 tools/audit/run_audit.py
   ```
