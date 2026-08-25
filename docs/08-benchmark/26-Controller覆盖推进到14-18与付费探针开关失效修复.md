# 26 · Controller 覆盖 12→14/18 + 一个匿名可刷的付费 LLM 端点

> 本轮补 5 个 Controller 测试（+87 例），过程中查出并修复
> **P1-7 那次「已修复」实际完全落空**的安全缺陷。

---

## 一、查出的缺陷：付费探针的开关从未生效

写 `HealthCheckController` 测试时，看到这段代码：

```java
@GetMapping("/ai-model")
@ConditionalOnProperty(name = "devops.ai.health.ai-model-enabled", havingValue = "true")
public Map<String, Object> checkAiModel() { ... }
```

### `@ConditionalOnProperty` 标在 `@RequestMapping` 方法上完全不生效

它是 **Bean 注册阶段**的条件注解。Controller 这个 Bean 一旦注册，
它的全部请求映射方法都会被 `RequestMappingHandlerMapping` 扫描注册成路由，
**没有任何一步会去看方法上的这个注解**。

### 后果：一个匿名可访问的付费 LLM 端点

这个端点会真实调用 **LLM chat ×2 + embedding ×1**，每次拉取都产生计费。

三件事叠加：

| | 状态 |
|---|---|
| `application.yml` | `ai-model-enabled: false`（配了，但没人读） |
| 文档 | 写明「默认关闭，仅运维手动触发」 |
| `@ConditionalOnProperty` | **无效** → 端点一直开放 |
| `WebConfig` | `/api/v1/health/**` 在**鉴权白名单**里（为 K8s 探针放行） |

结论：**任何人 curl 一下就产生一次 LLM + embedding 计费**。
接上 K8s probe（默认 10s 间隔）就是一天 8640 次。

而这正是 P1-7 声称已经堵上的那个成本失控风险——
`docs/05-development-design/07` 里写着「已修复」。

### 这类缺陷的共同特征

> **配置项存在、文档齐全、代码看着也对，唯独没有任何东西真正读取它。**

上一轮查出的「业务码词表 22 条、`getBizError` 零调用方」是同一类。
它们不会有任何报错，编译期也毫无信号，
**只能靠测试断言「关掉时真的没发生」来发现**。

### 修复

改用 `@Value` 注入布尔值 + 方法内判断，且判断放在调用模型**之前**。

返回 `DISABLED` 而不是抛异常——健康检查端点会被 probe 高频拉取，
抛 5xx 会让探针把整个实例判为不健康并**重启它**，
而「这个探测被关掉了」根本不是实例不健康。

---

## 二、一个测试设计上的教训：只测「关着」是不够的

最关键的断言是：

```java
verifyNoInteractions(turboModel, reasonerModel, embeddingModel);
```

但**只有这一条的话，把开关判断写死成永远返回 DISABLED 也能全绿**——
那样付费探针就彻底废了，而测试不会有任何反应。

所以必须有对照组证明「开启后确实会调模型」。

### 对照组遇到的坑（CI 抓出来的）

最初写成 `@Nested` 内嵌类 + `@TestPropertySource(ai-model-enabled=true)`，
CI 报：

```
enabledProbeCallsModelsAndReportsFailure
JSON path "$.overallStatus" expected:<FAILED> but was:<DISABLED>
```

**`@TestPropertySource` 标在 `@Nested` 上不会覆盖外层类的属性**——
内嵌类继承外层的 `ApplicationContext` 配置。
属性不同就意味着不同的 context，只能拆成独立的顶层测试类。

拆出 `HealthCheckAiProbeEnabledWebTest`，与原类唯一差异就是那个开关，
构成严格对照。

---

## 三、本轮补的 5 个 Controller（+87 例）

### `KnowledgeDocController`（33 例）—— 剩余里优先级最高

342 行、15 端点，集中了三类最容易出错的东西：
**两个并行的状态机、乐观锁、不可逆操作**。

**status 与 indexStatus 是两个状态机，绝不能混用**（6.21 决策）：

- `status` —— 生命周期：DRAFT / PUBLISHED / DEPRECATED
- `indexStatus` —— 向量化：INDEXED / SKIPPED / UNCHANGED / FAILED

