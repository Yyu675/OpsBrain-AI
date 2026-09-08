# 23 · Controller 契约测试补齐（2/18 → 9/18）与「4xx 被吞成 500」的修复

> 本轮取舍：暂停 `TicketDetail.vue` 第 5 步拆分，转去补后端 Controller 测试。
> 理由见文末「为什么先做这个」。

---

## 一、本轮最重要的产出不是测试，是测试逼出来的一个缺陷

### 现象

写 `TeamMemberControllerWebTest` 时想加一条边界用例：
`?includeDisabled=yes-please`（非法布尔值）应该返回什么。

按常识应该是 400。实际是 **HTTP 500 + 50001「服务内部异常，请联系管理员」**。

### 成因

`GlobalExceptionHandler` 底部有一个兜底处理器：

```java
@ExceptionHandler(Exception.class)
@ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
public ApiResponse<Void> handleException(Exception ex) { ... }
```

而 Spring MVC 的异常解析器是**有优先级的**：

```
ExceptionHandlerExceptionResolver   ← 我们的 @RestControllerAdvice 在这里
DefaultHandlerExceptionResolver     ← Spring 内建的 4xx 映射在这里
```

前者排在前面。这意味着：**只要没在 `GlobalExceptionHandler` 里显式接管，
Spring 本来会正确映射成 400/405 的绑定类异常，会被兜底分支抢先捕获**，
统统变成 500。

这不是「少写了几个 handler」，而是加了兜底之后，
Spring 自带的那套 4xx 映射被整体架空了——而这一点从代码上完全看不出来。

### 实测受影响的四类请求

| 场景 | 例子 | 修复前 | 修复后 |
|---|---|---|---|
| 缺必填参数 | `GET /metrics/instant`（漏传 metric） | 500 | 400 / 40001 |
| 参数类型不符 | `?days=abc`、`/alerts/undefined` | 500 | 400 / 40001 |
| 请求体不可解析 | JSON 语法错、body 为空 | 500 | 400 / 40001 |
| 方法不支持 | 把 PUT 发成 POST | 500 | 405 / 40001 |

其中 `/alerts/undefined` 值得单独说：前端拼路径时变量没取到值，
得到的就是字面量 `undefined`。这是**很常见**的前端 bug。

### 用户可见后果（双向的）

**对调用方**：前端 http 客户端按 5xx 判定「服务端故障」而发起重试。
但少传一个参数，重试多少次都不会好。用户看到的是转圈几秒后弹
「服务内部异常，请联系管理员」——一句他无法行动的话，
而真正的原因是某个参数写错了。

**对运维**：多出一条本不该存在的 5xx 错误率毛刺。
排障时看到 5xx 上升，第一反应是查服务器、查数据库、查 GC，
而真正的原因在调用方。**把客户端错误计入服务端错误率，
等于给自己的监控注入噪音。**

### 修复中的两个细节

```java
// 带参数名，不带参数值
return ApiResponse.error(40001, "参数「" + ex.getName() + "」格式不正确，应为 " + expected);
```

值来自用户输入，原样回显等于在错误消息里开了一个反射型输出点。
参数名足够定位问题。

```java
// 不透传 Jackson 的原始消息
return ApiResponse.error(40001, "请求体格式不正确或为空");
```

Jackson 的消息长这样：
`...XxxController$XxxRequest["version"]` —— 把内部包结构和类名公开出去了。

---

## 二、新增 7 组 Controller 契约测试

| 文件 | 用例 | 守的是什么 |
|---|---|---|
| `MetricsControllerWebTest` | 18 | 数据源不可用的语义、null≠0、PromQL 边界 |
| `KnowledgeCategoryControllerWebTest` | 16 | 两套异常路径的状态码差异、删除的前置条件 |
| `SessionMemoryControllerWebTest` | 15 | 自算 offset 的边界、清热记忆≠清会话 |
| `AlertControllerWebTest` | 14 | 分页钳制、处置竞态、详情三态 |
| `AuditLogControllerWebTest` | 12 | 时间解析、统计与列表同源、链路空结果 |
| `DashboardControllerWebTest` | 9 | 下钻口径不能静默错配 |
| `TeamMemberControllerWebTest` | 8 | 名单必须与库一致 |
| **合计新增** | **92** | |

加上原有 `TicketControllerWebTest`(12) 与 `AutomationGovernanceControllerWebTest`(25)，
Controller 层共 **129 例 / 9 个文件**，覆盖 **9/18** 个控制器。

### 几条值得记下来的断言

**`MetricsController` —— null 不能伪装成 0**

```java
// NaN → null，不是 0
.andExpect(jsonPath("$.data.samples[0].value").doesNotExist())
// 但 0 本身是有效读数，必须原样保留
.andExpect(jsonPath("$.data.samples[0].value").value(0.0))
```

「取不到 CPU」和「CPU 0%」是完全不同的两件事，
后者会让扩容决策彻底跑偏。这条不变式贯穿 L2 三页，
现在后端这一侧也有测试守着了。

**`MetricsController` —— 未知指标 ID 不能只是「被拒」，还要「没查」**

```java
verify(prometheus, never()).query(anyString());
```

只断言返回 40001 是不够的。如果 Controller 先发起了查询再报错，
指标目录「只暴露 ID 不接受任意 PromQL」的安全边界就形同虚设。

