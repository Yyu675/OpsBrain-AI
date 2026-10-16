# 27 · Controller 覆盖收官 14→17/18 + 日期解析被吞成 500

> 本轮补 4 个 Controller（+64 例），修 1 个真实缺陷，
> 并纠正了自己一处错误的测试假设。

---

## 一、缺陷：填错日期格式，用户被告知「请联系管理员」

`TicketPostmortemController` 里有这么一行：

```java
item.setDueDate(req.dueDate() != null ? LocalDate.parse(req.dueDate()) : null);
```

`DateTimeParseException` 继承自 `RuntimeException`，
`GlobalExceptionHandler` 此前没有接管，会落到兜底分支返回 **HTTP 500**。

### 为什么上一轮的修复没覆盖到它

第 23 轮补过 `MethodArgumentTypeMismatchException` → 400 的映射。
但那条通道只管**Spring 类型绑定**产生的异常。

这里的 `dueDate` 是请求体里的 `String` 字段，
Spring 绑定时它就是个合法字符串，**解析发生在业务代码里**，
因此完全绕过了那条通道。

### 用户视角

填了 `2026/09/30`（斜杠）或 `2026-9-5`（月份没补零）：

| | 修复前 | 修复后 |
|---|---|---|
| 用户看到 | 「服务内部异常，请联系管理员」 | 「日期格式不正确，应为 yyyy-MM-dd（如 2026-08-25）」 |
| 用户行为 | **真的去找管理员** | 自己改对 |
| 管理员看到 | 日志里一条 5xx | — |
| 管理员行为 | 往服务端故障方向排查 | — |

**两个人都被引向错误方向，而真实原因只是日期少了个零。**

修复：映射为 40001/400，消息给出期望格式而不是回显用户输入——
告诉他该怎么写才是可执行的下一步。

---

## 二、CI 纠正了我一处错误的测试假设

我写了这条：

```java
@DisplayName("overdue 传非布尔值 → 400，不静默当成 false")
mockMvc.perform(get("...").param("overdue", "yes"))
       .andExpect(status().isBadRequest());
```

CI 报 `expected:<400> but was:<200>`。

查证后确认**是我的断言错了**：Spring 的 `StringToBooleanConverter`
接受四组同义词——`true/false`、`on/off`、**`yes/no`**、`1/0`。
`yes` 是合法的 `true`，不是非法输入。

改成两条：

- 一条正面确认 `yes` / `1` 都被解析为 `true`（并 verify 传给 Service 的确实是 `true`）
- 一条用真正不在同义词表里的 `maybe` 验证 400

**第一条特意保留并写明缘由**，是为了防止后来者看到 `yes` 能通过
就以为绑定校验失效，反过来去「修」一个并不存在的问题。

> 顺带核对了 `TeamMemberControllerWebTest` 里的同类用例——
> 它用的是 `yes-please`，不在同义词表内，断言正确，无需改。

---

## 三、本轮补的 4 个 Controller（+64 例）

### `TicketPostmortemController`（21 例）

复盘数据有个特点：**写进去就是给几个月后的人看的**。
它不参与任何实时流程，唯一价值是故障复发时有人翻出来看。
所以这里的缺陷潜伏期极长——写坏了当时没人发现，
等到真需要它的那天才知道数据是错的。

守住的：

- **路径 id 覆盖请求体**。用例往 body 里塞 `TK-9999-9999`，
  断言实际写入的是 URL 里的工单号。若信任 body，
  前端拼错就会把 A 工单的复盘写进 B 工单，**两边都不报错**
- **「还没写复盘」不是错误**。返回 null 时 code 仍为 0。
  若给 40004，前端会弹「数据不存在」，
  而正确表现是展示一个空白表单让人填
- **null 不能伪装成 0**：`impactDuration` 补成 0 意味着故障瞬间自愈，
  会让 MTTR 统计凭空变好看；`docId` 补 0 会指向一个不存在的文档
- **`overdue=true` 必须透传**。「只看逾期未完成」是改进项看板存在的主要理由，
  被丢掉后看板退化成「显示全部」，真正该被催的几条淹没在几十条已完成里

### `SagaController`（16 例）

这组端点是「自动化收不了尾时」的最后兜底。补偿失败意味着系统半残——
某些副作用已产生但没能回滚。按 Agent Methodology §9.4 会标记
`MANUAL_INTERVENTION_REQUIRED` 并通知人，
**但没有配套的人工处理入口，脏数据就永久残留**。

