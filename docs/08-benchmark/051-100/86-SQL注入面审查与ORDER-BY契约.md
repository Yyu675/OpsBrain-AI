# 86 · SQL 注入面审查 + ORDER BY 防注入契约

> 本轮无用户指定方向，自主选了「用户输入直达 SQL 文本」这个切面。
> **结论：没有发现新缺陷**——但补上了一处关键的测试空白。

---

## 一、审查范围与结论

先做多切面快扫，选中命中率最高的一个深入：

| 切面 | 结果 |
|------|------|
| `BigDecimal` 用 `equals` 比较（精度陷阱） | 0 命中 |
| SQL 字符串拼接 | 6 处，逐个核实**全部安全** |
| 动态 `ORDER BY`（无法参数化） | 2 处，**全部安全但零测试保护** |

### 6 处 SQL 拼接的核实结果

| 位置 | 拼的是什么 | 判定 |
|------|-----------|------|
| `TicketPostmortemRepository` / `TicketTagRepository` | `IN (?,?,?)` 占位符串 | 安全（占位符数量由集合长度决定，不含用户数据） |
| `AuditLogQueryRepository` ×2 | `where` 变量 | 安全：只拼常量片段，用户输入全走 `?`，且 `LIKE` 做了 `escapeLike` 转义 |
| `ActionAllowlistRepository` / `AutomationPolicyRepository` | 同上 | 安全 |

### 2 处动态 ORDER BY

排序列名**无法用 `?` 占位符**（SQL 语法不允许），只能拼字符串——
这使 `ORDER BY` 成为全项目仅有的两处用户输入进入 SQL 文本的位置。

- `DevOpsTicketRepository.buildOrderBy` —— `Map` 白名单查表，未命中降级默认排序；
- `KnowledgeDocRepository` —— `switch` 常量分支 + `default` 兜底。

**两处都是对的。** 提交前我用 Python 复现了映射逻辑，
12 个注入载荷（含 `DROP` / `UNION` / `--` / 内联注释 `/**/` 绕过）全部降级。

---

## 二、既然没缺陷，为什么还要写测试

这段防护**一行测试都没有**。它的危险在于：

> 白名单的表现是「传了不认识的字段就不生效」，**会被当成 bug 报上来**；
> 而改成直接拼接的版本能支持任意字段，看起来是修好了。
> **把安全边界拆掉的改动，在功能上是正向的。**

所以必须有测试写明「这里的限制是故意的」。

### 测试设计的三个要点

**1. 断言落在「payload 没进结果」而非「等于默认值」**

只比对默认值的话，实现改成「拼接但也追加默认后缀」时仍会通过。
现在逐项断言结果里不含 `drop` / `union` / `;` / `--` / 引号。

**2. 单测不含特殊字符的真实列名**

```java
assertThat(buildOrderBy("password", true)).isEqualTo("create_time DESC");
```

这条是关键。若实现改成「过滤特殊字符后拼接」，
上面那些注入载荷可能仍被挡住，但攻击面已从**零**变成
**取决于正则写得多严**——而按任意列排序可用于逐位推断敏感字段。

**3. 源码级锁住实现方式**

行为断言只能证明「当前输入被挡住了」。额外断言
`SORTABLE_COLUMNS.get(` 必须存在，锁住的是「白名单」这个方式本身。

同时覆盖合法路径（camelCase→snake_case、优先级按业务权重而非字典序、
非 `create_time` 主排序追加稳定二级排序），确保防护没有误伤功能。

---

## 三、注入验证

注入 Q1：把白名单换成 `sortBy.replaceAll("[^A-Za-z0-9_]", "")` 后拼接
——**这正是最容易被当成「优化」的改法**（能支持任意字段了）。

CI 多条断言同时命中：

```
[白名单查表结果为 null 时必须降级，不得回落到用户输入] Expecting actual: ...
expected: "create_time ASC" but was: "createdAt ASC, create_time DESC"
```

第二条尤其说明问题：过滤式实现让 `createdAt` 不再映射为 `create_time`，
**连正常功能都悄悄坏了**（前端字段名被原样当成列名）。

还原后 `git diff e33c788 HEAD -- src/main/java` 为 **0 行**。

---

## 四、本轮踩的坑：ECJ 自检拦不住包路径写错

提交后 CI 编译失败：

```
cannot find symbol: class TicketQuery
location: package com.devops.agent.domain.biz.entity
```

`TicketQuery` 实际在 `domain.biz.repository`，与测试**同包，根本不需要 import**，
而我按直觉写成了 `.entity`。

**为什么本地 ECJ 自检报了「0 语法错误」**：缺少 junit/mockito/assertj 等 jar 时，
ECJ 把「包路径写错」和「jar 缺失」**都报成 `cannot be resolved to a type`**，
两者混在同一堆噪音里无法区分。

78 号报告记过这个坑，当时的对策是「与已过 CI 的同类测试对照噪音量」——
但那只能发现**数量级异常**，发现不了**单个符号写错**。

### 已固化为 AGENTS.md 3.9 的一节

补了一个更直接的自检：逐个提取 import，对 `com.devops.*` 的
按路径检查对应 `.java` 是否存在。另加两条：
同包的类不需要 import（写了反而暴露路径判断错误）；
用某个测试注解前先 grep 项目里有没有先例，确认依赖在 classpath 上。

---

## 五、当前状态

- 新增 `OrderByInjectionContractTest`（16 例），CI 绿（`d2b2227`）；
- 本轮**未发现新缺陷**，但把一处「正确却无保护」的安全边界固定了下来；
- AGENTS.md 3.9 新增「本地 ECJ 自检的能力边界」。

### 后续（两项仍需你的输入）

| 事项 | 需要确认什么 |
|------|-------------|
| 缺口 A 的 Redis 实现 | 是否真要多实例部署——单实例加上去反而白付 Redis 往返代价 |
| 缺口 B 全量对话归档 | 是否有合规审计 / 模型评测取数的实际需求（触及 SSE 主流程） |

我可以自主继续做的：按同样方式审查其余切面
（如时区处理、分页边界、并发写幂等），每轮产出一份「有则修、无则固化」的结论。
