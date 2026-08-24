# AGENTS.md — OpsBrain AI 项目开发约定

> 本文件是本仓库的**硬约束清单**，人与 AI 助手共同遵守。
> 只记录「本项目独有、违反就会出问题」的规则；通用编程常识不写在这里。
> 决策背景见 `docs/09-decisions/`，进度见 `docs/06-implementation-progress/`。
>
> 前端另有专属规范：`devops-platform-frontend/AGENTS.md`。

---

## 一、项目概览

OpsBrain AI（智维大脑）是企业级智能 DevOps 知识库与工单自动化 Agent 平台：
运维人员用自然语言提问 → RAG 检索企业知识库 → LangChain4j Agent 调度工具 → 秒级给出方案并自动开工单。

当前处于 **L1（被动问答）** 阶段，规划演进到 L5（全自动自愈）。

### 技术栈

| 层 | 技术 |
| :-- | :-- |
| 后端 | Java 21、Spring Boot 3.5.6、Spring MVC（SseEmitter）、Spring Data JPA + JdbcTemplate |
| AI | LangChain4j 1.1.0（BOM 统一版本）、阿里云百炼 OpenAI 兼容协议 |
| 存储 | PostgreSQL + **pgvector**（向量）、Redis 7（语义缓存/会话）、MinIO（附件） |
| 鉴权 | Sa-Token（header `satoken`） |
| 前端 | Vue 3.5、TypeScript、Vite 8、Element Plus、Pinia、TanStack Vue Query、ECharts |
| 构建 | Maven（`./mvnw`）、npm |

### 后端分层（六层单向依赖，不得反向）

```
controller/     HTTP 入口、DTO、参数校验、SSE 端点        → 只能调 application
  ├── config/       WebConfig（CORS + Sa-Token 拦截器）
  └── dto/          出参 DTO
application/    用例编排、事务边界、Agent 调度            → 只能调 domain + infrastructure
  ├── router/       DevOpsAgentEngine、DevOpsIntentRouter（大小模型分流）
  ├── runtime/      ToolRuntimeManager、ApprovalOrchestrator、各类 Scheduler
  ├── memory/       AgentMemoryManager
  └── context/      ContextBudgetManager
domain/         业务实体与领域服务                        → 不依赖 controller/application
  ├── rag/          切片、索引、混合检索、知识库治理
  ├── tools/        Agent 白名单工具、参数校验、风险等级
  ├── biz/          工单、团队成员、AI 分析、复盘
  ├── alert/        告警接入与推送
  ├── approval/     审批
  └── auth/         用户与权限
infrastructure/ 外部依赖适配                              → 不 import application/domain 的用例类
  ├── AiModelConfig / VectorStoreConfig / Mock*Model
  ├── persistence/  JPA 实体与 Repository 实现
  ├── cache/        SemanticCacheService、HotMemoryStore
  ├── storage/      MinIO
  └── websocket/    告警 WS 推送
common/         跨层共享：ApiResponse、异常、Guard、TraceContext
```

**依赖方向铁律**：`controller → application → domain ← infrastructure`。
`infrastructure` 可以实现 `domain` 定义的接口，但**不得 import `application` 包下任何类**。
`domain` **不得 import Spring MVC / HttpServletRequest / SseEmitter**。

---

## 二、常用命令

```bash
# 后端
./mvnw -B -ntp clean verify        # 编译 + 测试（提交前必跑）
./mvnw -B -ntp spring-boot:run     # 本地启动（默认 dev profile，AI_MODE=MOCK）

# 中间件（pgvector / redis / minio / prometheus / alertmanager / adminer）
docker compose -f docker-compose.dev.yml up -d

# 前端（在 devops-platform-frontend/ 下）
npm ci
npm run dev                        # 5173，已代理 /ai → localhost:8088（含 WS）
npm run verify                     # typecheck + lint + test（提交前必跑）
npm run knip                       # 死代码/死依赖检测

# 开工自检
./SOP_PreFlight_Check.sh
```

---

## 三、后端约定

### 3.1 统一响应契约（已冻结，不得擅改）

所有**非 SSE** 接口必须返回 `common/dto/ApiResponse<T>`：
`{ code, message, data, traceId, timestamp }`，`code == 0` 表示成功。

