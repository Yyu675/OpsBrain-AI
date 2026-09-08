# 56 · PRD v2.0（prd01.md）与代码实现对齐核查报告

> **背景**：用户上传的 `prd01.md`（PRD v2.0 完整合并版，L1-L5 自治分级蓝图）此前两次未送达沙箱；
> 本轮用户在对话中粘贴全文，本文据此与代码逐条实测对齐。
> **方法**：对 v2.0 中每一项「能力/契约/验收/未决问题」，在代码库中 grep/find 取证，
> 结论分四档：✅ 已实现（附代码位置）/ ⚠️ 部分实现（注明缺口）/ ❌ 未实现 / 📄 仅文档。
> **结果**：v2.0 已合并进 `docs/PRD.md`（v2.0 对齐版），本报告为取证底稿。

---

## 一、总体结论

| 自治级别 | v2.0 声称 | 实测 | 一句话 |
|:---:|---------|:---:|--------|
| L1 辅助问答 | 蓝图 | ✅ 完成 | 四层幻觉防护全部落地 |
| L2 主动感知 | 蓝图 | ⚠️ 80% | 主链路完成，信号源仅 Prometheus 一种 |
| L3 人机协同 | 蓝图 | ✅ 完成 | 审批闭环 + 重放执行 + 状态机齐全 |
| L4 受控自愈 | 蓝图 | ❌ 未开始 | **治理骨架全齐、执行器为零** |
| L5 全自动 | 远景 | ⏳ | 未启动 |
| 安全边界 | 五机制 | ✅ 全部实现 | 白名单/分级授权/RBAC/审计/凭据隔离 |
| 看板 | 蓝图 | ⚠️ 部分 | 健康度/趋势✅；成本/盲区部分 |
| 商业化 | P2+ | 📄 | 蓝图文档已出 |

---

## 二、逐条核查取证表

### 2.1 L1 智能问答

| v2.0 条目 | 实测 | 证据 |
|-----------|:---:|------|
| 自然语言问答 | ✅ | `DevOpsChatController`（SSE stream） |
| 自动开工单 | ✅ | `DevOpsTools.createDevOpsTicket`（2 个 @Tool 之一） |
| 答案带来源回链 | ✅ | `complete` 事件 `citations[]` 字段（`DevOpsAgentServiceImpl` 从检索片段提取去重） |
| 四层幻觉防护 L1 检索门槛 | ✅ | `HybridRetrieverService`：`min-similarity-score:0.73`，低于阈值熔断返回「无相关文档」 |
| 四层幻觉防护 L2 Prompt 约束 | ✅ | `AgentEngineConfig` L1 防护层：`@SystemMessage` 强约束 |
| 四层幻觉防护 L3 来源校验 | ✅ | citations 仅来自实际检索片段（toolResults 提取，去重累积） |
| 四层幻觉防护 L4 UI 呈现 | ✅ | `AIContextPanel.vue`：置信度三档配色（≥80 绿/50-79 橙/<50 红）+ 引用文档链接 |
| 工具白名单严格受限 | ✅ | `DevOpsTools` 仅 2 个 `@Tool` + `@ToolMeta` + `ToolRuntimeManager` 校验 |
| 开单真正落库不谎称 | ✅ | 建单走 `TicketService.save` 落库；审批场景返回「待审批」文案 |
| 库外问题诚实拒答 | ✅ | 0.73 门槛 + 输入门卫（Prompt 注入/敏感词拦截） |

### 2.2 L2 实时监测

