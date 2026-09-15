# 批次 6 审计报告：治理 / 看板 / 鉴权横切模块

> 所属：全范围深度审计（分阶段逐批） | 批次 6（收官，共 6 批）
> 审计日期：2026-09-16
> 覆盖模块：M8 看板（Dashboard / Trends / Metrics）/ 审计（AuditLog）/ 治理（Governance）/ 负责人名录（TeamMember）/ 鉴权（Auth）/ **批 88-A 遗留清单复核**
> 检查维度：静态缺陷 · 批 88-A 遗留问题闭环 · 统计口径 · 横切安全

## TL;DR

- **批 88-A 遗留的 5 项问题，4 项已修复，仅 1 项 P3 死代码残留**。
- M8 看板统计口径（6.41 修复后）健康，无新 P0/P1。
- 鉴权横切（Sa-Token + 角色 + CORS + CSP）在批 1/4 已验证安全。

---

## 一、批 88-A 遗留清单复核（本批核心）

| # | 遗留项（批 88-A 决策记录） | 现况 | 结果 |
|---|---|---|---|
| P2-1 | chat 渠道占位 key 无启动拦截 | `RealModeStartupGuard` 现拒空/占位 key 启动 | ✅ 已修复（批 88 遗留修复 commit） |
| P2-2 | CSP connect-src 分域会拦 API | `SecurityHeadersFilter:89` 从 CORS origins 派生 `connect-src` | ✅ 已修复 |
| P2-3 | CacheConfig knowledge-meta 注释失实 | 已移除失实段，改"统一 1000 条/5min" | ✅ 已修复 |
| P3 | 登录全量清缓存（allEntries） | 已改**双键精确清理**（`#id` + `#username`） | ✅ 已修复 |
| P3 | `tickets.query.ts` invalidateList 死代码 | 复核：`useTicketInvalidate` 有专门测试契约（`tickets.query.test.ts:130`），是**有意保留的公共 API**（工单页迁 TanStack 时启用），**非死代码** | ✅ 误判更正（批 88-A 原结论正确） |

**结论**：批 88-A 遗留 5 项**全部闭环**（4 项修复 + 1 项经复核为有意保留而非死代码）。

> ⚠️ 本报告初版曾将 `invalidateList` 判为「死代码残留」，复核测试引用后更正——
> 判断死代码必须先查测试契约，测试引用同样是有意的公共 API 消费方。

## 二、M8 看板统计口径 — ✅

- 有效查询口径：仅 `CHAT` + `CACHE_HIT`（剔除拒绝/失败审计行）——不虚报 query 数。
- 缓存命中率分母与概览 KPI **同口径**（`SERVED_QUERY_FILTER`），有意义。
- 平均成本只对**付费调用**（`cost_rmb>0`）求均值。
- 7 日成本趋势、模型分布、诊断面板（6.41 修复紧急单虚报后）健康。

## 三、横切安全核查集成

- 鉴权：`WebConfig` 全 `/api/**` 拦截 + 白名单（auth/health/webhook）+ OPTIONS 放过 CORRECT。
- 未鉴权 Webhook：`WebhookGuard` 共享密钥 + fail-closed 限流（批 4 验证）。
- 补偿/Saga 端点：类级 `@SaCheckRole("ADMIN")`（批 5 验证）。
- 知识库写操作：分级守卫 `requireEdit`/`requireDestructive`（批 2 验证）。

## 四、结论

批次 6（收官）静态层健康。唯一实质发现为批 88-A 遗留的死代码残留（P3）。**本批审计全部完成。**

## 附：本批涉及文件

- `src/main/java/com/devops/agent/infrastructure/config/CacheConfig.java`
- `src/main/java/com/devops/agent/domain/auth/UserRepository.java`
- `src/main/java/com/devops/agent/infrastructure/guard/RealModeStartupGuard.java`
- `src/main/java/com/devops/agent/**/SecurityHeadersFilter.java`
- `src/main/java/com/devops/agent/application/impl/DashboardServiceImpl.java`
- `devops-platform-frontend/src/api/queries/tickets.query.ts`
