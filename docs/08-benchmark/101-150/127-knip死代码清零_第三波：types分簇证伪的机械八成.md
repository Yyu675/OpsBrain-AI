# 127 knip 死代码清零·第三波：types 分簇证伪的机械八成

> 报告性质：前端模块技术债（阶段 D），批次 24（T40），接续报告 122/125。
> 战果：未用类型 91 → **71**（-20）；波次结论：真账里约八成是
> 「同文件自引用类型误挂 export」的机械错挂——去 export 零风险、零语义改动。

---

## 一、两簇施工记录（20 条，矩阵驱动）

证伪矩阵（91 条全量 grep）先把账切成三类，动刀只碰前两类：

| 类 | 条数 | 处置 |
|---|---|---|
| 跨文件零引用 + 同文件自引用 | 本作 20 条全在此 | 去 `export`（类型在文件内继续服役） |
| 跨文件零引用 + 定义仅一处 | 本两簇未见 | （后续簇若遇：整删，git 史可复） |
| 跨文件有引用（假阳） | 6 条存证不碰 | 与 exports 假阳同走路径 B 校准批 |

**第一簇（8 条，剔 1 假阳后 7）**:agentTrace/alerts/auditLogs/auth/chat 域——
`AgentTraceStats` `AlertQuery` `AiCallLogPage` `OperationAuditQuery`
`AiCallLogQuery` `LoginResult` `ChatStreamCallbacks`。

**第二簇（17 条，剔 4 假阳后 13）**:diagnosis/governance/healing/metrics/
ticketAiAnalysis 域——`DiagnosisReplayView` `ActionQuery` `PolicyQuery`
`HealingOutcome` `HealingTrigger` `MetricCatalog` `InstantResult` `RangeResult`
`OverviewResult` `DatasourceResult` `TicketAiAnalysis` `SaveAnalysisPayload`
`AiAnalysisStats`。

## 二、假阳存证新增（types 域 6 条，转正前必清）

`PagedResult`（auditLogs/governance 双同名 interface，各 cross 4-5）、
`HealingExecutionDetail`（cross 4）、`FrontendTicket`（**cross 46**——knip
对 re-export/barrel 解析盲区的最硬证物）、`BackendTicket`（cross 13）。
连同 exports 域 7 条，假阳存证总共 **13 条**——路径 B 校准批的清单在此。

## 三、账面与验证

| 类 | 开始（报告 122） | 现状 | 备注 |
|---|---|---|---|
| 重复导出 | 1 | 0 | ✅ 批 19 毕业 |
| 未用导出 | 23 | 7 | ✅ 批 22 真账清零，余皆假阳 |
| 未用类型 | 53(92) | **71** | 本批 -20；真账余 ~57（77 矩阵真账 - 本批 20） |
| Unlisted deps | 17 | 17 | 另账（codemirror vendor） |

四闸：tsc 0 / lint 0 err（54 warnings 均前存）/ vitest 93 文件 1806 绿 / build ✓。

## 四、下一簇预告（T41 候选面）

剩余 types 按矩阵聚拢的低垂果：stores / composables / utils / views 内部类型
与 api 尾部域（slaRisk、sessionMemory、automation、changeEvents、approval…
的 Query/Result 族）。判例纪律不变：跨文件零引用自引用 → 去 export；
定义仅一处 → 整删；cross>0 → 假阳存证。真账见零后走 T42：
13 条假阳 config 校准（路径 B）+ 删 `continue-on-error`，knip 升级为门禁。