- 新增业务错误码时，**必须**同步更新前端 `src/constants/bizCode.ts` 与 `utils/http.ts` 的 `toFriendlyError`，
  否则前端只会显示一句无意义的兜底文案。已存在的码：`40009` 乐观锁冲突、`40021` 内容重复、
  `40101` 未登录、`40103` 权限不足。
- SSE 接口（`/api/v1/chat/stream`）**不用** `ApiResponse`；异常必须转成 `error` 事件下发，
  **不得让异常冒泡到 `GlobalExceptionHandler`** —— SSE 响应头已提交，此时再写 JSON 只会让前端收到半截流。

### 3.2 traceId 与日志

- **禁止**在任何地方自行生成 traceId。唯一来源是 `common/context/TraceContext`，
  由请求入口 Filter 写入 MDC，`ApiResponse` 与日志都从那里读。
  > 历史坑：`ApiResponse.generateTraceId()` 曾用 `System.nanoTime()` 每次调用现算，
  > 导致同一请求的响应体、错误响应、日志三处 traceId 互不相同，traceId 完全无法用于排障。
- **凡是切线程的地方（`@Async`、`SseEmitter` 回调、`CompletableFuture`、各类 `Scheduler`）
  必须显式传递 MDC**（用 `TaskDecorator` 或手工 `MDC.setContextMap`），否则异步日志丢 traceId。
- 日志用 SLF4J，禁止 `System.out.println`。异常日志必须带上下文（工单号/文档 ID/会话 ID），
  不要只打 `e.getMessage()`。

### 3.3 向量维度铁律

`devops.ai.vector.dimension`（当前 1536）是**全链路唯一真相**，同时约束三处：

1. `init.sql` 中 `VECTOR(n)` 的列定义；
2. `AiModelConfig` 传给 Embedding API 的 `dimensions`；
3. `VectorStoreConfig` 建表/校验时使用的维度。

**换 Embedding 模型必须同时改这三处并重建向量列**，否则写库那一刻才会炸（列类型不匹配），
而且已入库的旧向量与新向量不可比，检索会静默返回垃圾结果。

### 3.4 Agent 工具（`domain/tools`）

- 工具只能通过**白名单**注册，新增工具必须声明 `ToolRiskLevel`。
- **高风险工具（会改变线上状态的）一律走 `ApprovalOrchestrator` 人机协同，禁止直接执行。**
  这是 L3/L4 自愈的安全底线，任何"临时先跑通"的绕过都不允许合入。
- 工具参数必须过 `ToolParameterValidator`。LLM 生成的参数是**不可信输入**，
  与用户输入同等对待——过 `SecurityInputGuard` / `PromptInjectionGuard`。
- 工具失败必须归类到 `ToolFailureType`，由它决定是否重试；
  **禁止在调用点用「判断异常消息字符串」的方式决定重试**。

### 3.5 数据库

- 主库是 **PostgreSQL + pgvector，不需要兼容其他数据库**。可以放心使用 PG 专有能力
  （`tsvector`、`JSONB`、`ON CONFLICT`、数组类型）。
- Schema 变更：在 `sql/` 下新增 `migration_vNN_描述.sql`，**编号严格递增，禁止修改已提交的迁移文件**。
  同时必须更新 `sql/init.sql`（全新环境的一次性建库脚本），两者不同步会导致新环境与老环境表结构漂移。
- 涉及并发更新的实体（工单状态、知识文档）必须用**乐观锁 `@Version`**，
  冲突时抛 `OptimisticLockException` → 业务码 `40009`，由前端提示用户刷新后重试。
  **禁止用「先查后改」的方式规避乐观锁。**
- 分页查询一律走服务端分页，**禁止 `findAll()` 后在内存里 filter/slice**。
- **凡是在一个方法内写入两张及以上表，必须加 `@Transactional(rollbackFor = Exception.class)`。**
  漏加不会报错，只会在失败时留下孤儿数据（如「工单已删但回复/标签仍在」），
  且用户看到的是「操作成功」。
  - 注意 Spring AOP **自调用失效**：同类内部方法调用不走代理，
    被调方法上的 `@Transactional` 不生效。内部辅助方法不要单独标注，让它继承调用方事务。
  - **对象存储（MinIO）不在事务内**，无法回滚。涉及「删库 + 删对象」时必须
    **先删库后删对象**：删库失败可回滚（只多占存储），反序会留下
    「记录在但文件没了」的死链。
