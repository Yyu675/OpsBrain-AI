# 75 · saveTicket 测试补齐，以及一次「假测试」的暴露

> 承接 74 号报告排定的优先级：`TicketService.saveTicket`（AI 建单主路径）。
> 本轮最有价值的产出不是那 24 个用例，而是**其中一个用例被证明是假的**。

---

## 一、本轮结果

| 项目 | 变化 |
|------|------|
| 新增测试 | `TicketSaveTicketTest` **24 例** |
| 审计命中 | 20 → **19** 处 |
| 基线条目 | 22 → **21** 条 |
| 注入验证 | E1 **首轮未检出**（测试太松）→ 加固后命中；E2/E3 命中 |

---

## 二、为什么 saveTicket 值得单独测

它是 AI 自动建单的**唯一落库入口**（`DevOpsAgentServiceImpl` 的 Single Writer
调用点），6 个入参 + 5 处派生逻辑，此前零直测。

它写下的不只是一行工单，还有三项**后续无法重算**的派生数据，错了都属于
「事后才发现」：

| 派生数据 | 出错后的表现 |
|---------|-------------|
| SLA 截止时刻 | 超时判定、SLA 看板、首响告警全部失准，**而工单本身看起来完全正常** |
| 优先级归一化 | 非法值入库 → 排序权重与 SLA 计时落到兜底分支，表现为「P0 排在 P2 后面」这类没人能立刻解释的现象 |
| `sourceTraceId` | 丢了就无法从工单回溯「当时模型是怎么判断的」——这正是 AI 建单最需要的审计能力 |

---

## 三、本轮最重要的发现：一个通过了 CI 的假测试

### 事情经过

按硬纪律做注入-还原验证，注入 E1：把 SLA 基准从「建单时刻」改成「当前时刻」

```java
- applySlaDeadlines(ticket, ticket.getCreateTime());
+ applySlaDeadlines(ticket, null);   // null 时回退 LocalDateTime.now()
```

**CI 全绿。** 我的测试抓不到它。

### 原因

`applySlaDeadlines` 传 null 时回退 `LocalDateTime.now()`，
而 `saveTicket` 里 `createTime` 也是刚设的 `now()`——两者相差**几十微秒**。

而我原来的断言是：

```java
assertThat(Duration.between(t.getCreateTime(), t.getResponseDeadline()).toMinutes())
        .isEqualTo(30);
```

`toMinutes()` 做整除截断，微秒级偏差被抹平，两种实现都得到 30。

### 这为什么严重

「基准取哪个时刻」恰恰是这个方法**最该守住**的东西。
代码注释里就写着理由：

> 若优先级中途调整时用当前时刻重算，等于把已消耗的时间一笔勾销——
> 一张已挂 3 小时的工单改优先级后会显示「SLA 消耗 0%」，考核数据失真。

也就是说，我写了一个**名字叫「SLA 截止时刻按建单时刻派生」、
实际却分辨不出基准取错**的用例。它会一直是绿的，
并且让后来者以为这条契约有人守着。

### 修法

改为断言**精确相等**，微秒级偏差即可分辨：

```java
assertThat(t.getResponseDeadline()).isEqualTo(t.getCreateTime().plusMinutes(30));
assertThat(t.getResolveDeadline()).isEqualTo(t.getCreateTime().plusHours(8));
```

重新注入 E1，CI 如期变红：

```
expected: 2026-08-27T08:09:58.577057284
 but was: 2026-08-27T08:09:58.577062512
```

相差约 5 微秒——正是原来被 `toMinutes()` 吃掉的那部分。

### 教训（已记入本文档，供后续参照）

> **凡是用聚合/取整函数（`toMinutes`、`toHours`、`toFixed`、`round`）
> 做的断言，都要问一句：被截断的那部分里，会不会正好藏着我要防的缺陷？**

这条与之前踩过的 `40.55.toFixed(1) === '40.5'` 是同一类问题的两面：
那次是**精度导致断言过严**，这次是**精度导致断言过松**。后者危险得多，
因为它不会让 CI 变红，没有任何人会发现。

---

## 四、注入-还原验证结果

