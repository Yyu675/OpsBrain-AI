# OpsBrain AI · 任务进度台账

> **这是什么**：正在做和刚做完的事都记在这里。**唯一的实时进度真相源**。
> **最后更新**：2026-08-28
> **配套文档**：`README.md`（只登已验收的能力）｜`AGENTS.md`（硬约束）｜`docs/08-benchmark/`（每轮审查报告）

---

## 零、这套机制怎么用（先读这段）

### 三份文档的分工

| 文档 | 记什么 | 什么时候写 | 可以有未完成项吗 |
|---|---|---|---|
| **PROGRESS.md**（本文件） | 进行中 / 待办 / 刚完成待验收 | **每次动手前后都写** | ✅ 可以，这就是它的用途 |
| **README.md** | **已验收**的能力、真实可用的功能 | **只在验收确认后写** | ❌ 不可以，写进去就是承诺 |
| `docs/08-benchmark/NN-*.md` | 单轮工作的完整过程与结论 | 每轮收尾时 | ✅ 含「判定不做」的理由 |

### 任务状态流转

```
📋 待办  →  🔵 进行中  →  🟡 待验收  →  ✅ 已验收
                              ↓
                         ❌ 打回（写明原因，退回进行中）
```

**关键规则**：

1. **🟡 待验收 ≠ 完成**。代码写完、CI 绿、注入验证过，也只是「待验收」。
2. **只有你（用户）明确确认后**，任务才转 ✅，并**在那时才写进 README.md**。
3. 我不会自己把任务标成 ✅ —— 那等于自己给自己验收。
4. **「判定不做」也是一种完成**，记在本文件的「已决策不做」区，附理由，避免后人反复纠结。

### 为什么要这么设计

README 是对外承诺。当前 README 就是反面教材——它写着「L1-L5 全自动自愈」
「四大千亿级商业化板块」，而 98 号实测 **L4 执行器为零**。
**承诺与能力脱节，第一次演示就会被拆穿。**

把「在做的」和「做成的」分开记，就是为了让 README 里的每一句话都有代码兜底。

---

## 一、当前路线（2026-08-28 确认）

**产品定位**（采纳 98 号方案 A + B）：

> 交付口径：**AI 运维知识中枢 + 智能工单**（L1–L3 真实可用）
> 演进口径：**Agentic AIOps 治理平台**（治理层已就绪，对接客户既有自动化工具）
> **L4 自愈明确标注为 roadmap，不作为现有能力宣传**

**技术路线**：先补 P0 四项（上生产门槛），前端选型优化优先决策。

### 执行索引（2026-09-07 起）

**《OpsBrain_AI_生产级落地路线图.md》（仓库根）为任务总索引**：阶段 0~6 的任务拆解、
验收标准、决策台账（D-01~D-07）与报告编号分配（100~114）以它为准。
本台账负责状态流转（📋→🔵→🟡→✅），路线图负责「做什么、怎么验收」。

> 路线图 §0.3 铁律沿用：🟡 待验收 ≠ 完成；报告 `docs/08-benchmark/NN` 从 100 起按阶段分配，
> 非路线图任务（如 T11）不占编号——沿用 T4~T10 前例，只在本台账登记。

### 已确认的决策（2026-08-28）

| 决策点 | 结论 | 依据 |
|---|---|---|
| 前端富文本编辑器 | ~~方案 A：移除 wangEditor~~ → **方案 A′：保留双模式，修正存储格式统一为 Markdown** | 99 号 v2；**决策已重做**，见下 |
| 知识库编辑者画像 | ~~全是技术人员~~ → **技术与非技术混合，非技术人员也要写** | 用户重新确认；PRD §三 明确列了「业务方/报障人」「合规审计员」两类非技术角色 |
| 知识库写端点权限 | **是缺陷，应加角色限制**（admin/operator 可写，viewer 只读） | 用户确认；7 个写端点（含物理删除）当前无任何角色校验 |
| 语法高亮语言包裁剪 | **立即开做**（与编辑器方向无关，选任何方案都要做） | 99 号；省约 550 KB，零业务风险 |
| 后端 P0 推进顺序 | **先做 springdoc-openapi**，再 Flyway / Testcontainers / Resilience4j | 用户选择；可带动前端 TS 类型自动生成 |

### 已确认的决策（2026-09-07）

| 决策点 | 结论 | 依据 |
|---|---|---|
| 推进依据切换 | **以《OpsBrain_AI_生产级落地路线图.md》为任务总索引**；其 §14 进度表登记进本台账 | 路线图（已入库）＋用户确认 |
| T11（P0-2b 告警/治理 record） | **收尾后转路线图阶段 0** | 用户选择；代码+测试已 CI 绿，只差注入-还原验证 |
| D-01 Flyway 基线方案 | **按路线图推荐采纳 B**（单基线） | 用户未表态时代理按文档推荐执行并告知可反悔；动手前发现路线图「25 个迁移文件」前提已失效（8-27 并入 init.sql），B 形态降为基线搬家+删代码侧双写 |
| ci.yml 收尾（psql 步骤→flyway validate） | **需用户配合** | 机器人无 workflows 权限；两选：Arena 重连 GitHub 或按报告 100 §五手工应用 |
| 0-2.6 Redis/MinIO 容器化 | **均不做**（S0-2） | Redis：lettuce 懒连接+链路不触碰+init 仅内存 Map；MinIO：链路不涉及。需要时按同基类补 |
| Testcontainers 容器模式与注入方式 | singleton + `@DynamicPropertySource`（不用 `@Testcontainers`/`@ServiceConnection`） | 每类一器拖垮 CI；显式三行连接信息胜过隐式自动装配（详见基类 javadoc 与报告 101 §四） |
| Resilience4j 0-3.5 范围（LLM 防护） | **embedding 限流落地；chat 流式 RateLimiter 与 TimeLimiter 不上** | 流式 TokenStream 在 Bean 层既不能 TimeLimit 也不宜 RateLimit 打断；端点级 HTTP 超时已存在（LlmEndpointSpec.timeout）。恢复条件：阶段 1 出现统一调用网关时收口 |
| Resilience4j 实例槽 logs/cmdb | **本轮不配** | 两个客户端尚不存在（S1-2/S1-3 随工具落地时接挂），凭空配槽只会产生无人消费的配置 |
| resilience4j 依赖版本 | **显式钉 starter+annotations 同 2.4.0** | BOM 3.5.6 不托管该构件系（CI 实证）；注解构件不被 starter 传递（CI 实证）；版本经 central 目录核对，冲突回退 2.3.0 |
| llm 限流模式（S0-3 校正） | **配速（60/s + 10s 等待上限）**，放弃零等待 | S0-4 实测：零等待把顺序批量摄取的突发拒绝，文档落 INDEX_FAILED，评测报「向量化失败」，未命中清单被污染——批量负载要配速不要拒绝（报告 102 §三）；交互单发嵌入典型等待 <1s，10s 是失控任务的最后防线 |
| MOCK 口径 RAG 覆盖层的判据 | **定为连通性 100% 红线**，语义覆盖率不在 MOCK 下声明 | mock 向量跨文本近似正交：默认 minScore 下≈0（假低）、minScore=0 下恒 100%（假高），两端都不是覆盖数字；语义判据归 EVAL_LLM 手动 job（报告 102 §一/§三） |
| PromQL 与变更评分的落位 | **模板进 yml 查询目录、评分分档不写连续函数** | label 约定因现场而异，硬编码=假验收；档位是运维心智，可读可测。服务名进 PromQL 前必过白名单（工具层是真正边界） |
| Spring Map 属性覆盖 | **@ConfigurationProperties Map 要么不换要么全换** | 实测：TestPropertySource 逐 key 部分覆盖触发整体替换语义，其余键消失（报告 103 §三）；逐 key 覆盖 Map 是伪功能 |
| 变更事件幂等键 | **UNIQUE(source, external_id)，external_id 可空（手工录入）** | CI 重发返 200 + deduplicated=true，流水线不该红；PG NULL 互不相等语义天然豁免手工行（V2 表注释可查） |
| P0-2b 剩余 Map 端点（工单余下 11 个 + 知识库余下） | **暂停挂账**：随阶段 0「前端类型手写」债项（路线图 §10.1）按需补改，不盲目全改 | 用户选择；路线图未给它排期，阶段 0 是主线 |
| 报告编号 100~114 | 归路线图阶段任务；非路线图任务不占编号 | 路线图 §11.2 |

---

## 二、🔵 进行中

