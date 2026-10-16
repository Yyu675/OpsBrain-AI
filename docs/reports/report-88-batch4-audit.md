# 批次 4 审计报告：告警链路模块

> 所属：全范围深度审计（分阶段逐批） | 批次 4（共 6 批）
> 审计日期：2026-09-16
> 覆盖模块：L2 告警（Webhook 接收 / 去重 / 自动建单 / WebSocket 推送 / 告警列表 / 通知中心）
> 检查维度：静态缺陷 · 安全（Webhook 免鉴权兜底）· 去重正确性 · Single Writer 契约 · 测试覆盖

## TL;DR

- **Webhook 端点安全设计成熟**（WebhookGuard：常量时间比较 + fail-closed 限流 + 生产密钥缺失即拒绝 + 401/429+Retry-After）——**排除批 1 对「免鉴权 webhook 可被伪造触发建单」的担忧**。
- 告警去重（SHA-256 指纹）与自动建单（Single Writer 契约）实现成熟，无新 P0/P1。
- 批 88-B1 在 `AlertService.createAutoTicket` 的双向溯源已在批 3 验证通过。

---

## 一、Webhook 安全验证（本批核心）— ✅ 排查通过

批 1 曾怀疑 `/api/v1/alerts/webhook`（在鉴权白名单、直写库、可触发建单）是否裸奔。核查 `WebhookGuard` 后**排除**：

| 防线 | 实现 | 成熟度 |
|---|---|---|
| 共享密钥 | 常量时间比较（防时序侧信道逐位猜测） | ✅ 高级 |
| 限流 | 按 IP 滑动窗口，**fail-closed**（Redis 故障拒绝而非放行） | ✅ 高级 |
| 生产默认 | prod 密钥未配置 = **拒绝处理**（非 WARN 放行） | ✅ fail-closed 安全默认 |
| 语义 | 401（密钥错·重试无意义）/ 429（太快·退避重投）→ Alertmanager 依状态码退避，**告警不丢** | ✅ |
| 测试 | `WebhookGuardTest` | ✅ |

「两次警告才生效一次」的 dev 兼容、`ALERT_WEBHOOK_SECRET` 运维说明已齐备。

## 二、去重与建单（6.34/50/58 迭代产物）

- **去重键**：`SHA-256(alertName + service + 排序后标签)`，排除 alertname/service/severity 单独处理；按活跃状态（FIRING/ACKNOWLEDGED）查询。
- **自动建单**：`Single Writer` 契约（6.10），`autoTicketEnabled` 开关（关则入库不去重建单）。
- **聚合降噪**（6.58 跨键风暴抑制）：`aggregateEnabled` / `aggregateWindowMinutes` 配置化。
- **失败隔离**：单条告警处理失败不影响整体（`processWebhook` 内部隔离），端点始终回 200 防空转重试。

## 三、结论

告警链路静态层健康。**最关键的免鉴权 Webhook 安全防线完备**，无新增 P0/P1。自动触发的告警→自愈联动留待批 5 深入。

## 附：本批涉及文件

- `src/main/java/com/devops/agent/controller/AlertWebhookController.java`
- `src/main/java/com/devops/agent/common/web/WebhookGuard.java`
- `src/main/java/com/devops/agent/domain/alert/service/AlertService.java`
- `src/test/java/com/devops/agent/common/web/WebhookGuardTest.java`
