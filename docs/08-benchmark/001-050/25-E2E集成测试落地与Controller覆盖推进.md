# 25 · E2E 集成测试落地 + Controller 覆盖推进到 11/18

> 两条线并行：填上核查发现的 E2E 空白，同时继续补 Controller 契约测试。
> 本轮 4 次推送，**全部一次通过 CI**。

---

## 一、一个关键决策：没有引入 Testcontainers

上一轮报告建议用 Testcontainers 做集成测试。**动手前先看了 CI 配置，发现不需要。**

`.github/workflows/ci.yml` 里**已经起了 PostgreSQL + Redis 两个 service 容器**：

```yaml
services:
  postgres:
    image: ankane/pgvector:v0.5.1
    ports: [25432:5432]
  redis:
    image: redis:7.0-alpine
    ports: [26379:6379]
```

端口与 `application-dev.yml` 对齐，建表脚本也已在 CI 步骤中执行
（`sql/init.sql` + 全部 `migration_v*.sql`）。

再引 Testcontainers 等于**在容器里再套一层容器**：

| | 现有 services | 加 Testcontainers |
|---|---|---|
| 新增依赖 | 0 | 2 个（testcontainers + junit-jupiter） |
| 镜像拉取 | 已有 | 再拉一次 |
| CI 耗时 | 不变 | 约翻倍 |
| 隔离性收益 | — | **在这里没有额外收益** |

隔离性是 Testcontainers 的核心卖点，但本项目的 CI 本来就是一次性容器、
跑完即销毁。**为了一个已经具备的性质付出双倍成本，不划算。**

> 这条判断值得记下来：工具选型前先看现有基础设施能不能直接用。
> 上一轮我自己写的建议，落地时被自己否掉了——**建议是基于当时的信息，
> 动手时信息更全，改主意是对的。**

---

## 二、E2E 测试：填上完全空白的一层

### 之前的状况

```
E2E / 联调        0        ← 完全空白
契约测试        129 例     ← Service 全 mock
单元测试        469 + 1074
```

两条核心链路**从未被任何测试完整跑通过**。

### 1. 告警链路（`AlertWebhookChainIntegrationTest`，6 例）

`Alertmanager 推送 → WebhookGuard → 去重 → 落库 → 自动建单 → 可查`

这条链路跨了 Controller、Guard、Service、两个 Repository 和
**真实的 PostgreSQL partial unique index**——每一层单测都绿，拼起来仍可能不通。

最有价值的一例：

```java
@DisplayName("数据库唯一索引与应用层去重一致：活跃告警同 dedup_key 只有一条")
```

`sql/init.sql` 里有：

```sql
CREATE UNIQUE INDEX uk_alert_active_dedup ON sys_alert (dedup_key)
  WHERE status IN ('FIRING','ACKNOWLEDGED');
```

应用层去重逻辑若与这个索引不一致，第二次插入会直接抛约束冲突。
**这是 mock 掉 Repository 的单元测试永远发现不了的那类问题。**

另一例覆盖了一个容易想漏的场景：

```java
@DisplayName("恢复推送把告警标记 RESOLVED，并释放去重键供下次故障使用")
```

去重键不释放的话，同一故障**第二次发生时会被当成「重复」丢掉**，
没有任何人会收到通知。这个 bug 一旦存在，表现是「故障复发时系统装作没看见」。

### 2. 鉴权链路（`AuthLoginChainIntegrationTest`，10 例）

`登录 → 签发 token → 带 token 访问 /me → 登出 → token 立即失效`

补的正是 `AuthControllerWebTest` 在类注释里**声明覆盖不到**的那一段。

核心用例：

```java
@DisplayName("登出是「真登出」：旧 token 立即失效，而不是只让前端删掉")
```

「登出」有真假之分：

- **假登出**——前端删掉本地 token 就算完。但那个 token 在服务端仍然有效，
  任何持有它的人（浏览器历史、日志、抓包）都能继续用
- **真登出**——服务端让 token 失效

**两者在界面上表现完全一样**（都跳回登录页），
只有拿旧 token 再请求一次才能区分。
这条断言无论如何都无法用 mock 验证——要验的恰恰是 Sa-Token 与 Redis 的真实交互。

同类的还有两条：账号**登录后被停用**（模拟离职处理）、账号被删除，
已签发的 token 都必须立即失效。否则离职员工手里的 token
会一直有效到自然过期，**停用操作形同虚设**。