| v2.0 条目 | 实测 | 证据 |
|-----------|:---:|------|
| 事件驱动（非人触发） | ✅ | `AlertWebhookController` + `AlertService.processWebhook` |
| 自动建单 | ✅ | 告警→工单（开关可关，Single Writer 契约） |
| 指标监控接入 | ✅ | Prometheus Alertmanager Webhook（`application.yml` 配置 + 端点） |
| 日志平台接入 | ❌ | 全仓无 ELK/Loki 客户端 |
| 云平台事件接入 | ❌ | 无云 API 事件流 |
| APM 链路接入 | ❌ | 无 |
| 自定义 Webhook | ✅ | `AlertWebhookController` 即通用开放端点（来源注册/鉴权待完善） |
| L1 同键去重 | ✅ | `computeDedupKey`（alertName\|service\|labels → SHA-256）+ `findActiveByDedupKey` + `incrementOccurrence` |
| 去重窗口 | ✅ | `devops.alert.aggregate-window-minutes:5`（`AlertService` @Value，可配置） |
| L2 跨键同源聚合 | ✅ | 跨键风暴抑制（CLAUDE.md §6.58） |
| L3 跨源独立 | ✅ | dedupKey 不同 → 各自建单（`findActiveByDedupKey` 未命中即新建） |
| 被抑制告警仍入库可查 | ✅ | 全量落库 `sys_alert`，抑制只作用于建单与推送 |
| 钉钉强提醒 | ✅ | `DingTalkNotifier`：markdown 卡片 + 加签（HmacSHA256）；开关默认关；异常只 WARN 不外抛（不阻塞主流程） |
| WebSocket 实时流 | ✅ | `AlertWebSocketHandler` + `WebSocketConfig` + `AlertWebSocketNotifier` |
| 通知中心持久化 | ✅ | 前端 `notifications` store + 后端拉取（§6.48） |
| P0/P1 触达 ≤30s | ⚠️ | 通道已具备；端到端 SLA 未断言（NFR 目标） |

### 2.3 L3 智能诊断与人机协同

| v2.0 条目 | 实测 | 证据 |
|-----------|:---:|------|
| 根因定位 | ⚠️ 半个 | AI 结构化分析 + `confirmRootCause` 记录闭环；无因果推断/传播路径 |
| 处置方案生成 | ✅ | AI 分析卡片（疑似根因/排查命令/置信度）+ 处置阶段动作列表 |
| 方案含风险等级 | ✅ | `@ToolMeta(riskLevel)` + `RiskPolicy` + 风险分级页 |
| 方案含回滚预案 | ✅ | `@ToolMeta(compensationAction)` + `SagaCompensationManager`（前端占位） |
| 落审批单含完整动作上下文 | ✅ | `sys_approval_request.payload`（可重放 JSON 原文，`ApprovalService` javadoc 明示） |
| 批准后重放执行 | ✅ | `ApprovalOrchestrator`（APPROVED 与 EXECUTED 分离） |
| 驳回记录理由 | ✅ | `ApprovalController.reject`（必填理由）+ `REJECTED` 终态 |
| 执行失败 EXECUTE_FAILED | ✅ | `ApprovalOrchestrator` 回写 status + executeResult |
| 超时未审批 EXPIRED | ✅ | `ApprovalStatus.EXPIRED`（定时任务标记） |
| 待人工介入 | ✅ 后端 / ❌ 前端 | `/saga/attention`（MANUAL_INTERVENTION_REQUIRED）；前端 `FutureCapability` 占位 |
| 高危绝不自动执行 | ✅ | `requiresApproval=true` 运行时校验（`ToolRuntimeManager`） |
| 审批限授权角色 | ✅ | `@SaCheckRole("ADMIN")` + 角色边界测试 |
| 全程留痕 | ✅ | `sys_operation_audit` + `sys_agent_tool_execution` + `AgentTraceController` |

### 2.4 L4 受控自愈

| v2.0 条目 | 实测 | 证据 |
|-----------|:---:|------|
| 低危自动执行 | ❌ | 全仓 `find -iname "*healing*"` 为空；`DevOpsTools` 仅 2 个 @Tool |
| 真实执行器 | ❌ | 全仓无 kubectl / K8s client / ssh 调用 |
| 自愈动作白名单数据表 | ✅（骨架） | `sys_action_allowlist`（migration_v26）+ `ActionAllowlist.vue` |
| 自动化策略 | ✅（骨架） | `sys_automation_policy` + `AutomationPolicies.vue` |
| 执行记录表 | ✅（骨架） | `sys_agent_tool_execution` + `ToolExecutionRecord` |
| 失败转人工 | ✅（骨架） | `SagaCompensationManager`：补偿失败 → `MANUAL_INTERVENTION_REQUIRED` |
| 自愈任务 5 个页面 | ❌ | 前端 5 条路由指向 `FutureCapability` 占位，后端零实现 |

### 2.5 安全边界