**`DashboardController` —— 口径字段是契约，不是调试信息**

```java
.andExpect(jsonPath("$.data.callTrendScope").value("GLOBAL"))
```

工单两条线支持按服务下钻，成本与命中率来自 `sys_agent_call_log`（无服务维度）。
用户在页面上选了「MySQL」，看到成本曲线也在那里，
很自然会以为那是 MySQL 的成本。`callTrendScope=GLOBAL` 是前端做口径标注的唯一依据。

**`KnowledgeCategoryController` —— 钉住「不一致」本身**

这是全项目唯一一个「一半自己 catch、一半交给全局处理器」的控制器：

| 异常 | 本地 catch | 全局处理器 |
|---|---|---|
| `IllegalArgumentException` | HTTP **200** + 40001 | HTTP **400** + 40001 |
| `IllegalStateException` | HTTP **200** + 40001 | HTTP **409** + 40004 |

前端 http 客户端同时看 HTTP 状态与业务码，这个差异会实打实影响错误分支走向。

我**没有**顺手统一它——那会同时改动前端行为，属于另一次重构。
这组用例钉住**当前真实行为**：将来收敛时它们会立刻变红，
提醒同步改前端，而不是等用户报「创建失败但没提示」。

**`SessionMemoryController` —— 清热记忆 ≠ 清会话**

```java
.andExpect(jsonPath("$.data").value(containsString("温记忆")))
```

这个端点只清最近对话原文，关键事实必须保留（「重新开始但保留结论」场景）。
提示语是用户判断「我这一下会丢什么」的唯一依据。
若有人顺手把它改成一起清，用户点一次「清空对话」
就会丢掉几十轮排障积累出的结论，且不可恢复。

---

## 三、CI 抓到的一个错误

第一次推送后端红了一处：

```
constructor OptimisticLockException cannot be applied to given types;
  found:    java.lang.String
  required: java.lang.String,java.lang.Integer,java.lang.Integer
```

我按单参数写了 `new OptimisticLockException("文档已被他人修改，请刷新后重试")`，
但它是三参构造 `(resourceId, expectedVersion, actualVersion)`，
消息由异常自己按版本号拼装。

这个设计是对的：它保证所有版本冲突提示口径一致，
且都以「刷新后重新提交」这个可执行动作收尾，
而不是各处自己写一句「版本冲突」让用户不知道该做什么。

改成三参后顺势加了断言：消息里必须含「请刷新」。
将来若有人把提示改回技术术语，测试会拦下。

> **沙箱环境说明**：Maven 全部镜像在本沙箱不可达，
> 本地只能用 ECJ 做语法级自检（能可靠查出语法错误、record 组件冲突），
> 类型级错误依赖 CI 反馈。本次即是 CI 发挥了它该有的作用。
> 读 CI 失败原因仍走 annotations API——原始日志在 Azure blob，被防火墙挡住。

---

## 四、为什么先做这个，而不是继续拆 TicketDetail

上一轮结束时的判断是：`TicketDetail.vue` 已从 2735 行降到 2026 行，
第 5 步预计再减 200 行，**收益已经递减**。而剩余的 B2/B3 表单彼此耦合，
强行拆开会让一次处置动作的校验、请求、状态同步散落三处。

相比之下，Controller 测试 2/18 是一个**面积大得多的空白**：
99 个端点里绝大多数没有任何契约保护。

而且这轮验证了一件事：**补契约测试本身就是在查缺陷**。
本轮那个「4xx 被吞成 500」的问题，是写第 4 个测试文件时
顺手加一条边界用例才发现的——它已经存在很久，
影响每一个带参数的端点，但没有任何人报过 bug，
因为它的表现是「偶尔弹一句服务内部异常」。

---

## 五、当前状态

```
后端   9/18 Controller 有契约测试，共 129 例
前端   1066 tests / 54 files
CI     连续绿（本轮 3 次推送，第 1 次红并已修复）
```

### 剩余 9 个未覆盖的 Controller

按建议优先级：

| Controller | 行数 | 优先级理由 |
|---|---|---|
| `ApprovalController` | 152 | 审批是 L3 治理的执行入口，限 ADMIN |
| `AuthController` | 126 | 登录端点免鉴权，且 BCrypt 长度上限是防 DoS 的 |
| `KnowledgeDocController` | 342 | 端点多、含乐观锁与发布态流转 |
| `AlertWebhookController` | 111 | 对外暴露给 Alertmanager，有 WebhookGuard 限流 |
| `TicketPostmortemController` | 130 | 复盘数据，与工单状态机耦合 |
| `SagaController` | 139 | 补偿事务，失败语义复杂 |
| `KnowledgeManageController` | 119 | — |
| `KnowledgeTagController` | 66 | — |
| `HealthCheckController` | 171 | 只读探针，风险最低 |
| `DevOpsChatController` | 386 | **SSE 流式，`@WebMvcTest` 难覆盖，建议单独设计** |

`DevOpsChatController` 需要说明：它是 SSE 流式端点，
`MockMvc` 对异步流的支持有限，
用常规切片测试只能验证「连接建立」而验证不了事件序列。
它应该走一套单独的异步测试方案，不适合塞进这一批。
