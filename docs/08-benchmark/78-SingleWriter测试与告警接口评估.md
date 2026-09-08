# 78 · Single Writer 路径测试 + 告警来源接口可行性评估

> 承接 77 号报告的「下一步」清单，逐项推进。

---

## 一、Single Writer 写入路径测试（18 例）

### 为什么专测这一段

`writeTicketFromDraft` 是 **AI 建单的唯一写入点**（P1-3 Single Writer），
审批拦截 / Saga 登记 / 落库 / 结果回写四条分支交织。

此前只有集成测试间接覆盖——而集成测试跑的是「顺利建单」那一条，
**另外三条失败分支从未被验证过**。

### 这段代码的特殊之处：它的输出会被模型当成事实复述给用户

每个分支都返回一段给模型看的提示文本。这些文本不是日志，
而是**模型下一轮回答的依据**。写错的后果不是「日志难看」，
而是**系统会让 AI 对用户说谎**：

| 分支写错 | 用户看到什么 |
|---------|-------------|
| 审批单提交失败却回「已进入审批队列」 | 等一个永远不会来的审批 |
| 落库失败却回「工单创建成功」 | 拿着不存在的工单号去催进度 |

所以测试里大量断言「返回文本必须/不得出现某些字眼」。
这不是在测措辞，是在测**系统会不会让 AI 撒谎**。

### 锁住的关键行为

| # | 行为 | 写错的后果 |
|---|------|-----------|
| 1 | 先登记 Saga 步骤再落库（`InOrder`） | 反序会留下无补偿记录的孤儿工单 |
| 2 | `businessKey` 必须是工单号 | 它是补偿动作 `voidTicket` 的唯一入参；写错会去作废一个不存在的工单，真正该作废的那张永远留在库里 |
| 3 | 审批拦截发生在**写库之前** | 此前工具直接写库只能事后作废，审批变成「先斩后奏」 |
| 4 | 审批分支登记为 `SKIPPED` | 登记成 `SUCCESS` 会让回滚去补偿一个没写库的步骤，误报「需人工介入」 |
| 5 | 审批 payload 存草稿 JSON | 为空则批准后无从执行，出现「审批已通过但工单建不出来」这种最难解释的状态 |
| 6 | 落库失败不暴露内部异常（P2-38） | 断言 `duplicate key` / `unique constraint` 不出现在返回文本里 |
| 7 | Saga 登记失败仍继续写库 | 审计是旁路，反过来会让一次数据库抖动阻断所有 AI 建单 |
| 8 | `approvalRequired` 默认 `false` 这个事实本身 | 若默认值被改成 `true`，所有 P0 工单会突然转入审批队列 |

### 注入验证 3/3

| 注入项 | 检出信息 |
|--------|---------|
| I1 `businessKey` 写成 `traceId` | `Argument(s) are different! Wanted: updateResult(100L, SUCCESS, ..., "TKT-20260827-0001", ...)` |
| I2 审批分支登记为 `SUCCESS` | `expected: SKIPPED but was: SUCCESS` |
| I3 内部异常详情透给模型 | 实际文本含 `duplicate key value violates unique constraint` |

还原后 `git diff eb6ddb8 HEAD` 为 **0 行**。

### 一处值得记的踩坑

四个依赖的包路径**都与直觉不同**，靠 `find` 逐个核对才发现：

| 类 | 实际包 | 直觉会写成 |
|---|--------|-----------|
| `ContextBudgetManager` | `application.context` | `application.runtime` |
| `SecurityInputGuard` | `common.guard` | `common.security` |
| `DevOpsIntentRouter` | `application.router` | `application.runtime` |
| `AgentLogService` | `domain.biz.service` | `service` |

ECJ 在缺 jar 时会把「包路径写错」和「jar 缺失」都报成
`cannot be resolved to a type`，两者混在一起无法区分。
可行的判据是**与一个已通过 CI 的同类测试对照噪音量**
（本例：已过 CI 的 42 条 vs 我的 31 条，属同一量级），
再单独筛 `Syntax error|already defined|ambiguous|is not visible` 确认为 0。

---