### 清理策略：为什么不用 `@Transactional` 回滚

两个 E2E 类都没用事务回滚，原因不同但都成立：

- **告警链路**：内部有独立事务边界（建单走 `TicketService`），
  回滚会让测试看不到真实的提交结果
- **鉴权链路**：Sa-Token 的会话写在 **Redis** 里，事务管不到它。
  回滚反而造成「库里没这个用户、但 Redis 里还有他的会话」的错位状态

改为随机 `runId` / 随机用户名 + 用例结束精确删除，并发与重跑互不干扰。

---

## 三、Controller 契约测试推进到 11/18

### `ApprovalControllerWebTest`（19 例）

这组端点是**「AI 能不能动生产系统」的最后一道闸门**——
批准之后编排层会立即重放执行被拦下的动作。

最重要的一条：

```java
@DisplayName("审批人取自服务端登录态，绝不接受请求体传入")
```

用例往请求体里塞一个伪造的 `approver`，断言它被忽略。
**审批记录是事后追责的唯一依据，可伪造等于整套审批形同虚设。**

另外两条：

- **40102（已被他人处理）必须与 40004（不存在）区分**。
  两个管理员同时点批准时，第二个人需要知道「已经被处理了」，
  而不是「这条审批不存在」——后者会让他以为数据出了问题
- **批准了但执行失败要如实说**。人的决策已生效（不回退）但动作没做成，
  只说「已批准」会让管理员以为事情办完了，而故障还在

### `AuthControllerWebTest`（11 例）

登录端点在鉴权白名单里——**任何人都能调**。
它是唯一一个「未认证流量直达业务代码」的入口，
所以它的入参约束是**安全措施**而不是体验优化。

```java
@DisplayName("超长密码（>128）被拒 —— 这条是防 BCrypt DoS，不是体验优化")
```

BCrypt 是刻意设计的慢哈希，成本随输入增长。不限长时，
攻击者提交超长字符串就能迫使服务端反复做昂贵哈希，少量并发即可耗尽 CPU。
`@Size(max=128)` 删掉后**编译照样通过、功能照样正常，只有被打的时候才知道**。

密码泄漏检查**故意做了两遍**：契约测试用 mock 的 `User` 验一次，
E2E 用**真实库里的 BCrypt 哈希**再验一次。
`toUserView` 手工挑字段的做法一旦被改成序列化实体，
mock 那版可能因桩数据没设 password 而漏掉，真实数据这版会立刻红。

---

## 四、当前状态

```
后端   44 个测试文件
  ├─ Controller 契约   11/18，共 159 例
  └─ E2E 集成           2 个，16 例   ← 本轮新增
前端   1074 tests
CI     绿（本轮 4 次推送全部一次通过）
```

### Controller 覆盖明细

| 已覆盖（11） | 例数 |
|---|---|
| AutomationGovernance | 25 |
| Approval | 19 |
| Metrics | 18 |
| KnowledgeCategory | 16 |
| SessionMemory | 15 |
| Alert | 14 |
| AuditLog | 12 |
| Ticket | 12 |
| Auth | 11 |
| Dashboard | 9 |
| TeamMember | 8 |

### 剩余 8 个未覆盖

| Controller | 建议 |
|---|---|
| `KnowledgeDocController` | 342 行、15 端点、含乐观锁与发布态流转，**优先级最高** |
| `TicketPostmortemController` | 与工单状态机耦合 |
| `SagaController` | 补偿事务，失败语义复杂 |
| `AlertWebhookController` | 已被 E2E 间接覆盖，可只补 WebhookGuard 拒绝路径 |
| `KnowledgeManageController` / `KnowledgeTagController` | 较简单 |
| `HealthCheckController` | 只读探针，风险最低 |
| `DevOpsChatController` | **SSE 流式，`MockMvc` 对异步流支持有限，需单独设计异步测试方案，不适合塞进这一批** |

---

## 五、本轮提交

```
（1）test: 补 L2 告警链路端到端集成测试 + ApprovalController 契约测试
（2）test(auth): 补 AuthController 契约测试 12 例
（3）test(auth): 补鉴权链路端到端集成测试 9 例——验证「登出是真登出」
```

四次 CI 全部一次通过——与上一轮连续三次红（桩失真、断言方向错）形成对比。
差别在于这轮动手前先把被测代码的真实签名与调用路径查清楚了，
而不是凭对 API 的印象写桩。