- **状态字段的变更必须走状态机校验**，只校验「值是否合法枚举」是不够的。
  参考 `TicketEnums.Status.canTransition`：终态不可逆、同态幂等放行。
  前端需用 `nextStatuses` 置灰非法选项——防呆优于事后报错。

### 3.6 安全

- **免鉴权端点是高危面**。当前白名单只有三个：`/api/v1/auth/**`、`/api/v1/health/**`、
  `/api/v1/alerts/webhook`。**新增任何免鉴权端点都必须在 PR 说明里论证，并配套限流。**
- CORS：生产 profile **不得**使用 `allowedOriginPatterns("*") + allowCredentials(true)`，
  必须列举具体域名。dev profile 可放宽。
- 用户输入与 LLM 输出**都要**过 `PromptInjectionGuard` / `SecurityInputGuard`。
  LLM 返回的 Markdown 进前端前需确保前端会做 DOMPurify 净化（见前端 AGENTS.md）。
- 附件上传必须过 `AttachmentSecurityGuard`；`spring.servlet.multipart.max-file-size`
  与 `devops.storage.attachment.max-file-size` 必须保持一致（取小者生效，不一致会在 Tomcat 层直接拒绝，
  业务代码根本收不到请求，报错信息会很迷惑）。
- 禁止把密钥写进代码或 `application*.yml`，一律走环境变量（见 `.env.example`）。
  日志中禁止打印 API Key、token、附件直链。`logResponses(true)` 只允许在本地临时开启。

### 3.7 配置

- 新增配置项一律走 `devops.*` 命名空间，并在 `application.yml` 里写默认值与**中文注释说明这个值为什么是这个数**。
- 阈值类参数（相似度阈值、幻觉熔断分、检索权重）改动必须在 PR 说明里给出依据（评测集结果或线上数据），
  **不接受"感觉这样好一点"**。
- `devops.ai.mode` 有 `MOCK` / `REAL` 两态。**任何新增的 AI 相关 Bean 都必须同时提供 Mock 实现**，
  否则 MOCK 模式启动会因缺 Bean 直接失败，破坏"开发期不烧额度"的前提。

### 3.8 后端测试

测试必须保护**真实行为、API 契约、数据一致性或回归路径**。

- **禁止**只为提升覆盖率、只证明代码能跑、或锁死内部实现细节的测试。
- **禁止**靠 `Thread.sleep`、随机输入、大循环、计时比较来"测试"。用确定性输入与精确断言。
- **禁止** mock 被测类自身，也不要把生产逻辑抄一遍到测试里算期望值。
- 优先写表驱动测试，输入与期望输出都写明。
- 需要数据库/Redis/上下文状态时，在测试 fixture 里显式初始化，不依赖执行顺序。
- 修 Bug **必须先写一个能稳定复现的失败用例**，再改代码让它变绿。
- 涉及 Controller 契约的改动，要有 `@WebMvcTest` 覆盖状态码与响应体结构。
- 清理测试时先合并重复场景；若旧测试间接覆盖了真实契约，用更小更直接的行为测试替换，不要直接删掉。

---

## 四、前端约定（摘要）

完整规范见 `devops-platform-frontend/AGENTS.md`。此处只列跨端相关的：

- 前端**禁止硬编码 `localhost` 或后端端口**。开发期走 Vite proxy（`/ai` → `:8088`，含 WebSocket），
  代码里一律用相对路径。
- 服务端数据一律走 TanStack Query，`queryKey` **必须**取自 `src/config/queryKeys.ts`，
  禁止在组件里手写字符串数组——写操作后的 `invalidateQueries` key 对不上会静默失效不了，
  表现为"改完数据列表没更新"，极难排查。
- 后端新增/变更业务码时，前端 `toFriendlyError` 必须同步。

---

## 五、协作与提交