| # | 任务 | 说明 | 开始 |
|---|---|---|---|
| T16 | **S1-1：指标取证工具**（路线图 §5.2，报告 103 **已产**） | 代码与测试完成；⚠️ CI 复验后**确认仍红**——真根因定为 Binder 绑定路径盲区（裸根键不落 Map），修复就绪：`templates:` 键层补齐 + 契约钉入注释 | 2026-09-07 |
| T17 | **S1-2：变更取证工具**（路线图 §5.3，报告 104 前半 **已产**；日志 S1-3 排下一轮） | 代码与测试完成；修复就绪随 T16 一并推送验绿 | 2026-09-07 |
| T18 | **S1-3：日志取证工具**（路线图 §5.4，报告 105 前半 **已产**） | 代码与测试完成；三件套 + 三态承载 + 注入留痕不阻断 + 预算两级收束 + 序列化断言退役 | 2026-09-07 |
| T19 | **S1-5 阶段 1：证据聚合器 + 落库 + 系统提示词**（路线图 §5.6，报告 105 后半 **已产**） | EvidenceAggregator（四态充分性 + C1/C2 确定性冲突）+ V3 表 + Repository + DevOpsAgentEngine 三态硬边界教学；阶段 2（拓扑）排下轮 | 2026-09-07 |
| **T20** | **S2-1：诊断编排器**（路线图 §6.1，报告 106 **已产**） | 骨架 + 触发钩子 + 回填工单全部落地：V4 表 + Orchestrator 异步池（4/8/100）+ AlertService 供应提交钩子 + 诊断回填工单 AI 分析区 + 12 个测试；实时推送（2-1.6）并入 S2-3 | 2026-09-07 |
| **T21** | **S2-2：根因假设与置信度**（路线图 §6.2，报告 107 **已产**） | 批次 A+B 完成：Hypothesis model + ConfidenceEngine + V5 表 + 双轨生成器 + 编排器接线 + 提示词假设契约 + 12 个测试；批次 C（LLM 合成版）排下一轮 | 2026-09-07 |
| **T22** | **S2-3：诊断报告与详情 API**（路线图 §6.3） | 批次 1+2 完成：回放 API + WS/钉钉推送 + 前端诊断页（三态徽章/置信度条/反馈三键）+ V6 迁移（假设反馈列 + 知识 boost 表）+ 检索器 boost 回流 hook + 8 个测试；批次 3（阶段 2 收口：报告 108 + README L3 更新 + 诊断样本入评测）排下一轮 | 2026-09-07 |
| **T23** | **S2-3 批 3：阶段 2 收口**（报告 108 **已产**） | 告警→根因完整闭环验收记录 + README L3 更新 + 诊断样本入评测 | 2026-09-08（补记） |
| **T24** | **S3-1：受控自愈执行器启幕**（路线图 §7，报告 109 **已产**） | V7 执行台账（快照/撤销凭据/幂等键）+ HealingOrchestrator 三路径（AUTO_EXECUTE/REQUIRES_APPROVAL/DENIED）+ 治理门 + 撤销 + 自愈中心两端；批 5 详页与回放路由并入 S3-5 | 2026-09-08（补记） |
| **T25** | **S3-1→S3-3 验收缺口补齐**（报告 110/111/112 **已产**） | 幂等闸（窗口判重+手工豁免）· 强契约 · agent 审计旁写；首批 fabric8 执行器（重启/扩缩容）；执行后验证链（验证器+心跳+自动回滚+升级工单） | 2026-09-08（补记） |
| **T26** | **S3-5：可观测可回放收口**（报告 113 **已产**） | V10 steps_json 步骤时间线（11+2 类节点）· 三条占位路由解封 · 详情页双载荷 · CI 双腿修红战役终局（报告 113 §五十层表） | 2026-09-08（补记） |
| **T27** | **S4-1：告警驱动策略引擎**（路线图 §十信任阶梯/L5 证据门序章，报告 114 **已产**） | `HealingAutoTrigger`：`sys_automation_policy` 终有消费者，告警→策略→治理门链路接通；dryRun 留痕第一级 · 冷却/日上限双保险 · stopOnMatch 防饿死 · ManagedExecutors 架构门禁合规；7 测试；CI 两轮修红（draft 15 参/线程池）后双腿绿（18ed3e8） | 2026-09-08 |
| **T28** | **S4-2：证据门计数器**（报告 115 **已产**） | 双计数器（演练命中场次×连续零误连胜）+ 连胜三态判定 + promotable/evidenceReady 服务端徽标 + 前端策略列表徽标格；阈值双 @Value；`PolicyEvidenceServiceTest` 10 例；本批独享一条尸检：多构造器 Bean 未标 @Autowired 致全 context 崩，修复后复验 | 2026-09-08 |
| **T29** | **S4-2b：证据门第三支柱**（报告 116 **已产**） | 审计完整度进 evidenceReady（近 50 条 steps_json/gate_decision 缺失一行即卡）+ promotable 解耦 + 阈值三派生字段下发 + 前端 title 富化；测试 +4、smoke 26/26、tsc 0 错；PRD L5 三支柱进度过半（零误✓ 审计✓ 观测挂账） | 2026-09-08 |
| **T30** | **真·阶段 4 起跑：S4-1 评测判据升级**（路线图 §8.1，报告 117 **已产**） | **编号勘误**：T27~T29 曾借号 S4-x，实为阶段 3 自愈信任阶梯延伸，自本批起按路线图重排（S4-1 判据升级→S4-2 校准→S4-3 CI 回归→S4-4 看板）。本批：EvalItem +expectedDocs/expectedRootCause（docTitle 为判据键）、RetrievalRankMetrics 纯计算+8 钉测、RAG 层接线（连通红线口径不变、注记子集出 Recall@1/@3/MRR、无注记留空拒伪造）、首组注记 5/65、校验器双档本地通；幻觉率/负例强化/扩集 150 挂账 EVAL_LLM 窗口 | 2026-09-08 |
| **T31** | **S4-3 前半：评测持久化与基线对比**（路线图 §8.3，报告 118 **已产**） | `EvalMetricsWriter` 分层合并落 target/eval-metrics.json（契约常驻必落/RAG 门内落/落盘失败只 WARN）+ `eval_compare.js`（±2pt 比率阈、leaked/blocked 反向零容忍、只比双侧共有指标、首跑 rc=0 拒假绿）；四路径本地实证；ci.yml 四触点继续挂账 workflows 权限 | 2026-09-08 |

---

## 三、🟡 待验收（代码已完成 + CI 绿 + 注入验证通过，等确认）

