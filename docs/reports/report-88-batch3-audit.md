# 批次 3 审计报告：工单业务闭环

> 所属：全范围深度审计（分阶段逐批） | 批次 3（共 6 批）
> 审计日期：2026-09-16
> 覆盖模块：M7 工单（创建 / 生命周期状态机 B0-B5 / 首响 / SLA / 活动流 / 回复 / AI 分析 / 复盘）/ 审批 / 批 88-B1 反向溯源
> 检查维度：静态缺陷 · 契约一致性 · 溯源链完整性 · 测试覆盖

## TL;DR

- 批 88-B1 的**双向溯源链验证通过**：告警→工单（`source_trace_id=dedup_key`）与工单→告警（`ticket_id` 回填）链条完整。
- 工单业务闭环（B0-B5 七阶段）主体在 6.46/6.47 已验收，静态核查无新 P0/P1。
- 发现 **1 个 P3 契约问题**：`AlertService.createAutoTicket` 注释声称「使用 8 参」实现已改为 9 参（批 88-B1），**注释未同步 → 失实**。

---

## 一、批 88-B1 反向溯源链验证（本批核心）

| 方向 | 实现 | 位置 | 结果 |
|---|---|---|---|
| 告警 → 工单（正向） | `alert.ticket_id = ticket.getId()` 回填 | `AlertService.createAutoTicket` | ✅ |
| 工单 → 告警（反向） | `source_trace_id = alert.getDedupKey()` | `TicketService.createTicket` 9 参重载 | ✅ 完整透传至 `ticket.setSourceTraceId` |

4 个建单入口分流核查：
- ✅ `AlertService`（告警）→ 9 参传 dedup_key
- ✅ `TicketController`（手动）→ 无溯源（8 参，正确）
- `ApprovalOrchestrator` / `HealingOrchestrator` → 建单入口（正向业务场景，无反向溯源诉求，符合设计）

## 二、静态审计结论——无新 P0/P1

- `TicketService`（1728 行）：createTicket 校验 / 优先级归一 / SLA deadline 派生 / 标签乐观写入 / 活动流起点 — 均成熟。
- 生命周期状态机（B0-B5：建单/首响/派单/处置/根因确认/验证/复盘）在 6.46 已过验收。
- 乐观锁更新（P1-4：读取-合并-写回 + CAS 版本校验）正确。

## 三、P3 —— 注释失实（契约一致性发现）

`AlertService.createAutoTicket` 方法注释：
> 「使用 8 参 createTicket(...)」

但批 88-B1 已将实现改为 9 参重载（末参传 dedup_key）。**注释与实现脱节**，误导后续维护者以为告警建单未带溯源。建议：注释同步为「9 参重载：末参传 dedup_key 建立反向溯源链」。

## 四、结论

工单闭环静态层健康，**批 88-B1 双向溯源链正确**。唯一实质发现为 P3 注释失实（低风险，建议顺手修正）。联调验证（建单→流转→复盘全链）留待环境修复后执行。

## 附：本批涉及文件

- `src/main/java/com/devops/agent/domain/biz/service/TicketService.java`
- `src/main/java/com/devops/agent/domain/alert/service/AlertService.java`
- `src/main/java/com/devops/agent/controller/TicketController.java`
- `src/main/java/com/devops/agent/application/runtime/ApprovalOrchestrator.java`
- `src/main/java/com/devops/agent/domain/healing/HealingOrchestrator.java`
