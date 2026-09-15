# 批次 5 审计报告：自愈执行器链路

> 所属：全范围深度审计（分阶段逐批） | 批次 5（共 6 批）
> 审计日期：2026-09-16
> 覆盖模块：L3/L4 自愈（执行器注册 / 编排 / 审批门 / 验证 / 审批重放 / Saga 补偿）/ 批 88-B3+B4 并发修复
> 检查维度：静态缺陷 · 并发正确性 · 安全（反射白名单）· 权限 · Single Writer 契约

## TL;DR

- **批 88-B4 的两个并发修复验证通过**：`markCompensating` CAS 在人工重试路径真实生效；反射白名单正确拦截非补偿语义方法。
- 审批重放（人审→执行）闭环正确：先固化决策再执行、未知动作类型拒绝执行（防意外副作用）、Single Writer 桥接。
- **发现 1 个 P3 精确性问题**：批 88-B4 注释称「调度可能并发触发补偿」，但审计未发现任何定时补偿调度器——实际触发源仅「流式失败 + 人工重试」两个（见 §三）。

---

## 一、批 88-B3 标识去重验证

`K8sRestartPodExecutor` / `K8sScaleReplicasExecutor` 的 `EXECUTOR_KEY` 从共用 `"k8s-fabric8"` 改为实例唯一标识。执行路由走 `supports(actionKey)` 非 `executorKey`，**纯展示层修复，不影响执行语义**。Commit 已验证测试 18/18。

## 二、批 88-B4 并发修复验证

| 修复 | 验证结果 |
|---|---|
| `markCompensating` CAS（SUCCESS/PARTIAL_SUCCESS→COMPENSATING，带 `compensated_at IS NULL` 条件） | ✅ 人工重试路径（`SagaController /compensate` → `compensateSaga` → `compensateStep` → `markCompensating`）真实生效，后到者返回 0 行跳过 |
| 反射白名单（仅 `voidTicket` / `compensate*` / `rollback*` 前缀） | ✅ 正确拦截 DB 脏值驱动的任意方法反射调用 |
| `releaseIdempotencyLock`（失败释放锁 + Lua CAS） | ✅ 代码正确（批 1 F3 已记测试缺口） |
| 超时 `future.cancel(true)` | ✅ 代码正确（同上） |

## 三、P3 —— 注释与现状的精确性

批 88-B4 `SagaCompensationManager` 注释称「人工重试与调度可能并发触发补偿」。审计实际触发源：
- **流式执行失败**（`DevOpsAgentServiceImpl:746`）
- **人工重试**（`SagaController /compensate`，且 `@SaCheckRole("ADMIN")`）

**未发现定时补偿调度器**（`@Scheduled` 搜索无补偿任务）。故「并发抢占」的实际场景是上述两源重叠/多线程并发，而非定时调度。**CAS 修复本身正确且必要**（防任意多源并发重复补偿），仅注释用词与当前实现略有出入。建议注释微调，非缺陷。

## 四、审批重放闭环（人审→执行）— ✅

- 先固化决策（APPROVED）再执行；失败体现 EXECUTE_FAILED 可重试，不回退批准判定。
- 未知动作类型**明确失败不猜测执行**（防意外副作用）。
- createTicket 传 null 由 Service 单一来源推导；creator 记审批人（可追溯）。
- 自愈重放桥接 `HealingOrchestrator.executeApprovedByApprovalId`（Single Writer，台账/快照/撤销凭据归编排器）。
- 通知旁路（失败不影响审批主流程）。

## 五、权限核查

`SagaController` 类级 `@SaCheckRole("ADMIN")`（补偿可撤单，普通用户不能反复触发）。`/attention` `/steps` 故障面清单同样限 ADMIN。一致，无缺口。

## 六、结论

自愈执行器链路静态层健康，**批 88-B3/B4 并发与安全修复正确落地**。唯一实质发现为 P3 注释精确性问题。批 88-B4 新增逻辑的**测试缺口**（批 1 F3 已详述）建议在本批或后续补齐。

## 附：本批涉及文件

- `src/main/java/com/devops/agent/application/runtime/SagaCompensationManager.java`
- `src/main/java/com/devops/agent/application/runtime/ApprovalOrchestrator.java`
- `src/main/java/com/devops/agent/application/runtime/ToolRuntimeManager.java`
- `src/main/java/com/devops/agent/controller/SagaController.java`
- `src/main/java/com/devops/agent/domain/healing/HealingOrchestrator.java`
- `src/main/java/com/devops/agent/domain/healing/k8s/K8sRestartPodExecutor.java`（批 88-B3）