## 二、告警来源抽接口：评估结论「现在不抽」

71 号文档把这一项标为**中风险**，本轮做了量化评估，结论是**暂不抽象**。
记录理由，避免后人反复纠结。

### 现状量化

- `AlertmanagerWebhook`（厂商专有 DTO）在 `AlertService` 中出现 **6 处**，
  渗透进 `processWebhook` / `processSingleAlert` / `createNewAlert` /
  `inferModule` 等方法签名；
- 该 DTO 的核心是 Alertmanager 特有的
  `labels: Map<String,String>` + `annotations: Map<String,String>` 键值模型，
  外加 `fingerprint`、`generatorURL`、`startsAt/endsAt`。

### 为什么不抽（理由不是「工作量大」）

**抽象会丢信息，而丢掉的恰恰是核心逻辑依赖的部分。**

告警去重键 `computeDedupKey(alertName, service, labels)` 的算法是
「排除 alertname/service/severity 之外的**全部 label** 参与哈希」——
它依赖的正是 Alertmanager「任意键值标签」这个模型本身。

若抽一个中性 `AlertSource` 接口，为了同时容纳
Zabbix（`triggerid` + 固定字段）、云监控（各家 SDK 各异）与 Alertmanager，
中性模型只能退化为两种形态之一：

1. **取最小公倍数**（只留 name/level/time/message）
   → 去重键失去 label 维度，同一服务不同实例的告警会被错误合并，
   而这类合并**不会报错**，只表现为「告警数变少了」；
2. **保留 `Map<String,String> extras` 兜底**
   → 各实现往里塞各自的字段，上层照样得按来源分支处理，
   接口成了一个只增加一层间接、挡不住任何东西的空壳。

**两种都比现状差。**

### 什么时候该抽

出现**第二个真实接入源**时。届时两套字段摆在面前，
才知道中性模型该长什么样——而不是现在凭想象设计一个。

这与 `Notifier` / `Retriever` 的情形不同：
那两处的中性模型（「发一条消息」「给我几段相关知识」）
在各家实现间语义一致，不存在信息丢失。

> 判据：**抽象只有在「它挡住的东西真的换得掉，且换的过程不丢信息」时才有价值。**

### 现有覆盖情况（说明不抽也不等于放着不管）

告警链路已有 4 个测试文件覆盖，其中 `AlertServiceTest` 21 例，
含去重键顺序无关性等核心断言。加上
`AlertWebhookControllerWebTest`、`AlertWebhookChainIntegrationTest`、
`AlertReplayEvaluationTest`，接入契约与压缩逻辑都有保护。

---

## 三、下一步

### 已完成的建议项

- ✅ Single Writer 路径（本轮，18 例 + 注入 3/3）
- ✅ 告警来源接口评估（本轮，结论：不抽，理由已存档）
- ✅ `ToolExecutionRepository` 吞异常审查（77 号，修 2 处 + 11 例）

### 仍受阻（需要你介入）

| 事项 | 阻塞原因 |
|------|---------|
| `run_audit.py` 接入 CI | 需 `.github/workflows/` 写权限 |
| K8s 执行器真实轨道 | 沙箱无 docker/kind |

第一项已连续**五轮**提及。目前审计只能靠我手动跑，
接入后新增命中会自动拦住。请在 Arena 为 GitHub 连接补上 workflows 权限，
或手工在 `ci.yml` 后端 job 加一步：

```yaml
      - name: 代码审计（新增命中即失败）
        run: python3 tools/audit/run_audit.py
```

### 我判断不值得做的（附理由，供你否决）

| 事项 | 不做的理由 |
|------|-----------|
| 前端 `knip` 的 21 个未使用导出 + 85 个类型 | 多为公共 API 面，逐个删除风险高于收益 |
| 告警来源抽接口 | 见第二节：会丢信息，等第二个接入源出现再说 |
| Flyway 接管 | 单一 `init.sql` 已消除双写漂移这个主要风险，剩余价值只是「记录执行历史」 |
| 给 `mapModuleToCategory` 加 null 防御 | 两条上游均保证非 null，属于制造噪音 |