- **状态要可读，不只是可判断**。`toDetail` 把枚举同时输出
  机器码 + 中文标签 + 处置提示。`failureHint` 是这个页面最有用的一列，
  直接回答「这条该不该重试」——少了它，运维得自己翻代码里的枚举定义
- **`needsAttention` 必须来自后端枚举**。前端自己维护清单的话，
  后端新增一个需关注状态时前端不同步，
  那条记录就**静默地不出现在待处理列表里**
- **部分失败要如实报**。重试动作本身执行完了，失败的是其中一步。
  报成整体错误会让运维以为「没跑起来」而反复点，
  而每点一次都在重跑那些已经成功的补偿
- **GET 触发补偿返回 405 且 verify(never())**——补偿是写操作，
  能被 GET 触发的话，浏览器预取、爬虫、甚至一次误粘贴到地址栏
  都会回滚生产数据

### `AlertWebhookController`（10 例）—— 专攻拒绝路径

E2E 已经验证过成功路径，但那条链路里 `WebhookGuard` 是放行的，
**它的拒绝分支从未被覆盖过**。

这个端点的客户端是 **Alertmanager，不是人**。机器依据状态码决定要不要重投：

| 场景 | 状态码 | 为什么 |
|---|---|---|
| 密钥不对 | 401 / 40104，**不带 Retry-After** | 配置问题，重试多少次都一样。带 Retry-After 会诱导它一直退避重试 |
| 触发限流 | 429 **+ Retry-After** | 让它退避后重投，**告警最终不丢** |
| 空负载 / 开关关闭 | 200 | 非 200 会让它反复重推 |

> 限流这里绝不能返回 200 静默丢弃：
> **对运维平台而言，悄悄丢掉告警比慢一点收到告警危险得多。**

另外两条容易被忽略的：

- **鉴权在总开关之前执行**。顺序若反过来，运维临时关闭告警接收的
  那段时间里，端点会变成一个无鉴权的开放接口
- **Retry-After 至少 1 秒**。传 0 等于「立刻重试」，把限流变成空转

### `KnowledgeManage` / `KnowledgeTag`（上一轮已补，此处不赘）

---

## 四、当前状态

```
后端   52 个测试文件
  ├─ Controller 契约   17/18，共 281 例
  └─ E2E 集成           2 个，16 例
前端   1074 tests
CI     绿
```

### Controller 覆盖明细

| Controller | 例数 | | Controller | 例数 |
|---|---|---|---|---|
| KnowledgeDoc | 33 | | Alert | 14 |
| AutomationGovernance | 25 | | Ticket | 12 |
| TicketPostmortem | 21 | | AuditLog | 12 |
| Approval | 19 | | Auth | 11 |
| Metrics | 18 | | KnowledgeManage | 10 |
| KnowledgeTag | 17 | | HealthCheck | 10 |
| Saga | 16 | | AlertWebhook | 10 |
| KnowledgeCategory | 16 | | Dashboard | 9 |
| SessionMemory | 15 | | TeamMember | 8 |

### 唯一未覆盖：`DevOpsChatController`

**这不是遗漏，是刻意留的。**

它是 SSE 流式端点（386 行）。`MockMvc` 对异步流的支持有限：
用常规 `@WebMvcTest` 切片只能验证「连接建立成功」，
验证不了**事件序列**——而 SSE 的价值恰恰在事件序列
（token 流、工具调用事件、错误事件、完成事件的顺序与内容）。

硬塞进这一批的结果是得到几条断言「HTTP 200」的用例，
覆盖率数字上去了，**实际什么都没保证**——
这正是本会话反复在避免的那类假测试
（参见第 26 轮「只测关着不测开着」那个教训）。

它应该走一套单独设计的异步测试方案：
`MockMvc` 的 `asyncDispatch` + `SseEmitter` 事件收集，
或者直接用 `WebTestClient` 消费流。这是一次独立的工作。

---

## 五、本轮提交

```
（1）fix(exception): 日期解析失败被吞成 500；补 TicketPostmortemController 20 例
（2）fix(test): overdue=yes 是合法 true 不是非法值；补 SagaController 17 例
（3）test(alert): 补 AlertWebhookController 拒绝路径 11 例
```

3 次推送，1 次红（我自己的测试假设错误）并已修正。