「已发布」不等于「可检索」。一篇 PUBLISHED 但向量化 FAILED 的文档，
在列表里看着好好的，**AI 检索却永远命中不到它**——
用户以为「知识库里没这条」，实际是索引挂了。

四种 `IndexOutcome` 逐一钉住。只回「成功/失败」两态的话，
用户无法区分 `SKIPPED`（草稿本就不该建索引，是正确行为）
与 `FAILED`（需要重试）。`UNCHANGED` 同理：若被当成「没建索引」，
用户每改一次标题都会看到「不可检索」的假警报。

**「删除」的默认语义是废弃，不是物理删**：

```java
verify(docService, never()).purge(anyLong(), anyString(), any());
```

若哪天有人把 `deprecate` 的实现改成 `purge`，
用户点一下「删除」就永久销毁了文档，而这个改动在编译期毫无信号。

### `KnowledgeTagController`（17 例）

守两个「会悄悄改动别人数据」的动作：

- **merge 的参数顺序**——颠倒会把「3 合并进 5」变成「5 合并进 3」，
  保留了错误的那个标签，且这是一次不可撤销的批量写
- **delete 的 `replacementId`**——null 与非 null 语义完全不同。
  丢掉它会让「删除并改挂到 X」变成「直接删」，
  **几百篇文档就此失去标签，而界面上没有任何异常提示**

### `KnowledgeManageController`（10 例）

守一个「已停用端点必须真的不做事」。

`/ingest` 曾经的修法是加 `@Deprecated` + 打 WARN **但照常执行**——
一边警告一边继续制造 `doc_id=NULL` 的孤儿切片。
那些切片按 doc_id 清理时**永远删不到，却仍参与检索**：
用户以为删了一篇过时文档，AI 却还在拿它回答问题。

用 `verifyNoInteractions` 钉住它现在真的什么都不做。

### `HealthCheckController`（10 例）+ `HealthCheckAiProbeEnabledWebTest`（2 例）

见上文。另外钉住 `/ping` 零外部调用——它被 probe 每 10s 拉一次，
一旦有人往这里加个查询就是一天上万次隐形负载。

`/db` 的 `DEGRADED`（连接超时未就绪）与 `DOWN`（彻底连不上）
不能混为一谈，两者处置方向不同；连不上时返回 200 + DOWN 而非抛 500——
「连不上」正是这个端点要报告的**结果**。

---

## 四、当前状态

```
后端   49 个测试文件
  ├─ Controller 契约   14/18，共 234 例
  └─ E2E 集成           2 个，16 例
前端   1074 tests
CI     绿
```

### Controller 覆盖明细

| 已覆盖（14） | 例数 | | 已覆盖 | 例数 |
|---|---|---|---|---|
| KnowledgeDoc | 33 | | Alert | 14 |
| AutomationGovernance | 25 | | AuditLog | 12 |
| Approval | 19 | | Ticket | 12 |
| Metrics | 18 | | Auth | 11 |
| KnowledgeTag | 17 | | HealthCheck | 10 |
| KnowledgeCategory | 16 | | KnowledgeManage | 10 |
| SessionMemory | 15 | | Dashboard | 9 |
| | | | TeamMember | 8 |

### 剩余 4 个

| Controller | 说明 |
|---|---|
| `TicketPostmortemController` | 130 行，与工单状态机耦合，**下一个该做的** |
| `SagaController` | 139 行，补偿事务失败语义复杂 |
| `AlertWebhookController` | 已被 E2E 间接覆盖，可只补 WebhookGuard 的拒绝路径（401/429） |
| `DevOpsChatController` | **SSE 流式，`MockMvc` 对异步流支持有限，需单独设计异步测试方案** |

---

## 五、本轮提交

```
（1）test(knowledge): 补 KnowledgeDocController 契约测试 27 例
（2）fix(health): 修付费 LLM 探针的开关完全失效 + KnowledgeTag/KnowledgeManage 测试
（3）fix(test): 付费探针「开启态」用例拆成独立类
```

3 次推送，1 次红（`@TestPropertySource` 在 `@Nested` 上不生效）并已修复。
那次红是有价值的——它拦住的正是「对照组没真正生效」这种最隐蔽的假测试。