| # | 任务 | 产出 | 验证情况 | 完成于 |
|---|---|---|---|---|
| **T15** | **S0-4：评测跑通与基线记录**（路线图 §4.4） | `AgentEvaluationTest` 迁 `AbstractIntegrationTest` + 种库前置（ingestAllLocalDocuments）· **S0-3 校正**：llm 限流零等待 → 配速（60/s、10s 上限；摄取批量突发被拒是假阴性制造者）· `RateLimitedEmbeddingModel` javadoc 校订 · 三层口径基线 + 缺口分类 · 报告 `docs/08-benchmark/102`（含 eval/eval-llm 双 job diff） | 三程捕获轮全录：34115435905（注解 ~250 字节截断，得通道教训）→ 34115837596（56%「基线」实为限流拒绝伪造，主评测 ERROR 撞破）→ 34116296014（修复后连通性 100%）→ 定版 34116951105 绿。**判断**：契约层 100%/100% 真基线；EVAL_RAG 层定连通性红线；语义覆盖率 MOCK 不可度量，留空拒伪造，归 EVAL_LLM 手动 job 首跑回填。⚠️ eval job/artifact/README 评测行三项挂账（workflows 权限 + 验收纪律）；S0-4b/c 入挂起区 | 2026-09-07 |
| **T14** | **S0-3：Resilience4j 熔断降级**（路线图 §4.3） | `prometheus` 熔断实例（@CircuitBreaker×3 + 显式 fallback 语义：真失败透传、熔断打开译为 MetricsUnavailableException、未启用升为子类排出统计）· `RateLimitedEmbeddingModel`（llm 30/s 零等待，REAL/MOCK 同裹）· `MetricsIntegrationDisabledException`/`LlmRateLimitedException` · AGENTS 3.7.5 分工硬约束 · 8 测试（熔断四态+共享实例×5、限流×3，MockWebServer 计数证「不再打网络」） | CI 三程收敛后绿（主 34112089678 / 还原 34112704933）；**J2 探针单注解精确命中**：阈值 1% 注入→唯一红 belowFailureThresholdStaysClosedAndKeepsCalling。⚠️ 三处外部库实相与记忆不符全部经 CI 暴露并以源码核对修正（BOM 不托管 resilience4j、starter 不传注解构件、注解无 ignoreExceptions 属性+RL 类名 2.x 去后缀）；0-3.5 如实记缺口：chat 流式与 TimeLimiter 未上（恢复条件=阶段 1 网关层），logs/cmdb 实例槽随 S1 客户端落地 | 2026-09-07 |
| **T13** | **S0-2：Testcontainers 集成测试**（路线图 §4.2） | `AbstractIntegrationTest`（singleton pgvector/pgvector:pg16 + `@DynamicPropertySource`）· `HybridRetrieverIntegrationTest` 迁入容器 · `FlywayMigrationIntegrationTest`（27 表 + history 直证）· **修复真缺陷**：`MockEmbeddingModel` 硬编码 1536 绕过配置 → 构造器注入 + `MockEmbeddingModelTest` · 报告 `docs/08-benchmark/101` | CI 绿（34105259493 首拉镜像、34106765448 复验）；**J1 探针三程**：①仅注入维度 512 竟绿——红线死在 MOCK 硬编码里；②修复+保留注入红在 INSERT 维度不匹配（34106451311）——红线接通；③还原即绿。验收 #1（docker-only 全通）如实记缺口：其余 13 个 @SpringBootTest 类未迁，登记 S0-2b | 2026-09-07 |
| **T12** | **S0-1：Flyway 迁移版本化**（路线图 §4.1，方案 B 变体落地） | `V1__baseline.sql`（init.sql 迁入，SQL 零改动）· flyway 依赖 + baseline-on-migrate + validate-on-migrate · 删除代码侧双写建表（2 个 SchemaInitializer + 2 个 ensureSchema）· `FlywayMigrationContractTest`（5 道闸）· compose/脚本/README/AGENTS §3.5 改写 · 报告 `docs/08-benchmark/100` | CI 绿（b240de4→34102402282）；**K1/K2 注入各命中预期**：K1 sql 目录 DDL→唯一一条注解精确点名契约测试；K2 坏 V2→全部上下文拒载，还原次轮直接转绿=PG 事务回滚无残留。⚠️ **验收 #3/#4 挂账**：机器人无 workflows 权限改不了 ci.yml，需用户重连 GitHub 或手工应用 ci 改动（报告 §五已备好文案） | 2026-09-07 |
| **T11** | **P0-2b 第三步（告警/治理模块）：9+1 个 Map 端点改 record** | `AlertDto.AlertPage`（新增）· `GovernanceViews`（13 个 record，新增）· 告警 service 拆 find/count · 治理 repo/service 一并强类型化 · `AlertDtoContractTest`（6 例）+ `GovernanceDtoContractTest`（17 例）· OpenAPI 可消费性断言 +3 例 | CI 绿（7d179c4）；**G-1/G-2/G-3 三项注入各命中预期用例**：整除截断→totalPagesRoundsUp+defaultPaging；丢 NON_NULL→denyOmitsConstraintFields；skipped 误标 matched→skippedIsNotUnmatched。首轮漏改 3 处 Map 桩被 CI 编译拦下已补（注解上限 3 条的教训应验） | 2026-09-07 |
| T1 | 进度台账机制落地 | `PROGRESS.md`（本文件） | 机制文档，无需 CI | 2026-08-28 |
| T2 | README 定位对齐 98 号结论 | `README.md` 重写 | 移除 L4/L5 与「千亿商业化」等无代码兜底的表述 | 2026-08-28 |
| T3 | 前端技术栈优化方案 **v2** | `docs/08-benchmark/99-前端技术栈优化方案.md` | v1 假设不成立已推翻重写；含两个新发现的真缺陷 | 2026-08-28 |
| **T10** | **P0-2 收尾：OpenAPI 契约可消费性验证** | `OpenApiSchemaIntegrationTest`（6 例）· 导出 `target/openapi.json` | 验证前两轮 DTO 在 OpenAPI 里真的产出了具体字段，而非 additionalProperties | 2026-08-31 |
| **T9** | **P0-2 第二步（知识库模块）：`list` 改用 record** | `KnowledgeDocDto.DocPage`（新增）· `KnowledgeDocController` 接入 · `KnowledgeDtoContractTest`（7 例） | ECJ 0 语法错误；额外锁住「两套分页命名不得顺手统一」 | 2026-08-31 |
| **T8** | **P0-2 第二步（工单模块）：`getTickets` 改用 record** | `TicketDto.TicketPage`（新增）· `TicketController` 接入 · `TicketDtoContractTest`（6 例） | ECJ 0 语法错误；既有 WebTest 已断言 tickets/total/page 三个键，序列化有保障 | 2026-08-31 |
| **T7** | **P0-2 第一步：接入 springdoc-openapi** | `pom.xml`（2.8.15）· `application.yml`/`-prod.yml` 配置 · `OpenApiExposureContractTest`（4 例） | 版本经官方版本表核对；prod 整体关闭并加契约锁死；解析器双向自验 | 2026-08-31 |
| **T6** | **F-6 入库内容统一为 Markdown** | `editorContent.ts` 新增 `toMarkdownForStorage` · `KnowledgeEditor.vue` `handleSave` 接入 · `markdownStorage.test.ts`（10 例） | tsc/eslint/1794 例全绿；C1/C2 两项注入命中互补的两组（4 例 vs 3 例，无交集） | 2026-08-31 |
| **T5** | **F-5 知识库写权限分级守卫** | `KnowledgeWriteGuard.java`（新增）· 3 个控制器 15 处 · `GlobalExceptionHandler` 分支 · 2 个测试类（12+2 例） | 15/15 端点覆盖；**B1/B2 两项注入均命中**（B2 首次未命中，查出是窗口过宽的假测试，已修正后复验命中） | 2026-08-31 |
| **T4** | **F-2 语法高亮语言包裁剪** | `src/vendor/codemirror-language-data-slim.ts`（新增）· `vite.config.ts`（alias）· `codemirrorLanguageSlim.test.ts`（28 例） | 产物 **4890→4324 KB**、文件 183→86、碎片 148→59；tsc/eslint/1784 例全绿；A1/A2/A3 三项注入各命中互补断言 | 2026-08-31 |

---

## 四、📋 待办（已排期，按优先级）

### P0 — 上生产门槛（按《生产级落地路线图》阶段 0 执行）

| # | 任务 | 前置 | 估期* | 状态 |
|---|---|---|---|---|
| S0-1 | ~~**Flyway 迁移版本化**~~（D-01 由代理按路线图推荐采纳方案 B；落地形态=基线搬家+托管接入，历史迁移文件早已不存在） | — | 3d | 🟡 **待验收**（收尾挂账见下方挂起区） |
| S0-2 | ~~**Testcontainers 集成测试**~~（0-2.6 评估：Redis/MinIO 均不做容器化；全量 docker-only 验收留 S0-2b） | — | 3d | 🟡 **待验收** |
| S0-3 | ~~**Resilience4j 熔断降级**~~（0-3.5 范围缺口与恢复条件见待验收区 T14；logs/cmdb 槽随 S1 落地） | — | 3d | 🟡 **待验收** |
| S0-4 | ~~**评测跑通与基线记录**~~（报告 102 已产；eval job/artifact/README 行三项随 workflows 权限挂账；语义覆盖率待首次 REAL 手动运行回填） | S0-2 | 2d | 🟡 **待验收** |
| S0-2b | 其余 13 个 `@SpringBootTest` 类迁入 Testcontainers 基座（验收 §4.2 #1 转 ✅ 的条件） | S0-2 | — | 📋 挂后续（阶段 1 前视时间择批迁；每类先查 Redis/调度器惰性） |

\* 估期沿用路线图 §14（全职节奏）。**后续阶段（S1 取证 → S2 诊断 → S3 自愈 → S4 度量 → S5 加固）见路线图 §14，阶段内启动时再逐条登记。**

> 阶段 0 产出报告编号 100~102（路线图 §4.5）。旧 P0-1/P0-3/P0-4 已并入 S0-1/S0-2/S0-3。

### ⏸️ 挂起（有明确恢复条件，不属于「决策不做」）

| # | 任务 | 挂起原因 | 恢复条件 |
|---|---|---|---|
| S0-1 收尾 | ci.yml：删「初始化数据库 Schema」psql 步骤 + 增 `flyway validate` 步骤 + 删除 `sql/init.sql` 兼容桩（验收 #1 纯空库 Flyway、#3/#4 一并收口） | GitHub App 无 **workflows** 权限，推送含 workflow 变更被拒（历史 workflow 变更均由用户账号推送） | 用户在 Arena 重连 GitHub / 或按报告 100 §五手工应用（两处文案已备好） |
| S0-4 收尾 | ci.yml：新增 `eval`（EVAL_RAG=true）/`eval-llm`（workflow_dispatch + secrets.ALIBABA_API_KEY）双 job + 评测报告 artifact 上传；README 补评测行（验收后） | 同上 workflows 权限缺口；README 更新有验收门 | 同上；diff 已备于报告 102 §五，可与 S0-1 收尾同批应用 |
| S0-4b | 知识库文档补充：40 条正例话题（MySQL/Redis/Docker/JVM/Nginx/Prometheus/CI/CD 等，id 清单在报告 102 §四）无内置文档对应 | 内容工作、非代码；补齐前 REAL 覆盖率天然受限 | 与阶段 4 评测迭代（S4-1）或内容专项一起做 |
| S0-4c | SLB 内置文档无评测正例（文档-评测不对齐） | 补 2~3 条 SLB 提问进 eval_dataset.json，或文档侧标注暂无覆盖 | 随下次评测集修订一并处理 |
| P0-2b 剩余 | Map → record：工单余下 11 个端点 + 知识库余下（多为透传 service，需连带评估） | 路线图未排期；已完成工单/知识库/告警/治理四个模块的高价值端点，是 P1-4 TS 类型生成的最小够用面 | 阶段 0 做「自动生成 TS 类型」（路线图 §10.1 债项）时，按实际需要的端点逐个补 |
| — | ⚠️ **P0-2 的收益边界（前置调研已做，挂起后仍有效）** | 实测 70/130 端点返回 `Map`/`Object`，OpenAPI 只能生成 `additionalProperties: true`。恢复时按模块逐个改，**不要期待一次性全量** | — |

### 前端优化（方向已重新确认，按序推进）

