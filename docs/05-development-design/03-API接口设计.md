# 🔌 OpsBrain AI 智能运维助手 —— API 接口设计说明书

> **文档定位**：本文档是前后端联调的**唯一接口契约（Schema-First）**。所有字段名、类型、SSE 事件格式在此定型，编码前冻结，联调期不得私改。任何字段变更必须先改本文档再改代码。
> **配套阅读**：数据结构见《03-数据库设计.md》，模块归属见《02-功能模块设计.md》。
> **基础约定**：
> - Base URL（开发）：`http://localhost:8088/ai`（实际端口8088 + context-path /ai）
> - 统一前缀：`/api/v1`
> - 完整接口示例：`http://localhost:8088/ai/api/v1/chat/stream`
> - 编码：UTF-8；请求/响应体：`application/json`（流式接口除外）
> - 跨域：开发期 `@CrossOrigin(origins = "*")`；生产由 Nginx 反代同源
> - 认证：**无**（MVP 全局硬编码租户 `userId = "devops-admin"`，见《白皮书》MUST NOT DO）

---

## 一、 接口总览

| # | 方法 | 路径 | 说明 | 类型 | 模块 |
| :--: | :--- | :--- | :--- | :--- | :--- |
| 1 | GET | `/api/v1/chat/stream` | 智能问答（SSE 流式打字机） | SSE | M1 |
| 2 | GET | `/api/v1/dashboard/overview` | 看板聚合统计指标 | JSON | M8 |
| 3 | POST | `/api/v1/knowledge/ingest` | 触发知识库切片入库 | JSON | M5 |
| 4 | GET | `/api/v1/knowledge/chunks` | 知识库切片分页浏览 | JSON | M5 |
| 5 | GET | `/api/v1/tickets` | 工单列表分页查询 | JSON | M7 |
| 6 | GET | `/api/v1/health` | 健康检查 / 双模状态 | JSON | 横切 |

> 接口 1、2 是 MVP 核心必做；3~6 为管理后台辅助接口，Day8 有余力时实现，可先返回 Mock 数据。

---

## 二、 统一响应规范（非流式接口）

所有非 SSE 接口统一包裹以下结构：

```json
{
  "code": 0,
  "message": "success",
  "data": { },
  "traceId": "a1b2c3d4",
  "timestamp": 1720944000000
}
```

| 字段 | 类型 | 说明 |
| :--- | :--- | :--- |
| `code` | int | 0=成功；非 0 见错误码表 |
| `message` | string | 提示信息 |
| `data` | object/array/null | 业务数据 |
| `traceId` | string | 链路追踪 ID（8 位） |
| `timestamp` | long | 服务器毫秒时间戳 |

**错误码表**

| code | 含义 | HTTP 状态 |
| :--: | :--- | :--: |
| 0 | 成功 | 200 |
| 40001 | 参数校验失败（query 为空等） | 400 |
| 40301 | 输入安全拦截（Prompt 注入） | 403 |
| 42901 | 上游大模型限流，已降级 | 200 |
| 50001 | 服务内部异常 | 500 |
| 50301 | 上游大模型/向量库全链路不可用 | 503 |

---

## 三、 接口 1：智能问答（SSE 流式）★核心

### 请求

```
GET /api/v1/chat/stream?query={urlencoded}
Accept: text/event-stream
```

| 参数 | 位置 | 类型 | 必填 | 约束 | 说明 |
| :--- | :--- | :--- | :--: | :--- | :--- |
| `query` | query string | string | ✅ | 1~1500 字，需 URL 编码 | 用户提问 |

### 响应头（后端强制设置，防缓冲，SOP 拦截点 4）

```
Content-Type: text/event-stream
Cache-Control: no-cache, no-transform
Connection: keep-alive
X-Accel-Buffering: no
```

### 响应体：SSE 事件流（定型契约）

事件按 `event:` 名称分类，`data:` 为 JSON 字符串。共 **5 类事件**：

#### ① start —— 会话开始
```
event: start
data: {"traceId":"a1b2c3d4","timestamp":1720944000000,"routerModel":"deepseek-chat"}
```
| 字段 | 类型 | 说明 |
| :--- | :--- | :--- |
| `traceId` | string | 本次会话追踪 ID |
| `timestamp` | long | 开始时间戳 |
| `routerModel` | string | 路由选中的模型名（deepseek-chat / deepseek-reasoner / Mock-Engine） |