- **提交前必须本地跑通**：后端 `./mvnw -B -ntp verify`，前端 `npm run verify`。
  未看到通过结果不得声称完成。
- 提交信息用 `type(scope): 描述`（`feat` / `fix` / `refactor` / `docs` / `test` / `chore`），
  描述用中文，说清"改了什么"和"为什么这样改有效"。
- **一个 PR 只做一件事**，不夹带无关改动。
- 改动涉及本文件所述约束时，**同步更新本文件**。
- 重大技术决策写入 `docs/09-decisions/`（一个决策一个 ADR 文件：状态 / 背景 / 决策 / 后果），
  不要再往 `CLAUDE.md` 里堆。
- AI 生成的代码**必须经人工阅读与本地验证**后才能提交；不接受未经验证的批量产出。

---

## 六、当前已知技术债（改到附近时顺手还）

| 项 | 位置 | 约束 |
| :-- | :-- | :-- |
| 超大 SFC | `TicketDetail.vue`(2655) `TicketList.vue`(2552) `KnowledgeEditor.vue`(1970) 等 | 修改这些文件时**不得让行数净增长**，至少拆出一个子组件或 composable |
| 后端无 Controller 测试 | `src/test/` | 新增/修改 Controller 必须补 `@WebMvcTest` |
| 无 CI | 仓库根 | 见 `docs/08-benchmark/01-new-api对标分析与借鉴优化建议.md` §5.2 |
| 无 Dockerfile | 仓库根 | 同上 §5.3 |
| traceId 未接 MDC | `TraceContext` / `ApiResponse` | 见 §3.2，优先修 |
| 无 API 限流 | 全局 | `/chat/stream` 与 `/alerts/webhook` 优先 |
| 文档写 deepseek 实际用 qwen | README / CLAUDE.md / `AiModelConfig` | 改文档或做 Provider 抽象，二选一，不要放着不管 |

---

## 更新日志

- **2026-08-24**：初版。从 3206 行的 `CLAUDE.md` 中提炼硬约束，参考 new-api 的 AGENTS.md 组织方式。

---

## 附录：缺陷登记与修复进度（2026-08-24）

详情见 `docs/08-benchmark/02-技术债审查与推进路线规划.md`。

### 已修复（阶段 A/B）

| 级别 | 缺陷 | 修复方式 |
| :-- | :-- | :-- |
| P0 | `InMemoryChatMemoryStore` 无驱逐路径，持续泄漏至 OOM | 新增 `TtlChatMemoryStore`（TTL + LRU 兜底） |
| P0 | SSE 60s 超时 < reasoner 模型 120s，复杂推理必然超时 | 超时配置化并对齐层级，加 15s 心跳帧 |
| P0 | `CostQuotaManager` 单机内存态，重启清零、多实例失效 | 迁至 Redis（日期分区 key + TTL 自然日重置） |
| P0 | traceId 每次现算、未接 MDC，日志无法关联 | `TraceContext` 改 MDC 承载 + `TraceIdFilter` |
| P1 | `mvnw`/`mvnw.cmd` 未提交，仓库无法开箱构建 | 补齐 3.3.4 wrapper + `.gitattributes` |
| P1 | 告警 webhook 免鉴权且无限流 | `WebhookGuard`（共享密钥 + 滑动窗口限流） |
| P1 | 生产 CORS `*` + allowCredentials | 改 `CORS_ALLOWED_ORIGINS` 白名单 |
| P2 | 元→分用 `(long)(x*100)` 截断，成本系统性低估 | 改 `Math.round` |
| P2 | 5 处日志用 `{:.1%}` 格式符，SLF4J 不支持，告警从未生效 | 全部改为标准 `{}` 占位 |
| P1 | 知识库无可见性字段，检索层无权限过滤 | v24 迁移 + `KnowledgeScope` 贯穿检索 SQL |
| P1 | 语义缓存 key 不含权限维度，可跨用户泄漏 | 缓存键按权限域分区（`cacheScopeKey`） |
| P1 | `@Transactional` 写在 Controller 层 | 下沉至 `KnowledgeTagService` |
| P1 | `/chat/stream` 无限流（最贵端点，脚本可打爆额度） | 滑动窗口限流，按 userId 20 次/分钟 |
| P1 | 写操作无统一审计（L3/L4 合规前置） | v25 `sys_operation_audit` + 拦截器 |
| P2 | 错误码是散落的魔法数字，前后端各自硬编码 | `BizError` 枚举 + 前端 `bizCode.ts` + **契约测试交叉校验** |
| P2 | 前端无暗色/无主题能力，639 处硬编码色值 | 四轴令牌 + 桥接层（存量零改动获得暗色） |
| P0 | **工单域 6 张表写操作零事务**，deleteTicket 中途失败留孤儿数据 | 14 个多表写方法加 `@Transactional` |
| P1 | 工单状态机缺失，CLOSED 可回 PENDING、VOID 可复活 | `TicketEnums.Status.canTransition` + 前端置灰 + 契约测试 |
| P1 | 迁移漏执行会静默损坏功能（缺 visibility 列→检索全挂，却表现为「知识库暂不可用」） | `SchemaGuard` 启动期自检，生产可设 `SCHEMA_FAIL_FAST=true` |
| — | 无 CI / 无 Dockerfile | 见 `ci/README.md`、`Dockerfile`、`docker-compose.yml` |