| # | 任务 | 状态 | 说明 |
|---|---|---|---|
| F-2 | ~~语法高亮语言包按需裁剪~~ | 🟡 **待验收** | **已完成**：4890→4324 KB（省 566 KB），文件数 183→86，碎片 148→59。见「待验收」区 |
| F-5 | ~~知识库写端点加角色校验~~ | 🟡 **待验收** | **已完成**：15 个写端点全部加分级守卫（可逆 ADMIN+OPS / 不可逆仅 ADMIN）。见「待验收」区 |
| F-6 | ~~保存时统一转 Markdown 落库~~ | 🟡 **待验收** | **已完成**：`toMarkdownForStorage` + `handleSave` 接入。存量核查确认无 HTML 数据需迁移 |
| ~~F-1~~ | ~~统一到 md-editor-v3~~ | ⛔ **已撤销** | 双模式服务真实用户分层（非技术人员要写），删除会伤到一类用户 |
| F-4 | 构建体积预算 + CI 阈值 | 📋 待办 | 防体积回弹，可随 F-2 一起做 |
| F-3 | knip 死代码清理 | 📋 阻塞 | knip 在本项目上崩溃（oxc-parser 段错误），需先修工具配置 |

### P1 — 规模化前

| # | 任务 |
|---|---|
| P1-1 | 分布式追踪接 OTLP |
| P1-2 | 消息队列（告警洪峰 + 异步执行） |
| P1-3 | 多租户路线决策：PG RLS 或明确私有化定位 |
| P1-4 | 前端 TS 类型由 OpenAPI 生成（依赖 P0-2） |

---

## 五、✅ 已验收

> 本区只登记**你确认过**的任务。当前为空——机制刚建立，此前 97 轮工作的成果
> 记录在 `docs/08-benchmark/01~98` 各自的报告里。

| # | 任务 | 验收于 | 报告 |
|---|---|---|---|
| — | 暂无 | | |

---

## 六、⛔ 已决策不做（附理由，避免反复纠结）

| 事项 | 决策 | 理由 | 出处 |
|---|---|---|---|
| 分页参数改注解式校验（`@Min/@Max`） | 不做 | 要动 15 个方法签名 + 全局异常处理新增分支，且语义从「夹紧」变「报错」，与产品口径冲突 | 91 号 |
| 先查后写改悲观锁（`SELECT FOR UPDATE`） | 不做 | 守卫式修法已解决且无锁，悲观锁引入锁等待与死锁风险，代价高于收益 | 92 号 |
| 错误码改成统一抛异常转码 | 不做 | 会把显式错误分支变成隐式异常流，排查要跨文件追；`GlobalExceptionHandler` 已负责异常路径，两套并存是有意分工 | 93 号 |
| `ApiCode` 与 `BizError` 合并 | 不做 | 两者定位不同（富枚举 vs int 签名别名），合并要么改 `ApiResponse` 签名触及全部调用点，要么每处写 `.code()` | 95 号 |
| SSE 加自动重试 | 不做 | AI 问答每次调用都计费、占配额，自动重试等于用户不知情花两份钱；流式可能已产生部分输出，重试会出现两段半截回答 | 96 号 |
| `TIMEOUT`/`NETWORK`/`404` 并进业务码词表 | 不做 | 传输层特征与业务码不是一个维度，硬并要编造业务码，反让「错误来自哪一层」变模糊 | 97 号 |
| Spring Cloud 微服务化 | 不做 | 30 万行以下单体是优势；拆分后分布式事务、服务发现的复杂度会淹没团队 | 98 号 |
| 换专业向量库（Milvus/Qdrant） | 暂不做 | pgvector 在当前规模够用，换等于凭空多一个组件要运维 | 98 号 |
| 前端换 React | 不做 | 无技术理由，纯迁移成本 | 98 号 |

---

## 七、变更记录