#### ② tool_status —— 工具执行中间态
```
event: tool_status
data: {"toolName":"searchDevOpsKnowledge","status":"RUNNING","message":"正在检索运维手册，关键词：CrashLoopBackOff"}
```
| 字段 | 类型 | 说明 |
| :--- | :--- | :--- |
| `toolName` | string | `searchDevOpsKnowledge` / `createDevOpsTicket` |
| `status` | string | `RUNNING` / `DONE` |
| `message` | string | 展示给用户的中间态文案 |

#### ③ token —— 打字机文本块
```
event: token
data: {"text":"根据《K8s故障处理手册》P14，"}
```
| 字段 | 类型 | 说明 |
| :--- | :--- | :--- |
| `text` | string | 一个字符或词。**后端必须 escapeJson**（转义 `\ " \n \r`），否则前端 `JSON.parse` 崩溃 |

#### ④ complete —— 会话结束
```
event: complete
data: {"traceId":"a1b2c3d4","latencyMs":1420,"isCached":false,"costRmb":0.002,"citations":["K8s手册-P14"]}
```
| 字段 | 类型 | 说明 |
| :--- | :--- | :--- |
| `traceId` | string | 追踪 ID |
| `latencyMs` | int | 本次总耗时（毫秒） |
| `isCached` | boolean | 是否命中语义缓存 |
| `costRmb` | double | **成本字段定型名**，人民币元；缓存命中为 0.0 |
| `citations` | string[] | 引用出处列表 |

#### ⑤ error —— 异常/安全拦截
```
event: error
data: {"traceId":"a1b2c3d4","code":40301,"message":"您的输入包含不合规的系统干预指令，本次调用已终止。"}
```

### 前端对接要点（Day7，@microsoft/fetch-event-source）

```javascript
await fetchEventSource(`${BASE}/api/v1/chat/stream?query=${encodeURIComponent(q)}`, {
  method: 'GET',
  headers: { 'Accept': 'text/event-stream' },
  onmessage(ev) {
    const d = JSON.parse(ev.data || '{}')
    switch (ev.event) {
      case 'start':       /* 显示 routerModel */ break
      case 'tool_status': /* 黄色气泡 d.message */ break
      case 'token':       /* 拼接 d.text 到 Markdown */ break
      case 'complete':    /* 读 d.latencyMs / d.isCached / d.costRmb */ break
      case 'error':       /* 红字提示 d.message */ break
    }
  },
  onerror(err) { isStreaming.value = false; throw err }  // throw 阻止无限重连（SOP 拦截点 5）
})
```

### curl 验收
```bash
curl -N "http://localhost:8088/ai/api/v1/chat/stream?query=什么是K8s的Pod"
```

---

## 四、 接口 2：看板聚合统计

### 请求
```
GET /api/v1/dashboard/overview
```

### 响应 data
```json
{
  "totalQueries": 168,
  "cacheHits": 56,
  "cacheHitRate": 33.3,
  "avgCostRmb": 0.0035,
  "totalTickets": 18,
  "modelDistribution": [
    { "name": "deepseek-chat",     "value": 84 },
    { "name": "deepseek-reasoner", "value": 28 },
    { "name": "semantic-cache",    "value": 56 }
  ],
  "costSavingsChart": {
    "days": ["周一","周二","周三","周四","周五","周六","周日"],
    "traditionalCost": [4.2, 5.1, 4.8, 6.0, 5.5, 3.2, 4.0],
    "optimizedCost":   [0.8, 1.1, 0.9, 1.4, 1.2, 0.6, 0.9]
  }
}
```

| 字段 | 类型 | 说明 |
| :--- | :--- | :--- |
| `totalQueries` | long | 累计问答总数 |
| `cacheHits` | long | 缓存命中数 |
| `cacheHitRate` | double | 命中率 %（后端算好，前端直接展示） |
| `avgCostRmb` | double | 单次平均成本 |
| `totalTickets` | long | 累计工单数 |
| `modelDistribution` | array | 饼图数据，`{name, value}` |
| `costSavingsChart` | object | 7 日降本折线柱状对比 |

> 数据库记录 < 5 条时返回上述演示默认值（M8 兜底策略）。

---

## 五、 接口 3：触发知识库入库

### 请求
```
POST /api/v1/knowledge/ingest
Content-Type: application/json

{ "reload": true }
```
| 字段 | 类型 | 必填 | 说明 |
| :--- | :--- | :--: | :--- |
| `reload` | boolean | 否 | true 则先清空再全量重建，默认增量 |