### 待修复

| 级别 | 缺陷 | 位置 | 计划 |
| :-- | :-- | :-- | :-- |
| P2 | 6 处自建线程池散落各层，无界队列、无监控、无统一优雅停机 | 审查报告 §2 P2-2 | 阶段 C |
| P2 | 后端无 Controller 层测试，93 个端点无契约保护 | `src/test/` | 阶段 C |
| P2 | Controller 层 **78 处** `catch(Exception)` 样板，错误码映射不一致且直接下发 `e.getMessage()` | 全部 Controller | 见审查报告 §4.1 |
| P2 | **Bean Validation 零使用**（`@Valid` 0 处），校验全在 Service 手写（TicketService 20 处） | 全部 DTO | §4.2 |
| P2 | 前端 121 处裸 `ElMessage` 绕过 `notify` 冷却去重，批量操作会刷屏 | 前端 | §4.3 |
| P2 | `TicketFormDialog.vue`(1073行) 表单无分组、校验规则内联 | 前端 | §4.4 |
| P2 | AI 对话链路的知识检索恒为「仅 PUBLIC」：工具跑在模型回调线程，取不到 `AgentKnowledgeScopeHolder`。这是<b>刻意的保守失败</b>（宁可少给不可越权），但也意味着 ADMIN 在对话里同样查不到受限文档。需改为每请求构建 AiService 或用 LangChain4j 工具上下文透传 | `AgentKnowledgeScopeHolder` | 阶段 D |
| P2 | 文档权限变更后必须重建其切片，否则切片上的冗余 `visibility` 会滞后造成越权 | `KnowledgeDocService` | 阶段 C 收尾 |
| P2 | 前端 knip 存量：23 未用导出 + 53 未用类型 | 前端 | 阶段 D |
| P2 | 构建产物 `vendor` chunk 达 2.9MB，`manualChunks` 兜底分块未生效 | `vite.config.ts` | 阶段 D |
| P2 | 两套富文本编辑器并存（wangEditor + md-editor-v3） | `package.json` | 阶段 D |

### 🔴 头号阻塞：CI 未启用，2543 行后端代码从未编译

`ci/github-actions-ci.yml` 已就绪但因 GitHub App 权限限制无法由 AI 推送到
`.github/workflows/`。**启用只需 30 秒**：

```bash
git mv ci/github-actions-ci.yml .github/workflows/ci.yml && git commit && git push
```

在此之前不建议继续叠加新功能——40 个文件的改动一次性编译，错误会更难定位。
详见 `docs/08-benchmark/05-项目阶段评估与风险清单.md`。

### ⚠️ 本轮后端改动尚未编译验证

开发沙箱内无 JDK 且 Maven Central 不可达，后端改动仅经过静态校验
（结构、导入、依赖签名对照上游源码）与算法等价验证。
**首次 CI 运行是它们的第一次真实编译**，需留意编译错误。
前端改动已在本地实跑验证（typecheck / lint / 563 tests / build 全通过）。
