# 111 S3-2 首批 fabric8 执行器：重启与扩缩容

> 报告性质：路线图 §7.2「首批执行器（重启 Pod / 扩缩容）」的落地记录。
> 执行器从「抽象 + Mock」进入「真实目标系统」阶段；全程复用既有 K8s
> 基建（fabric8 6.13.4 早就位、K8sOpsExecutor 的懒加载/测试接缝模式）。

---

## 一、交付清单对照（§7.2 任务表）

| # | 要求 | 落法 |
|---|---|---|
| 3-2.1 | RestartPodExecutor：删除 Pod 由 Deployment 重建 | ✅ `K8sRestartPodExecutor`（K8s 原语层面唯一诚实的重启方式） |
| 3-2.2 | ScaleReplicasExecutor：调整 Deployment replicas | ✅ `K8sScaleReplicasExecutor` |
| 3-2.3 | dryRun 输出目标/当前副本数/预期影响 | ✅ 两个执行器的 dryRun 都含目标、现状、影响数与回滚方案字样 |
| 3-2.4 | 回滚：重启诚实标注不可回滚；扩缩容恢复原副本数 | ✅ 重启 undoToken 恒 null（快照留全要素供审计）；扩缩容 preSnapshot.previousReplicas → undo 按快照恢复 |
| 3-2.5 | 爆炸半径 ≤20%（可配置） | ✅ `devops.healing.k8s.max-blast-ratio`（默认 0.2），dryRun 与 execute 双重校验，公式容许下限 1 |
| 3-2.6 | 注册为 Agent 工具 | ⏳ 刻意延后——Agent 工具面是 chat 驱动的另一条暴露面，待受控自愈被验证后再开（见第四节） |
| 3-2.7 | 白名单 + 风险策略初始数据 | ✅ V1 基线早已播种 `k8s.pod.restart` / `k8s.deploy.scale`（enabled=FALSE）；本批 V8 补 Mock 轨种子 |

## 二、三道防线的纵深（为什么执行器侧还要再查一次）

```
配置层：sys_action_allowlist（未登记=拒绝）+ sys_risk_policy（环境/审批/爆炸半径）
   ↓ 通过
代码层：executor 硬护栏——
   ① 命名空间白名单（devops.healing.k8s.allowed-namespaces，默认 staging,dev）
      配置层配错也伤不到 prod：护栏在代码里，不在数据里
   ② replicas ⟂ [1, max-replicas]（默认 10，与白名单 param_schema 种子对齐）
   ③ 爆炸半径执行前二次校验（演算→执行之间副本数可能已被他人改动）
   ↓ 通过
原语层：fabric8 调用，异常只回摘要（类名），不外泄连接细节与凭证
```

## 三、两条回滚立场（3-2.4 的诚实差分）

| 执行器 | undoToken | undo 行为 | 理由 |
|---|---|---|---|
| 重启 | **恒 null** | 撤销入口前置拒绝 | 已删除的 Pod 变不回来；新 Pod 不健康是**新的故障**，应走新一轮诊断，不是假装可时光倒流 |
| 扩缩容 | `scale-back-*` | 按 preSnapshot 恢复原副本数 | 副本数是可逆状态量，快照恢复是这个动作真正的回滚 |

> 注册强契约（3-1.4 折中版，报告 110）在此经受住了第一次真实考验：
> 重启执行器选择的出路是「undoCapable=true（契约）+ undoSupported=false（运行期）+
> undoToken=null（台账）」，三层一致地表达「我不可撤销」，而不是实现一个撒谎的空方法。

## 四、3-2.6 为什么延后（刻意的）

把 restartPod/scaleReplicas 注册为 Agent 工具 = 让 LLM 能在对话里直接打出这两个动作。
当前受控自愈链路（治理门→演算→审批→执行→撤销）刚被验证到编排层，
Agent 工具面是**第二条暴露面**——按「AI 的自主权与它证明的可靠性严格挂钩」
的 README 原则，先让第一条面在审批与治理下跑稳，再开第二条。该Hook点已留好
（执行器本身无状态、幂等闸在编排层）。

## 五、测试矩阵（+8 例，S3 累计 24 例）

- 重启：dryRun 输出全要素（目标/phase/同应用总数）/ execute 删除且快照留 uid、undoToken 恒 null
- 扩缩容：爆炸半径 3→6 越限拒绝 / execute 记 previousReplicas=4 且 undo 调 scale(4) / replicas=50 越界拒绝
- 公共：prod 命名空间硬护栏 / 无集群双侧明确失败 / 注册表 K8s+Mock 共栖路由各归其位

## 六、阶段 3 余量

- §7.3-3-3.5 Saga 联动 + §7.4 ActionVerifier（执行后验证 → 失败自动 undo → 升级人工）→ 下一主批
- L4 占位路由 steps/verification 两条（依赖 3-4 的模型分出）
- 3-1.5 automation_policy → 自动构造 HealingAction（S3-2 告警驱动闭环）
