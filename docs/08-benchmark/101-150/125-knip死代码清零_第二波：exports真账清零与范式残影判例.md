# 125 knip 死代码清零·第二波：exports 真账清零与范式残影判例

> 报告性质：前端模块技术债（阶段 D），批次 22（T38），接续报告 122（第一波）。
> 战果：未用导出 17 → **7（全是批 19 存证的 knip 假阳）**——该类「真账清零」。
> 未用类型 91 留待 T39 按域分簇；Unlisted deps 17（codemirror vendor）另账。

---

## 一、十条证伪记录（决策轴证据全留档）

| 条目 | 证伪过程 | 判定 | 处置 |
|---|---|---|---|
| `CODE_VERSION_CONFLICT` | 同文件 line 336 CAS 冲突重试链引用 | 同文件自引用 | 去 `export` |
| `useTicketDetailQuery` | `TicketDetail.vue` **直接 import `fetchTicketById`** 不经 hook | 同楼两式的范式残影 | **整删** |
| `useTicketStatsQuery` | 通话视图测试直接 mock api 层 | 范式残影 | **整删** |
| `useTeamMembersQuery` | 同上 | 范式残影 | **整删** |
| `ErrorCode` | 全仓零引用；错误显示链走 message 不走 code | 残页常量 | **整删** |
| `findDocsBySourceTicket` | 后端 `GET .../by-source-ticket/{id}` 在服务 | 成对半成面（工单徽标页待接） | **@public** |
| `retryIndexing` | 后端 `POST .../reindex/pending` 在服务 | 半成面（索引健康操作项） | **@public** |
| `fetchTicketByTraceId` | 后端 `GET /tickets/by-trace/{traceId}` 在服务（line 139） | 半成面（告警→工单溯源） | **@public** |
| `fetchTicketAiAnalysisVersions` | 与在用的 `submitAiAnalysisFeedback` 同域成对；版本路由在服务 | 半成面（历史对比面板） | **@public** |
| `fetchAiAnalysisStats` | 与反馈函数构成「反馈→统计」闭环对；统计路由在服务 | 半成面（概览页接入） | **@public** |

## 二、本批确立的第三条判例（补豁免标准件的决策轴）

报告 122 给了「@public 豁免 vs 整删」的前两条轴（HTML SSoT / 公共库待用）、
批 19 给了「后端路由在服务」。本批加第三条，且是**否决性的**：

> **同楼两式残影必删不豁免**：同一功能若已被另一范式承接
> （本批实证：视图直接 fetch 替代 Query hook），旧范式导出 `@public`
> 等于给后来者留选错路的路标。删它，是让架构只有一种正确写法。

ticketKeys（detail/stats/all）未随 hooks 删——key 定义独立存活被
`queryKeys.test.ts` 与失效链罩着，keys 与 hooks 是两层资产。

## 三、账本进度

| 类 | 起点（报告 122 开工） | 本批后 | 余 |
|---|---|---|---|
| 重复导出 | 1 | 0 | — ✅ 第一批毕业 |
| 未用导出 | 21 | **7（全为假阳存证）** | 假阳清账 = knip 版本收敛或 entry 配置复核 |
| 未用类型 | 92 | 91 | T39 按 api/ 域分簇（auditLogs/governance/healing/metrics…） |
| Unlisted deps | 17 | 17 | codemirror vendor 动态加载解析面（另批准） |

四闸：knip 对账三读、`vue-tsc` 0、lint 0 err、vitest 93 文件 1806 全绿、build ✓。

## 四、距门禁转正（权限已还，触发条件只剩它自己）

清零定义更新为：真账清零即可**视假阳处置结论而定**——
路径 A：knip 版本升级收敛这 7 条（最干净）；
路径 B：knip.config.ts 对这 7 条做 `ignore` 校准并留假阳证据注释。
**推荐 B**：升级追版本是无限尾追，config 校准把「我们知道的」固化进仓，
将来版本收敛后删校准行即可。转正删 `continue-on-error` 与 B 同批落地。