| v2.0 机制 | 实测 | 证据 |
|-----------|:---:|------|
| 工具白名单 | ✅ | 仅 2 个 @Tool 注册进 `AgentEngineConfig` L2 白名单层 |
| 分级授权 | ✅ | `@ToolMeta(requiresApproval)` + `ToolRuntimeManager` 校验 + 审批链 |
| RBAC | ✅ | Sa-Token 拦截 + `@SaCheckRole` + 前端按角色导航（§6.59） |
| 全程审计 | ✅ | `sys_operation_audit` + 审计查询页 `AuditLogs.vue` |
| 凭据隔离 | ✅ | `.env.example` 模板 + gitignore；API Key 不落库（P0-4 修复） |

### 2.6 看板与数据分析

| v2.0 条目 | 实测 | 证据 |
|-----------|:---:|------|
| 健康驾驶舱 | ✅ | `DashboardController.overview` + `Dashboard.vue` |
| 多维趋势 | ✅ | `DashboardController.trends` + `TrendChart.vue`（ECharts 三处接入，按天/服务/优先级下钻） |
| 成本视图（云资源） | ❌ | 仅 LLM 成本（`CostQuotaManager` + costRmb）；云资源成本依赖云平台事件源（未接） |
| 知识盲区 | ⚠️ | `KnowledgeStatsService` 已埋 `hotQueryCount`；前端展示待做 |

### 2.7 非功能需求

| v2.0 条目 | 实测 | 证据 |
|-----------|:---:|------|
| 全链路 traceId | ✅ | `TraceContext`（唯一来源）+ MDC `%X{traceId}`（§6.27） |
| 通知失败旁路 | ✅ | `DingTalkNotifier` 异常只 WARN 不外抛 |
| 核心链路 SLA ≥99.5% | ⚠️ | 无 actuator/健康探针（P2 待办），无法度量 |
| 百万片段/500 并发 | ⚠️ | pgvector 支撑；未压测 |

---

## 三、v2.0 未决问题 → 代码实测答复

| 未决问题 | 实测答复 | 状态 |
|---------|---------|:---:|
| 第三方接入确切清单 | 仅 Prometheus Alertmanager（P0 ✅）；自定义 Webhook 端点开放；日志/云/APM 未接 | 已答复 |
| 聚合时间窗口 | `devops.alert.aggregate-window-minutes:5`，配置化 | 已答复 |
| 聚合维度 | dedupKey = alertName + service + labels（排除 severity），SHA-256 | 已答复 |
| 触达渠道矩阵 | 钉钉（markdown+加签，默认关）+ WebSocket + 通知中心；P0/P1 强提醒分级 | 已答复 |
| L4 白名单界定 | 骨架就绪（白名单表/策略表）；首批场景建议：磁盘清理、单实例重启 | **待拍板** |
| 自治切换机制 | 按场景配置（策略引擎）；是否叠加客户成熟度双轨 | **待拍板** |
| FinOps/SecOps/混沌排期 | 蓝图已出；建议 FinOps 先行（LLM 成本底座已具备） | 建议已给 |
| 事件网关双向 | 当前单向；回写需 P4 执行器 + 目标系统集成 | 依赖 P4 |

---

## 四、结论与建议

1. **v2.0 蓝本与代码的偏差不在「该有的没有」，而在「没写的已经有了」**：
   L1-L3 主体 + 安全边界五项已全部实现，v2.0 作为目标态文档无需改动设计，
   只需把「状态」列补上（已并入 `docs/PRD.md`）。
2. **L4 是唯一的设计-实现断层**：治理骨架齐备、执行器为零。
   建议按 53/54 号报告的 P1→P4 顺序推进，P4 前先拍板目标系统。
3. **v2.0 中的指标基线**（MTTR ↓50%、收敛比 10:1、采纳率 80%、拒答率 95%）
   目前无评测集支撑，建议补一个「指标评测集」（L1 问答评测集 + 告警降噪回放集），
   让每个指标都有可重复的量化验证——这同时是面试叙事中「数据驱动」的强证据。

---

## 五、口径说明

- 前端测试 1584 例、后端 882 例、25 张表、19 个手工 SQL 等基线读数见 54 号报告，本文不重复。
- `docs/PRD.md` 已升级为 v2.0 对齐版；本报告与 PRD 附录 A 速查表互为索引。