| 日期 | 变更 |
|---|---|
| 2026-08-28 | 建立本台账；确认产品定位路线；登记 P0 四项与前端优化待决策项 |
| 2026-08-28 | 收到四项决策：编辑器走方案 A、用户全为技术人员、语言包裁剪立即开做、P0 先做 OpenAPI。台账与待办已按决策重排 |
| 2026-08-31 | 插入 OpenAPI 可消费性验证：继续改 DTO 前先确认生成的 schema 前端能用，避免多模块返工 |
| 2026-08-31 | P0-2 第二步·知识库模块：`list` 改 record。发现知识库与工单分页字段名不同（content/totalElements vs tickets/total），**刻意不统一**——前端两处 store 逐字段读取，统一属破坏性变更 |
| 2026-08-31 | P0-2 第二步启动，按模块推进。工单模块先做 `getTickets`（前端消费最多）。`getStats` 本轮不动——它直接透传 service 的 Map，改动会牵到 service 层 |
| 2026-08-31 | P0-2 第一步完成（转待验收）：接入 springdoc 2.8.15。生产环境整体关闭——Sa-Token 只管 /api/**，而 /v3/api-docs 不在其下，开着等于无鉴权公开 130 个端点结构 |
| 2026-08-31 | F-6 入库格式统一完成（转待验收）。前置核查：存量种子文档全是 Markdown，**无 HTML 数据需迁移**，风险大幅低于预期 |
| 2026-08-31 | F-5 知识库写权限分级守卫完成（转待验收）。采纳选项三：可逆操作 ADMIN+OPS、不可逆仅 ADMIN。过程中两次拒绝使用项目未验证过的 API（SaMode、mockStatic），改用已验证写法 |
| 2026-08-31 | F-2 语言包裁剪完成（转待验收）。根因是 md-editor-v3 内部引用 `@codemirror/language-data` 全语言注册表（136 个动态 import），用 Vite alias 换精简版解决 |
| 2026-08-28 | **编辑器决策重做**：核查发现「用户全是技术人员」不成立（PRD 列了 2 类非技术角色），且 v1 误判了现有实现（双编辑器是同页双模式切换，非两个独立入口）。改为方案 A′：保留双模式 + 修正存储格式。新增两项缺陷待办 F-5（权限）/ F-6（存储格式） |
| 2026-09-07 | **收到《OpsBrain_AI_生产级落地路线图.md》并确认为任务总索引**（用户上传直挂，以 fast-forward 合入工作分支）。两项决策：T11 收尾后转阶段 0；P0-2b 剩余端点暂停挂账（恢复条件=阶段 0 做 TS 类型生成时按需补）。待办区按路线图阶段 0 重排，S1~S5 启动时再逐条登记，避免双写漂移 |
| 2026-09-07 | T11 完成（转待验收）：告警/治理 Map → record。教训两条：①首轮只换了带断言的 Map 桩，漏改 3 处被 CI 编译拦下（注解上限 3 条恰好放过同类遗漏——全文件 grep 确认归零再推）；②注入验证分两个探针推，是尊重「注解最多回 3 条」的既有教训 |
| 2026-09-07 | T12（S0-1）完成（转待验收）：Flyway 迁移版本化。**教训/新知**：①动手前核查证伪了路线图「25 个手写迁移文件」的前提（已于 8-27 并入 init.sql），方案 B 因此降级为「搬家+删双写」；②真正的双真相源不是两份文件，而是**代码侧 ensureSchema 与 init.sql 内容逐字重复**——单文件基线只消灭了第一副本；③机器人无 workflows 权限改 ci.yml → 用无 DDL 的 psql 过渡桩保住 CI 绿，验收 #3/#4 挂账到用户补权 |
| 2026-09-07 | T13（S0-2）完成（转待验收）：Testcontainers 基座 + 检索链路迁入。**本轮最大收获记在 J1 探针**：路线图要求「维度 512 注入应变红」，实测竟绿——`MockEmbeddingModel` 硬编码 1536 绕过配置，MOCK 路径的维度红线是死的（CI 只有 MOCK 模式，等于从不校验维度一致性）。修复为永久代码（构造器注入），红线经「修复+注入红 / 还原绿」验证接通。另：J1b 首推因字段名漏改编译红，「沙箱无 JDK 改后必 grep 全引用」第四次应验 |
| 2026-09-07 | T14（S0-3）完成（转待验收）：Resilience4j 熔断 + LLM 限流。**本轮教训独占一栏**：对外部库 API 面，三处记忆全部失真（BOM 托管范围、starter 传递链、注解属性面+类名去后缀），每一轮都以 CI 编译红为代价换来源码核对——今后凡引用未验证过的第三方 API，先官方源码/目录核对再写代码（呼应既有「拒绝使用未验证 API」教训） |
| 2026-09-07 | T15（S0-4）完成（转待验收）：评测基线与口径收束，**阶段 0 四项全部落地**。**元教训**：①判据与度量模型必须同口径（0.73 语义门槛拿 MOCK 向量去比是无效测量）；②「56% 未命中」差点落成假基线——数字必须先问来历再落纸（实为限流拒绝伪造，被 ERROR 注解撞破）；③受限网络下注解通道（~250 字节截断）关键数字必须置消息首行；④评测第一天就抓住一个生产缺陷（S0-3 限流零等待），路线图「评测先行」自我应验 |
| 2026-09-07 | T16/T17（S1-1+S1-2）代码完成（**待 CI 复验后转待验收**）：指标/变更双取证工具落地，阶段 1 起跑。**教训新增一条**：Spring `@ConfigurationProperties` Map 属性是整体替换语义，逐 key 覆盖会吞掉其余键——注解通道第五轮才定案（可用空目录现场取证）。⚠️ 本轮末沙箱 **GitHub 认证失效**，推送与 API 双断，两任务与报告 103/104 留在本地等待推送——这是流程上第一次「代码完成但无验证」的悬置态，恢复后第一件事就是验绿，期间不再叠加新代码层数 |
| 2026-09-07 | T18/T19（S1-3 + S1-5 阶段 1）落地；本轮定案两个隐形事故：①`.gitignore` 裸模式吞源码目录案（`infrastructure/logs` 四个 .java 从未进 git，CI 编译级联红，修复=精确豁免）；②**Binder 绑定路径盲区案**（裸根键永不进 Map，catalog 恒空的终极答案；三假说全推，git 史证明自 12:14 起从未绿过）；③**双串参同序陷阱**（level/keyword 第二次互插，钉注释）。 |
| 2026-09-08 | **台账补记（S2~S4 整段）**：T20~T22（S2 诊断三任务：编排器/假设置信度/回放 API 与前端页，报告 106/107）→ T23（阶段 2 收口，108）→ T24（S3-1 受控自愈执行器启幕，109）→ T25（验收缺口补齐：幂等·强契约·审计旁写 + fabric8 首批执行器 + 执行后验证链，110/111/112）→ T26（S3-5 可观测可回放收口 + CI 十层修红终局，113）→ T27（S4-1 告警驱动策略引擎，114）。补记原因：session 间进度行断档，成果已随报告逐批提交推送，台账索引补全 |
| 2026-09-08 | T27（S4-1）落地并双腿绿（18ed3e8）。**新工具账两条**：①本地 JDK 不可得（外网 TLS 全封+无 root）——编译验证全押 CI annotations 通道（符号级定位），代价每轮 10 分钟，推前必须静态复核签名/导入/参数数（draft 15 参漏传就是复核漏网的学费）；②**git HEAD 重置事故**：本地 HEAD 被重置回基线 674429c、工作树原样保留，海量「未跟踪」全是已提交成果的假象——判据：status 显示整目录未跟踪而你确定提交过，先 `git rev-parse HEAD` 对账远端，`git reset --mixed FETCH_HEAD` 重建（工作树零触碰）再动 |
| 2026-09-08 | T28（S4-2）落地：证据门计数器（报告 115）。**新教训入 checklist**：多构造器 Spring Bean 无一标 @Autowired → 回退找默认构造器 → 全 context 崩（本地编辑器零感知、单测测不到，只有 CI 能杀）；本地无 JDK 推前 checklist 定居报告 115 §三五条，装配面审计自此优先于逻辑面 |
| 2026-09-08 | T28 补钉（批次 10）：证据徽标渲染断言 +5 例（render.smoke 24/24、vue-tsc 0 错），报告 115 §五欠账 1 清偿；**仪式感确认**：vm 层断言与渲染断言是两层皮，各自只证一半——「页面画对」必须有 find/text 级证据 |
| 2026-09-08 | 批次 11：转正引导闭环——可转正徽标升级为快捷入口（role=button/键盘可达/点击走与操作列同一条 togglingDryRun 二次确认→CAS 链路）；灰徽标保持纯信息（未达标不具备引导权）。render.smoke +2 例点击级断言，本地 52/52 + tsc 0 错 |
| 2026-09-08 | 批次 12：S4-1 欠账清偿——告警→策略引擎触发语义钉测 5 例（`AlertHealingTriggerWiringTest`）：dedup/聚合抑制两分支零调用的旁路语义从注释升级为回归闸；引擎缺席/引擎炸双降级用例补齐 S4-1 的不可阻断铁律证据面 |
| 2026-09-08 | 批次 13（T29）：证据门第三支柱落地（报告 116）——evidenceReady 现=非演练×连胜达标×审计完整；**语义纪律**：古行窗口即时效不回填、promotable 与审计完整性解耦，两条均写入 javadoc 防回辩 |
| 2026-09-08 | 批次 14（T30，真阶段 4 起跑）：评测判据升级（报告 117）——排序指标管线分层（计算面常驻 CI 钉测 / MOCK 消费面只证流通 / 语义归 EVAL_LLM）；**编号勘误入账**：T27~T29 的 S4-x 借号实为阶段 3 延伸，历史提交不追改、台账自此按路线图 §8 重排 |
| 2026-09-08 | 批次 15（T31）：S4-3 可自驱半部（报告 118）——持久化+对比脚本四路径实证（过/劣化/首跑/update）；**语义纪律**：只比双侧共有指标（env 门缺层不判负）、方向语义进键名分类、基线与 run_audit 扫描账命名隔离防整写互冲 |
| 2026-09-08 | 批次 16（T32）：S4-4.2 诊断区看板（报告 119）——`GET /dashboard/diagnosis-board`（impl 直查 JDBC 风）+ 纯函数 Composer 8 例钉测（NO_DATA 入分母不豁免、点名只认 FAILED/UNAVAILABLE、avg null 透传、未知状态进总数不进桶）；前端 B6 区四查询→五查询，smoke +3 例全绿 1804 + tsc 0 错 |
| 2026-09-08 | 批次 17（T33）：S4-4.3 诊断量逐日趋势（报告 120）——`sessionTrend{days,created,completed}` 补零下沉 Composer + today 注入不读时钟、越窗行丢弃；前端 B6 柱/折图，smoke +2 例+1 旧例前提精确化（双趋势源下「整页无图表」两源皆空才成立），全量 1806 绿 + tsc 0 错 |
| 2026-09-08 | 批次 18（T34）：S4-3 CI 接线预案（报告 121）——ci.yml 变更以 patch-as-data 落 `tools/ci/s4-3-eval-regression-ci.patch`（单 hunk 23 行：基线对比门禁 step + eval-metrics artifact）；**零漂移自证**：check/试装逐字节一致/yaml 解析/还原四步全过；4-3.2 首跑建账与 4-3.5 RAG 层开通条件写成权限落地 checklist |
| 2026-09-08 | 批次 19（T35）：knip 清零第一波（报告 122）——口径三对账（ci 注释基线已漂）；动刀 4 条（dstat 去导出/permission 收敛单出口 → **重复导出类清零**）；三疑死链全翻案保留 + 确立 **`@public` 豁免标准件**（HTML SSoT/公共库/前后端成对）；7 条伪造存证不碰；exports 21→17、types 92→91，四闸全绿（tsc/lint/1806/build） |
| 2026-09-08 | 批次 20（T36）：EVAL_LLM 本地窗贯通（报告 123）——拔两雷：LLM 层空壳成层（SseCapture 按 SSE 文本协议收 token/error/complete，正例关键词/负例拒答+幻觉穿制，llm 七键入三层合并）+ mode 钉死解锁（`${EVAL_AI_MODE:MOCK}`）；假账防线（MOCK 下响亮失败）；eval_compare 幻觉率并入「上升=劣化」域并夹具实证双修（hallucina 词根）；本地一键窗 `run_local_eval.sh`（四子命令）+ 打勾清单；knip 战线顺延 T37 |
| 2026-09-08 | 批次 21（T37）：S4-3 CI 实弹挂线（报告 124）——**D-A 兑现，workflows 权限真推送实证已还**（`73f5d90..cdf9dff`）；eval 门禁 step #9+artifact #10 挂线且首跑 success（首跑语义两轮实证）；4-3.5 落地：tee 输出同账、marker 幂等 PR 评论、pull-requests: write 最小放权、PR 模板；knip 战线顺 T38（清零即触发删 continue-on-error 权限批剩余半） |
| 2026-09-08 | 批次 22（T38）：knip 清零第二波（报告 125）——exports 17→7 **真账清零**；十条证伪记录：范式残影判例确立「同楼两式残影必删不豁免」（三 Query hook 整删）、ErrorCode 残页整删、conflict 同文件去 export、五成对半成面 @public；四闸全绿 1806；转正路径推荐 B（config 假阳校准随删 continue-on-error 同批） |
| 2026-09-08 | 批次 23（T39）：4-4.3 成本区收尾（报告 126）——单次诊断均价：trace_id 归因成本 JOIN/全部完成会话分母（零成本会话入分母与耗时同口径）、空分母 null、四位小数同前端成本口径；Composer 第 11 参数+2 钉测（合 12），B6 第五卡 + smoke 断言并轨；1806 绿 + tsc 0；**4-4 自驱件清零，余全等 LLM 窗** |
| 2026-09-08 | 批次 24（T40）：knip types 第三波（报告 127）——91→71；全量矩阵切三类后两簇 20 条全为「同文件自引用误挂 export」机械去化（零语义改动）；假阳存证增至 13 条（FrontendTicket cross=46 为 re-export 盲区最硬证物），路径 B 校准批清单闭合并待真账余 ~57 续推 |
| 2026-09-08 | 批次 25（T41）：knip types 第四波（报告 128）——**真账三域全毕业**：types 91→14 + exports 7 剩余 21 条全为假阳存证；三簇施工 56 去化+2 删；第四条判例「成对死链必须双删」（SSEEvent/SSEEventType 共同体死亡，tsc TS6196 兜底揭发）入决策轴；假阳清单 21 条封版，T42 校准+转正内容定稿 |
| 2026-09-08 | 批次 26（T42）：knip 战线终章·门禁转正（报告 129）——21 条假阳 @public 全消化（证据注释随件）、**unlisted 真账归位**（package.json 曾 0 声明 @codemirror 靠传递依赖侥幸，17 包按现存版本精确补声明 lockfile 零震荡）、本地 rc=0 五类议题全清空；删 `continue-on-error` 注释改写「退出条件已达成」，**死代码自此即红**，四闸全绿 1806 |
| 2026-09-08 | 批次 27（T43）：4-4.1 AI 效果区半部先行（报告 130）——根因准确率纯读数装配（后端零改动，批 22 存证的成对半成面三连胜）：AiAnalysisStats 恢复 export 成去化双向性范例、rated=0→「—」不老假 0%；smoke +2 含乌龙案卷（100.0% 天然含子串 0.0%→块级断言）；1808 绿；**4-4 UI 骨架全部就位，剩两卡硬等 LLM 窗** |
| 2026-09-08 | 批次 28（T44）：评测集强化弹药（报告 131）——115→128 条（五类负例贴 guard 规则族写变体）；三重纪律资产：**零 JDK 预演当场抓到 #121 漏网弹**改词后三规则齐命中、正例 65 反向预演零误伤、完整性校验器期望值「冻结数→保底闸」存量错配修复；四重验证全过，契约层 CI 实证 39 条安全负例新账面 |

## T44 补丁 · 批 29(2026-09-08,应急修复批):CI 红腿案卷 + 前端五闸确立

- **报账更正**:批 27(`8024db4`)实为前端腿 failure（历时 watch 取错 run 对，未核对 headSha);批 28(`7d4d574`)双腿 failure——两红同源：批 27 把 hook 插入 `@public` 注释与 re-export 行之间，豁免拓扑静默失效（knip 纪律 #2)。后端腿始终 success——**128 数据集 + 39 安全负例契约层 CI 实证全绿**,T44 主件战果无损。
- **修复**:hook 移出注释-声明夹层 + `AiAnalysisStats` 补 @public 第四豁免形态「返回类型推断消费」;case file `docs/08-benchmark/132-*.md`。
- **纪律升版**:前端门禁四闸 → **五闸**(tsc / lint / **knip** / vitest / build),knip 转正为本地 CI 等价口径,RC=0 方准推。
- 本地五闸实证:全绿(93 文件 / 1808 用例)。

## T45 · 批 30(2026-09-08):评测集 150 收官 + 预演工具入仓

- eval_dataset.json 128→**150**(67 正/83 负,安全三类 54),+22 条全贴规则族轨道;报告 133。
- **`tools/audit/preview_dataset_vs_guard.js` 入仓**(第六道本地闸):54/54 拦截预演 + 67 正例零误伤,RC=0。
- 预演当场抓弹两发并修复:#138 弹体虚写(private_key 下划线 vs 规则空格);工具自身 STRUCTURE_BREAK 逃逸事故(转义类张冠李戴 → 改码点谓词实现,教训入仓:语义检查不用转义类)。
- 双校验器(schema/保底闸)绿;欠账①②清零,剩 S4-2 ECE 真窗对账 + D-D 增强件。

## D-D · 批 31(2026-09-08):评测基线自动更新增强件落地

- `.github/workflows/eval-baseline-update.yml`:workflow_dispatch 派单 → 镜像主线 backend 环境重跑 → eval_compare 先亮账(劣化 rc=1 即中止,回归物理不可入基线) → 无劣化 --update → bot 回写派单分支([skip ci] 防自激)。
- 守护:环境同口径镜像 / 权限隔离(主线 read 不变) / 派单理由强制进提交审计 / artifact 留档 30 天。
- 边界:EVAL_RAG/EVAL_LLM 真窗基线不在其域,仍走本地真窗人工 --update(S4-2 欠账不变)。
- eval_compare.js 头注挂账转已办;报告 134。

- **插曲结案**:0c4e920 push 事件被 GitHub 侧丢弃(25+min 零 run),空探针 `bfde51a` 复触——同树双腿 success,批31内容有效入账。零-run-超时即探针复触已写入报账纪律(报告134 §六)。

## F-4 · 批 32(2026-09-08):构建体积预算门禁,前端五闸升六闸

- `tools/audit/bundle_budget.json`(地板=2026-09-08 实测 4227KB/782KB,余量 ⌈×1.09⌉/⌈×1.15⌉)+ `check_bundle_budget.js`(未压缩字节口径,超阈 RC=1,--update 抬预算强制写理由)。
- ci.yml 前端腿「生产构建」后接「体积预算(门禁)」;`npm run size:gate` 立本地第六闸。
- 狗食全链:真建 RC=0 ✓ / 假预算倒灌 RC=1 ✓ / 六闸本地全绿(vitest 1808)。
- 时序注:批31实证 workflows 权限已通,F-4 是排期内唯一全沙箱可验证的 CI 类件;S0-1/S0-4 收尾仍属用户验收件,不越权。
- 报告 135。

## 批 33(2026-09-08):S0-4c 清偿跟 + 标题 bug 修复 + S4-2 ECE 计算骨架

- **T45 标题拼写 bug 在案修复**:#130 expectedDocs「阿里云负载均衡SLB配置手册.md」→ 真身「阿里云SLB负载均衡手册.md」;错处=EVAL_RAG 真窗首开的未爆雷,「写注记前核文件名」入渐进注记纪律。
- **S0-4c 达成**:SLB 正例 #130/#151/#152 三条全部悬正确 expectedDocs;数据集 150→152(69/83)。
- **S4-2 ECE 骨架**:`EceMetrics`(等宽bin,空样本NaN拒假满分,越界抛拒钳位)+ EceMetricsTest 11 钉测;`eval_compare` REVERSE 词表预接 `\bece\b|calibration`(三案转台;\b 防 pieceRate 撞名已实证)。真窗首开日操作手册入报告136 §四(四步,最后一寸=LLM窗置信度通道)。
- 闸序:校验器/保底/预演152全绿;ECE Java编译+钉测仲裁交本批CI后端腿。报告136。

- **插曲结案(批33)**:会话中途沙箱重克隆(本地史回基座674429c),reflog考古+`reset --soft FETCH_HEAD`缝合,批33六文件零污染重提交(1b3a8c3..6bde3c2双腿绿)。「报账前验地基(git log先验历史连续性)」入纪律,与核headSha/零run探针并称三兄弟。报告136 §六。

## 批 34(2026-09-08):4-2.3 分桶校准器 —— S4-2 代码侧清零 + 全项目 ETA 定版

- `BucketCalibrator` + 7 钉测:空校准集恒等(红线①)/空 bin 回退全局正确率(红线②)/校准后 ECE 必降构造证明入钉。**S4-2 全部代码活清零**,剩纯数据流入(真窗一职)。
- **ETA 全景**(报告137 §二,对照路线图13.x清单):agent 侧剩 8.5~12 全职日,用户侧 ~1.5d(真窗+验收+环境+拍板);全职日历≈09-19±3 天核心清单全过,兼职 10 月上旬。两条外部闸门:真窗数据、Docker/JDK 环境。
- 仲裁:BucketCalibrator Java 编译+钉测交本批 CI 后端腿。

## 批 35(2026-09-08):S4-2 线上校准读数——反馈闭环第二读数进看板

- 后端 `HypothesisCalibrationBoard`+6 钉测:判定集=HELPFUL/WRONG,PARTIAL/未知/越界三豁免但计数,空判定集三量 null(null≠0);`getDiagnosisBoard` 同端点挂 `calibration` 键,零新端口。
- 前端校准块 3 KPI+ECE,空→—,旧后端无键→不发空壳;smoke+3(vitest 1811)。
- knip 新议题 1 条按批 29 第四豁免形态标准件消化(CalibrationBucket 嵌套推断)→六闸全绿。
- 环境尾单:node_modules 被批33重克隆清空,npm ci 复原(535包)。
- 仲裁:后端编译+钉测交本批 CI 后端腿。报告 138。

## 批 36(2026-09-08):S5-3.3 依赖漏洞扫描 CI + 全阶段残账清单

- 主线门禁:ci.yml 前端腿「依赖漏洞审计(门禁)」(npm audit --omit=dev --audit-level=high;prod 0/全量 0 干净地板起步)。
- 增强件 `vulnerability-scan.yml`:Trivy 全仓 FS 扫(HIGH,CRITICAL 即红,未修复豁免),派单+周巡(周日22:00 UTC),artifact 30天;**激活边界同 D-D:并入 main 前不注册**。后端弃 OWASP 选型案卷入 workflow 头注(NVD 无 key 限流首轮必超时)。
- YAML 狗食:`||` 回退默认单引号截断被 pyparse 当场抓——「`||` 一律双引号外包」立规。
- **全阶段未完成清单成文**(报告139 §二):S1/S2/S3 零残,S0 三笔收尾,S4 两笔真窗,S5 十子项,跨阶段 4 件;这是用户问「剩什么」的定版口径。

## 批 37(2026-09-08):S5-2 部署与运维——预算/自监控/备份反面/SOP 四件套

- compose:`x-default-logging`(50m×5 全容器有界)+ app deploy.resources(2g/1.5,768m 留);JVM MaxRAMPercentage 口径互证。
- 自监控:prometheus opsbrain job(/ai/actuator/prometheus)+ alert rules 组(Down P0/2m,Restart P2);labels 契约与 AlertService 同构。
- `scripts/backup.sh`+`restore.sh`:PG热备+MinIO整桶+manifest三件互锁(SHA256/git_sha),14天保留,恢复双 key + 三查对账;`bash -n` 过。**演练记录格待 Docker 环境**——与 S0-2b/压测同账,勿当已验收。
- `docs/09-operations/`:部署运维手册(起栈/预算/密钥L0-L2/演练模板)+ 值班SOP(AI不可用/向量库/告警风暴/磁盘满)。
- 勘察清障:app HEALTHCHECK 与 actuator probes 实测早已具备,真缺口只剩 scrape——「先核既有再补」又省一口冗余。
- 报告 140。

## 批 38(2026-09-08):S0-4b 知识库文档首波——MySQL/Redis 两手册

- `knowledge/MySQL故障排查手册.md`(六章,覆盖正例#4-8,#43 共7)+ `knowledge/Redis内存治理手册.md`(四章,#9-11,#33 共4):40 条话题的清账 11/40,单桶最高密度优先。
- **防盲注纪律重申**:只补文档不回填 expectedDocs——真窗首开以实测检索序回填 11 条注记(报告117在案)。
- 摄取面风险 LOW:MOCK 确定嵌入零外呼,既有测试无文档总数定数断言;仲裁交本批 CI 后端腿。
- 撰档狗食:Redis 档尾部乱码+孤儿代码块当场清扫。
- 续波队列定版(报告141 §五):Nginx/证书(5)→方法论(6)→K8s扩展(9)→告警通知(3)→产品档(6);散题并入相应卷,不碎档。
- S5-1 批二延:OTLP/logstash 依赖项入 pom 需 JDK 实证,纳入 JDK 环境就绪件的同批清单。

## 批 39(2026-09-08):S0-4b 续波(二+三)——11/40→22/40

- `Nginx网关与证书故障手册.md`(五章:#12-14,36-37)+`故障排查与复盘方法论手册.md`(六章:#18-21,31-32;#23-25散题并入不碎档)。
- 队列余量:K8s扩展(9)→告警通知(3)→产品档(6);注记回填纪律同批38。
- 报告142。

## 批 40(2026-09-08):S0-4b 波四+波五——22→34/40

- `K8s进阶故障手册.md`(九章,与原K8s卷分卷不重复)+`告警治理与通知手册.md`(三章,dedup 指纹两边界与钉钉五要点)。
- 余量:波六 产品档(6 条正例)最后一波慎撰;注记回填纪律维持批38。
- 报告143。

## 批 41(2026-09-08):S0-4b 波六 产品档——文档面 40/40 收口

- `OpsBrain产品与工单行为手册.md`(六章+速查):全文以代码事实为锚(六个事实源实勘在案:审批三级硬边/自愈三闸/SLA 不替你改数据/AI 三层引用/MD 原生/建单自分析),不写超出实现的承诺。
- **S0-4b 文档面收口**(11+11+12+6=40);散题#34 DNS 并入 K8s 进阶卷。真收尾差两件环境型:摄取生效验证(随 S0-2b 首跑)+ expectedDocs 注记真窗回填——文档在≠命中中。
- 报告144。

## 批 42(2026-09-08):S5-4.2 慢查询静态审计——确证 2 枚 V11 落器

- 静态对账:27 表/84+存量索引 x 热点查询列集全扫;SLA/dedup/治理五领域哨兵全在场。
- **V11 两枚**:idx_alert_group_dedup(部分索引,风暴聚合退路 dedup-miss 每条必跑)+idx_ticket_create_time(看板 KPI 每次加载必扫)。锁窗纪律:V11 后迁移一律 CONCURRENTLY 双段。
- 观察档 3 项在案不动:keyword 三列 LOWER LIKE→pg_trgm 真窗决议(建议编 5-4.5);urgent bitmap 已服务;诊断看板低频。
- 环境插曲:.git 对象空心化,fetch+reset --mixed FETCH_HEAD 复原零失——沙盘.git可回收钉。
- 连带修正:「V24/42 SLA索引」真实形态=V1 squash,对账认内容不认编号。
- 演练挂账:EXPLAIN 双查询对照/膨胀率 Monthly/trgm 拍板——真窗未跑不勾销。报告145;docs/09-operations/慢查询静态审计.md。

## 批 43(2026-09-08):S5-4.4 池配置核查——prod Hikari 裸奔封堵

- 五池盘点:Hikari prod 全默认(唯一🟡)、Tomcat 200/ Letttuce 共享/ OkHttp 被闸盖/ PG 100 全过。闸真相=ratelimiter llm 60/s,bulkhead 无配存在。
- 落器:application-prod.yml Hikari 显式块(20/5/20s/lifetime 600s/idle 300s/keepalive 240s),全 env;规模铁律 replicas×pool ≤ max_conn×0.7;leak 探不进 prod。
- S5-4 静态面全清(4.1/4.2/4.3/4.4);S5 余量二分:五件 JDK 批 × 真窗演练档。报告146;docs/09-operations/连接池配置核查.md。

## 批 44(2026-09-08):S5-6 数字资产移交清单——阶段5配置/文档面清零

- `docs/09-operations/数字资产移交清单.md`:九格清点(两格挂账:备份演练/摄取实证)+六步部署包验证+三签封口+起步三式。签收不等于销账,首真窗为销账线。
- **S5 余量纯二分**:JDK 批五件 × 真窗演练档;其余只剩用户阶件(并 main/演示)。报告147。

## 批 45(2026-09-08):S5-3.5 API 入口限流——双闸出厂

- 翻案:resilience4j 2.4.0 早守门(llm 限流既在岗),本件无需 JDK,划出 JDK 批。
- `RateLimitFilter`+`RateLimitProperties`+`RateLimitWebConfig`:规则大队(登录10/60s+AI对话20/60s)、IP分桶(ClientIpResolver 同口径辨伪)、timeout=ZERO 拒绝不排队、429+Retry-After+JSON、context-path 剥离、2万硬顶粗回收;**不标@Component**(web切片不扫配置→25类冲击半径归零,第57号案族谱入库)。
- 集成面配额安全账三件在案(4登录<5/chat调用<20/悲观累积位预留CI试错位)。
- 单测6例 Spring mock 零容器;报告148。

## 批 45续-46(2026-09-08):批45销账澄账 + S5-3.2 复核续全扫

- 红案澄账:557443e 同主代码差无双腿绿;四连红唯一同时变量=当时段 CI/网络事件(azure blob 沙箱 EOF 侧证);tee 取证道常驻。教训58:同码复绿是及格线。报告149。
- S5-3.2 续:三面扫描全合格(点名不带值/异常不带配置/审计恒空 digest);一处实获:HealingOrchestrator 撤销日志直录 undoToken 全长→ maskToken(前4+len)收口;S5-3.2 全清。报告150。

## 批 48+49(2026-09-08):S5-1 下半场——ECS 结构化日志+业务水位计

- 改道教训59号:**先扫「Boot托管版本自带能力」再排依赖批**——Boot 3.5.6 原生 structured logging(3.4+)使 logstash 依赖批解编,JDK 批收窄为只剩 OTLP 导出一件。
- 批48:application-prod.yml console/file 双 ecs,MDC/traceId 随身,硬开不给口子;dev 人类可读原样。
- 批49:BusinessMetrics 侧车四枚存量 Gauge(tickets.total/urgent_pending/alerts.active/approvals.pending),供体=既有 repo 计数零增码,供体崩→NaN 不陪葬抓面;构造器直插法被实锤弃(6+ 测试类手装)。
- 单测2例 SimpleMeterRegistry 零容器。盲编译纪律:方法引用全改显式 lambda。
- 报告152;S5-1 仅剩 OTLP 导出归 JDK 合并批。

## 批 50(2026-09-08):业务告警组——水位计接通契约,S5-1 仅剩 OTLP

- alert.rules.yml 新组 opsbrain-business-alerts 两枚:UrgentPendingHigh(>10/15m)+ApprovalBacklog(>5/1h),起步阈值首周校准,NaN 不误报双职责分离。
- 数据面=批49 四 Gauge;labels 契约全守。**S5-1 在 OTLP 导出外全组闭环**。
- 撰档狗食:告警描述两叠字返工(语句不过关带蠢字进事故档案)。报告153。

## 批 51(2026-09-08):S5-1 超时面静态审查——早就统一了,S5 收敛为一件

- S0 铸型四级单收敛阶梯(180>150>120>60)在档;外围件超时全在场;5s/8s 不 env 化的口子纪律入账。
- **S5 全残收敛为一件:OTLP 指标导出(JDK 合并批)**。报告154;docs/09-operations/超时面静态审查.md。

## 批 52(2026-09-08):OTLP 落器——S5 全段零残(演练挂格外)

- BOM 仲裁捶:micrometer-registry-otlp 版本 Boot 托管,无版本猜题,无需 JDK 实证,JDK 批最后一件销。
- 双注册器共存(prom 拉照旧+otlp 推默认关 env 显开);step=60s 画像/趋势档,告警面照归 prom 拉模型。
- **S5 十子项全清(演练挂格均带在案)**;残余面=真窗演练+用户阶件。报告155。

## 批 53-55(2026-09-08):运维档保鲜/PR 就绪书/vitest 缺席侦查

- 手册 §6-9+SOP 新排障口速查四行:批45-52 落件的错误读法先辟谣(429≠故障/ECS≠乱码)。
- PR #2 贴「合并就绪书」(marker 幂等):阻塞面全勾/合并同步三件/48h 建议序。
- vitest 缺席侦查:93 文件零 .skip,旧口径过时,零工程结案。报告156。

## 批 56(2026-09-08):验收勾稽预置件——PRD §17 十条对账

- `docs/03-quality-assurance/验收勾稽预置件.md`:十验收条 × 现行证据;翻牌全带证据链(报告/测试名),三格小注(#5时延/#7真执行器/#10真窗数字)挂账不藏。
- 13.1~13.4 原件仓内零获,不杜撰——以 PRD §17 为锚,原件定版日请用户指认并入。报告157。

## 批 57(2026-09-08):书吏增加——文档状态清单追更段

- 清单本体停更 8-26→追更段接管:七 运维档六件/八 知识册八件(覆盖正例表)/九 批案+勾稽/十 追更纪律(PROGRESS 批账一行,清单逢新档顺手追笔)。旧段原样保留。报告158。
- (本行于批 58 前补——批内全案纪律即时自查案例一条。)

## 批 58(2026-09-08):README 门面校正

- 「不是什么」节 L4 行 ❌→⚠️ 重判:链路已通/执行器 Mock 轨边界钉清,与勾稽 #7 口径对齐。门面文件入对防线纪律一条。报告159。

## 批 59(2026-09-08):真执行器接挂清单(空头挂账实体化)

- `docs/05-development-design/真执行器接挂清单.md`:现行执行面六行+硬契约三条(序/撤销启败/无声明拒绝)+接挂六道;候选四道小→大排(k8s.pod.delete 同架轨首)。报告160。

## 批 60(2026-09-09):真窗工作包+勾稽 13.x 补全(用户四问一次答)

- `scripts/lab/alert_loadgen.sh` + `explain_v11_audit.sql`(演练用具);`docs/09-operations/真窗联合测试清单.md`(T1..T15)。
- 勾稽预置件 §三:§13.1-13.4 原件寻获(仓根路线图)+26 项判(18 工程面齐/真窗补签 7/拍板 2+D-01..D-07 账)。
- 纪律三返工:清单乱文重卷/列名实对(occurrence_count/ticket_no/update_time)/loadgen 冒烟修两真案(xargs env 传导/gawk asort 性口)。报告162。

## 批 61(2026-09-09):PR 就绪书时点更新+ETA 根账

- PR #2 就绪书幂等更新(批60 时点:HEAD/15批连绿/五节面+T1-T15 指向)。
- ETA:agent 工程面剩 0;用户侧 ≈4.5 纯工日(真窗 1-1.5d+并 main 0.5d+拍板 0.5d+红笔 1.5d+缓冲 1d);明日全职 ≈09-13~14,兼职 ≈09-16~17;关键路径=并 main→T12/T13→红笔日。报告163。

## 批 62(2026-09-09):实现审计+§14 追笔+待完成清单终版

- 实测：256 源/153 后测/250 前端/11 迁移/138 端点/152 评测/CI 6 连绿;§13 26 项=工程 18+真窗 7+拍板 2。
- 路线图 §14 进度表追笔(旧 📋 全列过界一次性对账);报告 164:A 真窗 12 序/B 拍板 4 件/C 红笔日 3 条/D 路线件不入账。

## 批 63(2026-09-09):白话化返修(用户反馈看不懂)

- 新档 `docs/09-operations/项目现状与待办一页纸.md`(5 分钟可读版)。
- 《真窗联合测试清单.md》整卷重写为四问格式大白话;T12/T13 测试类名更正为 AgentEvaluationTest(原写 EvalDatasetTest 系勘误)。报告 165。

## 批 64(2026-09-09):全维深挖+路线图对立+决策落账+F1/F2 两处修

- 全卷对立:工程面 19/26(F1 CI 迁移校验补强补 13.1-1),真窗 7+Trivy 1;再无「已实现缺一的修复任务」。报告 166。
- F1:pom 钉 flyway-maven-plugin 11.20.3 + 插件 classpath 内 DB 模块;CI 迁移校验步(migrate 零障库→validate→表数≥27),三轮修红转绿(取证道 commit comment tee 常驻)。
- F2:monitoring/grafana 效果看板备用盘+手册 §10 可选启用。
- 决策三件(D-07/私有化/D-01..06)预置件 §四落案。
- README 能力矩阵 L4 行勘误(与批 58 判词对齐,执行器实查已破零)。

## 批 66(2026-09-09):合并前终检+就绪书终版(等合并键)

- 终检三格:头 SHA 双腿×2/165 commits 领先/工作树干净;PR #2 就绪书终版上帖(三判词立即合并)。
- 时长测壬:真窗清单实击 ≈6-8h/两个半天;ETA 不变(全职 09-13~14/兼职 09-16~17)。报告 167。

## 批 67(2026-09-09):全维清点三件销账+repo 现场自救实录一条

- 删:sql/init.sql(S0-1 收尾清偿,桩前提已被 CI 现状取代)+FutureCapability.vue(import 面零引用)+远支 arena/01a031f6(祖先链全含零丢失)。远程主力=main+当前。
- 自救:本地 .git 被重建到 674429c,git reset --hard FETCH_HEAD 复位 87c569c 后三刀复切;铁律+1:git status 截头排查一律全列。报告 168。


- 批 67 收官：`e4384b5` 双腿红（CI 两 workflow 仍 `psql -f sql/init.sql`；前端 knip 红因 ComingSoonPanel 成孤儿）→ `7caea5a`(schema 步桩→V1__baseline.sql 两 workflow+占位页复位隔离+knip tee 取证道）双腿绿 → `c7f4965` 重删占位页、取证帖实锤 ComingSoonPanel → 补同删后**双腿绿 ✓**。教训两条入档：删物前引用面全层含 workflows；桩内挂账语句=前提未灭。
- 批 68:PR #2 已合并(`f672d1b`,merge commit);.md 普查 251 份判"多而杂但不可删"→归档 A 落地:benchmark 166 份四桶(`50bb57b` 绿)、03/04 入 archive+PRD.md 重判保留(`4b25c2d`)。B/C 级(CLAUDE.md 瘦身/薄件核)待用户点头。
- 批 69:SQL 迁移收敛——V2~V11 折叠进 V1 单一基线(33 表,用户拍板文件不散放);dev 存量库同步修复(删账本 V2~V7 行+补 8 月旧库 9 月新表历史欠账,列差归零);429 假红根因=共享上下文跨类登录风暴踩爆 login 限流,五集成类 @TestPropertySource 关限流后 42/42 绿;SilentCatch 漂移(BusinessMetrics 补 warn 留线索,注入-还原闭环)。报告 170。
- 批69收官件B:CLAUDE.md 瘦身 3218→319 行——6.1~6.59 共 59 条决策(2985 行)逐字归档至 docs/archive-历史档/CLAUDE-6.x决策记录全量.md(锚点原样,9 处外部 §6.x 引用逐点核验仍可达);六章改 59 行索引表;双七章错位追加病灶消除(DEF 表+L1.5 状态并入唯一七章);头部定位声明同步。B 级遗留(CLAUDE.md 瘦身/INDEX.md)全部清账,C 级(14 份薄件核/批报模板收口)待用户点头。
- 批70:本地跑通实测两修复——LlmHealthIndicator MOCK 模式误报 DOWN(未配 key 起 dev 必 401→health 503 像「系统坏了」,改 UNKNOWN+守卫用例 11/11)+dev yml mode:REAL 字面量压环境变量(README 教 AI_MODE=MOCK 静默失效,改 ${AI_MODE:REAL})。端到端实测全通:栈 7 容器健康/后端 22s/health UP/四域 API code=0/SSE 流式/Vite 代理 200。路线图核验:S0-S5 工程面全 ✅(微瑕:无 micrometer-tracing 依赖、执行器走 Registry 非 @Tool 与文档表述有差);真窗 14 项全待跑(T2/T3/T4/T6/T12/T13/T14/T15),S4-3 eval 基线数字未铸。
- 批70续:路线图四处表述随实际架构校准——3-2.6 执行器实际走 HealingGate 治理链非 Agent @Tool(治理链触发比 LLM 自由调用安全一个量级,原表述作废);5-1.1 span 追踪经拍板 A 不引入(私有化部署 MDC traceId 够用,两套 traceId 体系关联成本>收益,真窗再评);5-1.3 ECS 日志实现方式如实;S5-1 进度行同步。据文档改文档,代码零改动。
- 批71:T12 铸剑闭环——eval-baseline-update.yml workflow_dispatch 由用户触发,Linux runner 首跑 eval_baseline.json(契约层 passRate 1.0/interceptRate 1.0/leaked 0/blocked 0,152 条集),bot 1bdeb98 回写 main [skip ci];门禁注入验证双向:伪造 passRate-15pt+leaked+3→rc=1 正确指认两项劣化,原值对比 rc=0;README 工程质量段补首个可复现数字(基线出处+对比机制,语义级命中率如实标 EVAL_LLM 手动 job 不虚标);S4-3 转全绿。本机 Testcontainers 被 Docker Desktop 29.6 npipe 缺陷挡死属环境限制,CI Linux 每日绿跑佐证;依赖漏洞扫描 run failure 系 runner Set up job 基础设施偶发,非扫描红。
