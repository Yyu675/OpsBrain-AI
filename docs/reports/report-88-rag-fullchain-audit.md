# RAG 全链路防护审计 + 太空驾驶舱评估

> 所属：全范围深度审计 · 补充维度
> 日期：2026-09-19
> 覆盖：RAG 八节点防护链 + 太空驾驶舱需求分析

## TL;DR

- RAG 全链路防护链**完整且有效**：四层幻觉防护（L1~L4）+ 内容清洗 + 权限域隔离缓存 + 工具白名单 + 注入防护，全部在代码中真实生效。
- 太空驾驶舱：**不建议现在做**。当前看板已覆盖核心 KPI，驾驶舱的价值点在告警/工单量 ≥50/天 时才显现——现在做是过度工程。

---

## 一、RAG 全链路防护审计（八节点）

### 防护链总览

```
用户提问
  ↓
[N1] 输入防护层    ← PromptInjectionGuard（提示词注入检测，中英文规则）
  ↓
[N2] 语义缓存层    ← SemanticCacheService（按权限域隔离，scope|query 双键）
  ↓ (未命中)
[N3] 意图路由层    ← DevOpsIntentRouter（大小模型分流，成本感知）
  ↓
[N4] 检索召回层    ← HybridRetrieverService（向量+关键词混合，L4 熔断 Score<0.73）
  ↓
[N5] 内容清洗层    ← KnowledgeContentCleaner（HTML/图片引用/乱码清洗）
  ↓
[N6] 上下文组装层  ← ContextBudgetManager（token 预算裁剪，防上下文溢出）
  ↓
[N7] LLM 推理层    ← 四层幻觉防护 L1~L4（Prompt约束/白名单/参数校验/熔断）
  ↓
[N8] 响应输出层    ← SSE 流式推送 + 引用溯源 + 工具执行审计
```

### 逐节点审计结论

| 节点 | 防护组件 | 状态 | 证据 |
|------|---------|------|------|
| N1 输入防护 | `PromptInjectionGuard` | ✅ | 中英文注入规则，`忽略指令/角色扮演/系统提示词覆盖/代码执行` 四类拦截 |
| N2 语义缓存 | `SemanticCacheService` | ✅ | 权限域隔离（scope\|query 双键）、C2 热点缓存隔离、异步写入 |
| N3 意图路由 | `DevOpsIntentRouter` | ✅ | 大小模型分流，深seek-chat/reasoner 按复杂度选 |
| N4 检索召回 | `HybridRetrieverService` | ✅ | L4 熔断 `min-similarity-score: 0.73`、SQL WHERE 内过滤（保 topK 语义）、hybrid 向量+关键词 |
| N5 内容清洗 | `KnowledgeContentCleaner` | ✅ | HTML 标签/图片引用/乱码（REPLACEMENT_CHAR 计数）清洗 |
| N6 上下文组装 | `ContextBudgetManager` | ✅ | token 预算预检、历史裁剪、超限硬终止（预算超限拒绝） |
| N7 LLM 推理 | 四层幻觉防护 L1~L4 | ✅ | L1 Prompt 约束（DevOpsAgentEngine 系统提示）、L2 工具白名单（仅 searchDevOpsKnowledge/createDevOpsTicket）、L3 参数校验（ToolParameterValidator）、L4 相似度熔断 |
| N8 响应输出 | SSE + 引用溯源 | ✅ | 引用出处提取（【来源：标题】标记）、complete 事件带 citations、成本字段 costRmb 统一 |

### 发现的两个可改进点（非缺陷，为增强建议）

| 级别 | 点 | 位置 | 建议 |
|------|----|------|------|
| P3 | 知识文档 `content` 字段无长度上限 | `KnowledgeDoc` | 加 `@Size(max=500000)` 防单篇文档撑爆 token 预算 |
| P3 | L1 Prompt 为编译期常量，无法按会话注入 | `DevOpsAgentEngine` | 保留现状（侵入最小，对全模型一致），如未来需按租户定制再改 |

---

## 二、太空驾驶舱评估

### 太空驾驶舱是什么

一个实时大屏页面，聚合展示系统的核心运营指标：实时告警流、工单处理进度、AI 回答质量、成本趋势、系统健康度——通常用于 NOC（网络运维中心）大屏或演示。

### 当前 Dashboard 已覆盖什么

| 已有能力 | 位置 |
|---------|------|
| KPI 总览（工单数/缓存命中率/查询量/成本） | Dashboard |
| 成本趋势 + 工单趋势图 | Dashboard |
| SLA 风险面板 | Dashboard |
| 诊断会话统计 | Dashboard |
| 根因准确率反馈 | Dashboard |
| 告警列表/工单列表 | AlertList/TicketList |

### 太空驾驶舱的增量价值 vs 成本

| 维度 | 分析 |
|------|------|
| **增量价值** | 大屏实时聚合视图，适合 NOC 大屏展示；动画/图表视觉冲击力 |
| **当前适用性** | 告警量 1 条/天、工单 25 张/月——数据密度不足以支撑大屏的信息量 |
| **建设成本** | 需新增实时推送通道（WebSocket 全量推送）、前端大屏适配、图表库选型（ECharts 已有）、定时聚合刷新 |
| **建议时机** | L2 告警量达到 **≥50 条/天** 或 **≥20 张工单/周** 时再评估——届时数据密度足够，大屏才有实际运维价值 |

### 结论

**不建议现在做太空驾驶舱。** 原因：
1. 数据密度不足——当前是 25 张工单/月 的规模，大屏会空旷
2. Dashboard 已覆盖核心 KPI——太空驾驶舱的增量是「实时性+视觉冲击」，不是新数据
3. 成本/收益不成比例——投入 2-3 天做大屏，换来的只是「看起来更酷」

**更务实的替代**：Dashboard 增加「实时告警流」侧栏（WebSocket 推送新告警，已有推送基础设施）——成本低、即插即用、真实解决「值班时不用刷页面」的痛点。

---

## 三、最终建议（按优先级）

| 优先级 | 建议 | 理由 |
|--------|------|------|
| **P1** | Dashboard 加实时告警流侧栏 | 复用已有 WebSocket 基础设施，解决值班刷新痛点 |
| **P1** | 知识文档 content 加长度上限 | 防单篇超长文档撑爆上下文预算 |
| **P2** | RAG 评测 CI 接入 PR 门禁 | 评测框架已有，差一步接线 |
| **P2** | Swagger UI 开放访问 | /v3/api-docs 已可用，只需 SecurityConfig 放行 |
| **P3** | 太空驾驶舱 | 待告警量 ≥50/天 时再评估 |