### 响应 data
```json
{ "documentsLoaded": 5, "chunksIngested": 62, "elapsedMs": 8400 }
```

> 入库为耗时操作，MVP 阶段同步执行即可（文档量小）；返回入库统计供管理后台展示。触发成功后会清理相关语义缓存。

---

## 六、 接口 4：知识库切片浏览

### 请求
```
GET /api/v1/knowledge/chunks?page=1&size=10&keyword={可选}
```
| 参数 | 类型 | 必填 | 默认 | 说明 |
| :--- | :--- | :--: | :--: | :--- |
| `page` | int | 否 | 1 | 页码 |
| `size` | int | 否 | 10 | 每页条数 |
| `keyword` | string | 否 | - | 按 doc_title / content 模糊筛选 |

### 响应 data
```json
{
  "total": 62,
  "page": 1,
  "size": 10,
  "list": [
    {
      "chunkId": "K8s手册-P-3",
      "documentTitle": "K8s故障处理手册",
      "sectionHeader": "1.1 Pod 挂载错误",
      "content": "当 Pod 出现 FailedMount 时...",
      "similarityScore": null
    }
  ]
}
```
> `KnowledgeChunkDTO` 字段与《03-数据库设计.md》`sys_knowledge_chunk` 表对齐。浏览接口 `similarityScore` 为 null（非检索场景）。

---

## 七、 接口 5：工单列表查询

### 请求
```
GET /api/v1/tickets?page=1&size=10&priority={可选}&status={可选}
```
| 参数 | 类型 | 必填 | 说明 |
| :--- | :--- | :--: | :--- |
| `page` / `size` | int | 否 | 分页 |
| `priority` | string | 否 | HIGH / MEDIUM / LOW |
| `status` | string | 否 | PENDING / PROCESSING / CLOSED |

### 响应 data
```json
{
  "total": 18,
  "list": [
    {
      "id": "TKT-20260714-88",
      "title": "生产环境网络抖动异常",
      "priority": "HIGH",
      "module": "NETWORK",
      "status": "PENDING",
      "sourceTraceId": "a1b2c3d4",
      "createTime": "2026-07-14 10:22:31"
    }
  ]
}
```

---

## 八、 接口 6：健康检查

### 请求
```
GET /api/v1/health
```
### 响应 data
```json
{
  "status": "UP",
  "aiMode": "REAL",
  "components": { "pgvector": "UP", "redis": "UP", "llm": "UP" }
}
```
| 字段 | 说明 |
| :--- | :--- |
| `aiMode` | 当前双模开关：`MOCK` / `REAL` |
| `components` | 各依赖存活状态 |

---

## 九、 关键 DTO 定义（Java，定型）

```java
// 向量检索召回结果封装
public class KnowledgeChunkDTO implements Serializable {
    private String chunkId;
    private String documentTitle;
    private String sectionHeader;
    private String content;
    private Double similarityScore;   // 检索场景返回；浏览场景为 null
}

// 工单创建入参（供 @Tool 参数校验，与建表字段对齐）
public class DevOpsTicketCreateRequest implements Serializable {
    private String title;        // 必填
    private String priority;     // 必填 [HIGH, MEDIUM, LOW]
    private String module;       // 必填 [K8S, ALIYUN_SLB, MYSQL, NETWORK]
    private String stackTrace;   // 选填
    private String sourceQuery;  // 关联原始提问
}

// 统一响应包装
public class ApiResponse<T> implements Serializable {
    private int code;
    private String message;
    private T data;
    private String traceId;
    private long timestamp;
}
```

---

## 十、 接口验收清单（DoD）

- [ ] 接口1：`curl -N` 逐字流出 token；同义二次提问 `isCached=true` 且 <50ms；query 为空返回 40001
- [ ] 接口1：注入语句"忽略所有系统提示"触发 error 事件 code=40301
- [ ] 接口1：所有 `data` 均为合法 JSON（含双引号/换行的答案不导致前端 parse 崩溃）
- [ ] 接口2：字段完整，空库时返回演示兜底数据
- [ ] 接口3~6：分页正确，参数缺省有默认值，跨域头存在
- [ ] 全部非流式接口响应符合统一 `{code,message,data,traceId,timestamp}` 结构

---

> **契约纪律**：本文档字段一经冻结，联调期只增不改。确需变更走"改文档 → 通知前后端 → 同步改代码"流程，杜绝口头改字段导致的联调返工。