| 注入项 | 结果 | 检出信息 |
|--------|------|---------|
| E1 SLA 基准误用当前时刻 | **首轮漏检 → 加固后命中** | 纳秒级差异 `...577057284` vs `...577062512` |
| E2 优先级不归一化 | 命中 | `expected "P0" but was " p0 "` / `expected "P2" but was null` |
| E3 入库零行放行 | 命中 | `Expecting code to raise a throwable` |

还原后 `git diff 917d10b HEAD -- src/main/java/` 为 **0 行**。

### 顺带：唯一性断言拦下两次打偏

注入 E1、E2 时都触发了断言：

```
AssertionError: E1-SLA基准错为当前时刻: OCCURS 2 TIMES - ambiguous
AssertionError: E2-优先级不归一化: OCCURS 2 TIMES - ambiguous
```

`applySlaDeadlines(ticket, ticket.getCreateTime())` 与
`ticket.setPriority(normalizedPriority)` 在 **saveTicket 与手动建单里各有一处**。
若用 `replace(..., 1)` 直接改第一处，很可能打到另一个方法上，
验证结论就完全不成立。改用带上下文的锚点（B0 注释、`setStackTrace` 行）后定位准确。

---

## 五、其它锁住的行为

| 行为 | 不这样做的后果 |
|------|--------------|
| 返回的工单号 == 落库 id | 不一致时用户点进去是 404 |
| 入库影响 0 行必须抛错 | Saga 记为成功，补偿时找不到对象，脏数据判定全乱 |
| 仓储异常保留根因 | 只说「入库失败」丢掉 `duplicate key`，无从判断是主键冲突还是连接问题 |
| 落库先于活动流 | 反过来会留下指向不存在工单的孤儿活动 |
| Redis `increment` 返回 null 降级为 1 | 抛 NPE 会让整条 AI 建单链路失败；重号可由唯一约束兜住，建单失败却是用户直接可见的 |
| 序号为 1 才设 48h 过期 | 每次都设会让当天键 TTL 被反复延长 |
| `HIGH → P1`（不是 P0） | 与 `migration_v16` 存量迁移口径一致；误升 P0 会让 15 分钟首响时限失去可信度 |

### 一处查证后确认「不是缺陷」的地方

`mapModuleToCategory` 里 `module.toUpperCase()` 在 module 为 null 时会 NPE。
追查两条上游后确认是**理论风险**而非现存缺陷：

- `TicketDraftParser` 用 `node.path(field).asText("")`，恒非 null；
- `DevOpsTools` 侧 `ToolParameterValidator.validateTicketParams` 显式拒绝空 module。

故**不改产品代码**，只用测试锁住现有契约。改一个没有触发路径的 NPE，
除了增加噪音没有别的作用。

---

## 六、下一步推进建议

### 优先级 1 —— 复核既有测试里的「聚合断言」

本轮暴露的假测试大概率不止一处。建议扫一遍全仓测试中的
`toMinutes()` / `toHours()` / `toFixed()` / `Math.round`，
逐个判断被截断的精度里是否藏着要防的缺陷。
这比再补几个新用例价值高——**一个假绿的测试比没有测试更危险**。

### 优先级 2 —— 基线剩余 5 条「确属缺口」

1. `KnowledgeDocService.moveCategory` / `renameCategoryDocuments`
   —— 影响知识库树结构与检索过滤；
2. `TicketAiAnalysisService.recordFeedback` —— 模型质量评估的数据来源；
3. `AgentMemoryManager.recordUserTurn` / `recordCompletedTurn` —— 三层记忆写入。

### 优先级 3 —— 仍受阻于权限的两项

- Flyway 接管（现已降级：单一 `init.sql` 消除了双写漂移这个主要风险）；
- `run_audit.py` 接入 CI（`run: python3 tools/audit/run_audit.py` 一行）。

两项都需要 `.github/workflows/` 写权限。**这是目前唯一需要你介入的事**：
在 Arena 里为 GitHub 连接补上 workflows 权限，或由你手工改一次 `ci.yml`
（改法我可以先写好贴出来）。

### 不建议做的

- **前端 knip 报的 21 个未使用导出 + 85 个未使用类型**：多为公共 API 面，
  逐个删除风险高于收益；
- **给 `mapModuleToCategory` 加 null 防御**：无触发路径，属于制造噪音。
