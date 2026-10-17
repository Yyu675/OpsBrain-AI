# OpsBrain AI · 任务进度台账

> **这是什么**：正在做和刚做完的事都记在这里。**唯一的实时进度真相源**。
> **最后更新**：2026-10-18（批163 将工单列表 KPI 固定 Map wrapper 改为 `TicketStatsData`，动态状态/优先级字典保留 Map；批162 已合入 PR #71 `090f829`）
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
「四大千亿级商业化板块」，而 98 号当时实测 **L4 执行器为零**
（2026-09-24 更新：执行器现已落地，见 `domain/healing`，但「未实证不得宣传」的原则不变）。
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
| S0-1 | ~~**Flyway 迁移版本化**~~（D-01 由代理按路线图推荐采纳方案 B；落地形态=基线搬家+托管接入，历史迁移文件早已不存在） | — | 3d | 🟡 **待验收**（收尾已清偿：批87 实证 ci.yml flyway migrate→validate→表数三步在岗、init.sql 桩已删——原挂起行随之删除） |
| S0-2 | ~~**Testcontainers 集成测试**~~（0-2.6 评估：Redis/MinIO 均不做容器化；全量 docker-only 验收留 S0-2b） | — | 3d | 🟡 **待验收** |
| S0-3 | ~~**Resilience4j 熔断降级**~~（0-3.5 范围缺口与恢复条件见待验收区 T14；logs/cmdb 槽随 S1 落地） | — | 3d | 🟡 **待验收** |
| S0-4 | ~~**评测跑通与基线记录**~~（报告 102 已产；eval 门禁/artifact/README 行三项**已清偿**（批31 权限兑现，批87 逐条实证）；语义覆盖率待首次 REAL 手动运行回填） | S0-2 | 2d | 🟡 **待验收** |
| S0-2b | 其余 13 个 `@SpringBootTest` 类迁入 Testcontainers 基座（验收 §4.2 #1 转 ✅ 的条件） | S0-2 | — | 📋 挂后续（阶段 1 前视时间择批迁；每类先查 Redis/调度器惰性） |

\* 估期沿用路线图 §14（全职节奏）。**后续阶段（S1 取证 → S2 诊断 → S3 自愈 → S4 度量 → S5 加固）见路线图 §14，阶段内启动时再逐条登记。**

> 阶段 0 产出报告编号 100~102（路线图 §4.5）。旧 P0-1/P0-3/P0-4 已并入 S0-1/S0-2/S0-3。

### ⏸️ 挂起（有明确恢复条件，不属于「决策不做」）

| # | 任务 | 挂起原因 | 恢复条件 |
|---|---|---|---|
| S0-4b | ~~40 条正例话题文档~~（批41 文档面 40/40 收口）——余两件环境型：摄取生效验证（随 S0-2b 首跑）+ expectedDocs 注记真窗回填 | 环境型（真窗/容器），非内容欠账 | S0-2b 首跑窗口 / EVAL_LLM 真窗 |
| P0-2b 剩余 | 顶层响应 `ApiResponse<Object>`/`ApiResponse<Map<...>>` 与控制器原始 Map 响应已清零；批147收口 ServiceOwnerListResponse.items，批148收口按库索引重建 failures | OpenAPI 快照、Orval、fieldContract 双向对账；保留现有 JSON 字段结构和值 | 不改请求协议、HTTP 状态和健康探针行为；继续审计嵌套 Map DTO（诊断回放、Healing steps 等），Zabbix 动态 webhook 请求 Map 保持不动 |
| — | ⚠️ **P0-2 的收益边界（前置调研已做，挂起后仍有效）** | 实测 70/130 端点返回 `Map`/`Object`，OpenAPI 只能生成 `additionalProperties: true`。恢复时按模块逐个改，**不要期待一次性全量** | — |

### 前端优化（方向已重新确认，按序推进）

| # | 任务 | 状态 | 说明 |
|---|---|---|---|
| F-2 | ~~语法高亮语言包按需裁剪~~ | 🟡 **待验收** | **已完成**：4890→4324 KB（省 566 KB），文件数 183→86，碎片 148→59。见「待验收」区 |
| F-5 | ~~知识库写端点加角色校验~~ | 🟡 **待验收** | **已完成**：15 个写端点全部加分级守卫（可逆 ADMIN+OPS / 不可逆仅 ADMIN）。见「待验收」区 |
| F-6 | ~~保存时统一转 Markdown 落库~~ | 🟡 **待验收** | **已完成**：`toMarkdownForStorage` + `handleSave` 接入。存量核查确认无 HTML 数据需迁移 |
| ~~F-1~~ | ~~统一到 md-editor-v3~~ | ⛔ **已撤销** | 双模式服务真实用户分层（非技术人员要写），删除会伤到一类用户 |
| F-4 | 构建体积预算 + CI 阈值 | ✅ 已办 | 批32 立闸 + 批86 按立法重锚（10-06: 4861/962） |
| F-3 | knip 死代码清理 | ✅ 已办 | knip 5.88.1 已是六闸成员（批26 门禁转正；批87 今日 rc=0 + 7 条配置 hints 清零）——「oxc-parser 崩溃」系过时情报 |

### P1 — 规模化前

| # | 任务 |
|---|---|
| P1-1 | 分布式追踪接 OTLP |
| P1-2 | 消息队列（告警洪峰 + 异步执行） |
| P1-3 | 多租户路线决策：PG RLS 或明确私有化定位 |
| P1-4 | ~~前端 TS 类型由 OpenAPI 生成~~ ✅ 批86/87 落地（orval 生成 + fieldContract 29 对编译期对账；「生成物全 optional→不替换只对账」改道见批86 档） |

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
| 2026-10-17 | 批128：工单流速面与 MTTR 分级时序度量体系落地（D-b 实施收官），在办工单按优先级水位拆分 `opsbrain.tickets.open{priority=P0~P3}` 与解决耗时直方图 `opsbrain.ticket_resolved_seconds{priority}` 闭环，PR #35 合入 main |
| 2026-10-17 | 批127：通知渠道送达静默度量与看门狗指标增强落地，秒级时序 Gauge `opsbrain.notify_silent_seconds` 闭环，PR #34 合入 main |
| 2026-10-17 | 批126：诊断各路证据成效度量体系落地，五路证据采集状态 Counter `opsbrain.diagnosis_evidence_status_total{kind,status}` 闭环，PR #33 合入 main |
| 2026-10-17 | 批125：钉钉通知受信任回链升格原生 ActionCard 独立交互按钮与 Markdown 智能降级落地，PR #32 合入 main |
| 2026-10-17 | 批124：告警多维质量度量扩展（severity/source）与自愈降噪反指标（`opsbrain.observation_suppressed_total`、`opsbrain.storm_aggregated_total`）落地，PR #31 合入 main |
| 2026-10-16 | 批123：三种通知模板（触发/恢复/诊断不足）企业实战重构、告警当前状态与工单生命周期全链路中文本地化、级别双语中枢、企微原生富文本色彩渲染落地，PR #26~#30 合入 main |
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
- 批72:真窗三连——T6 风暴压测(loadtest-alert-storm.sh,100/s×10s:P95 22ms/零5xx/574 入库零丢/聚合抑制 0 新单;429 判据反转=WebhookGuard 防风暴闸设计生效实证)+T3 备份恢复演练(backup-restore-drill.sh,六表对账全绿;抓出 pg_dump 缺 --clean --if-exists 恢复中止真缺陷;演练记录归档 09-operations)+T2 一键启动(SOP_一键启动.sh 四步探活断言幂等可重入,本机重入全绿)。真窗清单三项注记补入;报告 171;INDEX 追加。余:T4/T13/T14/T15+三件生产 compose 形态用户亲手跑。
- 批73:AI 模型实测普查(用户记忆「缺 chat」部分推翻——chat 是配置过时非缺失:gemini-2.5-flash 已下架→修复为实测可用的 gemini-3.1-flash-lite);embedding 实测 qwen3-embedding-8b 仅支持≤1024 维,1536 三连败,端点 13 个 embedding 模型无一可用 1536——与维度铁律冲突,REAL 模式激活挂账等用户换端点(OpenAI 官方 text-embedding-3-small 原生 1536);reranker 确认代码零消费(架构规划占位);端点有「短窗多模型探测封 IP」守卫已入注;.env/.env.example 口径同步。报告 172。
- 批74:方案C落地——多渠道模型配置(channels.chat/embedding/reranker 三角色独立 base-url/key/model,ALIBABA_* 双层回落兼容)+三道防线(REAL启动维度契约自检/模型指纹锁 SHA-256 入 sys_model_fingerprint,变更拒启+自动清语义缓存/健康探测分渠道 DEGRADED_CHAT_DOWN 精确指认)+限流配额分治(llm 与 llm-embedding 独立桶)。途中坑:devops.ai.chat.* 已被限流占用致 YAML 重复键炸启动,改 channels.* 层级;契约测试抓到我自己新代码的静默 catch(readStored)——DataAccessException 收窄+debug 留痕。ModelFingerprintGuardTest 5/5+HealthCheck 分渠道测试更新绿。报告 173/174(双 agent 并行审计 24 项:后端 12+前端运维 12,P1×6/P2×16/P3×8,修复路线图批75 P1 全清→76 P2→77 P3)。
- 批75:P1 六项全清(报告174审计第一批)——①审批超时扫描器接线(ApprovalExpireScheduler 5min,超时=EXPIRED 不放行,治理承诺复活)②WebhookGuard prod 无密钥拒收(403 非 WARN 放行,require-secret 豁免)③限流器故障分治(五参 tryAcquire:webhook 防线型 fail-closed/chat 可用型 fail-open,Alertmanager 退避重投不丢告警)④删单收口单事务(8 表,MinIO afterCommit 提交后删/回滚不删,破循环用 repository 注入)⑤前端 401 兜底语义反转(默认在岗,VITE_DISABLE_AUTH_REDIRECT 显式豁免)⑥生产 compose 补监控栈三件套(compose config 验证过)。途中:LogRedaction 契约又抓到我新日志(登记 1→2 逐条核对);前端 errorBoundary 契约的 Windows 路径分隔符存量 bug 顺手修(endsWith 不匹配反斜杠致本机误报,CI 正常)。测试账:工单域 138/138+限流 15/15+http 53/53+redaction 2/2+errorBoundary 11/11。报告 175。
- 批76:P2×5+P3×3 全清——P2-1 告警去重改单语句 upsert(xmax=0 判插改,ON CONFLICT 计次,并发窗口原子化;三测试类 40 例迁移);P2-2 审批僵尸单扫描(APPROVED 滞留>10min CAS 标 EXECUTE_FAILED,与超时扫描同频双翼);P2-3 诊断池满落库排队(QUEUED 占位谓词+60s 捞起扫描器+限量注入防自锁,V1 唯一索引谓词扩展,PG 实测三态);P2-4 compose app healthcheck+中间件资源上限(PG 1g/Redis 512m/MinIO 1g);P2-5 看板热查 created_at/collected_at 单列索引;P3-1 CSV 导出 10k 熔断;P3-2 监控规则+5(依赖探活/JVM/5xx,promtool 13 绿);P3-3 备份轮转吞错改 WARN。报告 175。审计 24 项 P1×6/P2×5/P3×3 全清,余 5 项诚实挂账(白名单粒度/小表分页/env 占位/异地备份/HNSW 调参留 T4)。
- 批77:多知识库+按库切片参数 & 二进制文档上传解析(V2,两项对标借鉴全栈落地;决策全文 `docs/09-decisions/多知识库按库切片参数与二进制上传解析.md`,收官记 `docs/06-implementation-progress/2026-09-18-多知识库与二进制上传V2收官.md`)——①三层结构 base→doc→chunk:`sys_knowledge_base` 挂切片三参数、`ChunkProfile` 不可变 record 构造期校验 parent>child>overlap≥0、逐字段 null 回落全局默认(允许只覆盖一项);`kb_id` 冗余下沉 chunk **免 JOIN 保 HNSW**(与 visibility 同一实测坑:带 JOIN 的 ORDER BY embedding<=>? 会让 PG 弃 HNSW 退化全表扫),代价=换库必重建索引已纳入 update 触发条件;默认库 code=default 承接存量、禁改码禁停用(它是未指定库的回落键);**不提供物理删库**(删库=其下文档失去归属),关闭用 DISABLED;参数变更不自动重建存量(全库重嵌=付费 API 成本),显式 `POST /bases/{id}/reindex-all` 仅 ADMIN。②二进制上传 `POST /docs/upload`(multipart):AttachmentSecurityGuard 白名单作参数重载(工单附件与知识文档合法集不同,但路径穿越/双扩展名/大小/畸形名攻击面一致,集中一份实现)→Tika 解析(选 Tika 而非 PDFBox+POI:格式全覆盖且是 LangChain4j 原生 DocumentParser 接口)→原件留存 MinIO 独立桶(**Fail-Safe 不阻断入库**,originalStored=false 如实标记)→标准创建链路全复用(清洗/去重/SimHash/向量化);扫描件(解析文本<10 字符)拒绝入库不存空文档。③**真机联调揪出两个单测照不出的真问题**:MinIO 凭证漂移(.env `MINIO_ACCESS_KEY=devops` 与容器实际 `devops_minio` 不符,附件桶从某时起就不可用而启动仅 WARN 无人感知——恰好证明 Fail-Safe 降级设计生效)+增量编译残留致列表 500(spring-boot:run 在 target/classes 半旧状态启动 NoClassDefFoundError,教训=验证前必须确认运行实例加载当次代码)。④真机对比实证:同一 396 字章节 FAQ 库(1200/300/50)切 2 片(284+162)、默认库切 1 片,**按库参数真实生效**;原件预签名下载逐字节一致。⑤配套三缺口补齐:编辑器所属知识库选择器/`GET /docs/{id}/original-url` 预签名+详情页徽章可点/库列表带 indexedCount+failedCount(一次 GROUP BY 无 N+1)。⑥**存量失红清零**:ExecutorLifecycleContractTest(ToolRuntimeManager 迁 `ManagedExecutors.forTimeoutBoundWork`——新增「有界+快速失败」池型,CallerRuns 会让 Future.get(timeout) 失效故不能用)+AlertWebhookChainIntegrationTest(测试自带 webhook 密钥并带头,与本地 .env 解耦且守卫开启下测链路更强);另修 KnowledgeWriteGuardCoverageTest 漏登记(batch-import 7→8,连 upload 计 9,新登记 KnowledgeBaseController 3 写端点+reindexAll 入不可逆清单)与 SilentCatch 基线 22。⑦明确不做:检索层暂不按库过滤(kb_id 下沉为将来铺路,上线时需同步考虑 Agent 工具白名单语义)。测试账:后端知识域 128/128+全量 1588 全绿(8 存量失红已清零)、前端 34/34 知识域+typecheck 0 错+build 通过+Playwright 真浏览器 E2E 5/5。
- 批78:阶段A(批88 B/A 核查遗留)补齐——渠道模型配置可视化落地。**测试先行**:ApiKeyCryptTest(8例:加密-解密往返/随机IV/错误密钥/降级明文/mask脱敏)+ModelChannelControllerWebTest(3例:列表视图/空数组/*apiKeyEnc永不外泄*安全底线/*ADMIN权限边界如实标注*)+AiChannelRepositoryTest(7例:upsert九列参数顺序/status回落ACTIVE/embedding列不串位/RowMapper全映射/dimension null)+AiChannelMirrorTest(5例:三渠道镜像/模型维度归属/key加密+脱敏/无密钥降级明文/镜像失败不阻断启动)。**前端可视化页**:ModelChannels.vue(三渠道卡片:chat turbo+reasoner/embedding model+dimension/reranker,脱敏key+状态徽章+时间,P0只读)+api/modelChannels.ts+types.ts+API端点+路由`/model-channels`(roles:['admin'])+导航项。**验证**:4测试类23例全绿 BUILD SUCCESS;前端 vue-tsc 0 错/eslint/knip(修AiChannelKey未用);test-compile过。**环境注记**:ChatStreamSseTimeoutTest(loginForStream data=null)为既有 `@SpringBootTest` 全上下文集成测试,本机需真库/Redis+批45 login限流假红根因,**与阶段A改动无因果**(真context下AiChannelMirror镜像正常);非本批引入不越权修。**设计取舍**:P0只读,明文/密文key永不流出后端(Controller ChannelView刻意不携带)、前端无解密能力;P1/P2开放编辑/热更新。
- 批79:阶段A增量——修复渠道状态管理"假功能死角"。**问题**:AiChannelMirror 每次启动把三渠道 status 强制写回 ACTIVE(`ON CONFLICT ... status=EXCLUDED.status`),而 AiChannelRepository.updateStatus 是 P1 预留的停用方法——运维即便把某渠道停用,重启即被镜像覆盖回 ACTIVE,停用永不生效,updateStatus 形同虚设。**修复**:镜像改为「尊重既有状态」——channel() 先 findByKey 取既有 status,渠道已存在则沿用、首次落库才回 ACTIVE;不牵架构纯增量(application.yml 仍 P0 权威源,status 仅展示/预留)。**测试**:AiChannelMirrorTest 适配 findByKey mock(默认 Optional.empty 保持 ACTIVE 回落)+新增 mirrorPreservesDisabledStatus(已停用 chat 镜像后仍 DISABLED、无既有记录的 embedding/reranker 仍 ACTIVE),6 例全绿。**核查确认**(批 88 另两 P2 已在既有批次修复,non-issue):chat 占位 key 启动拦截已在 RealModeStartupGuard 第 0 步覆盖双渠道(chat+embedding,startsWith("your-")||contains("-here"));CacheConfig 注释失实已改为真实 users 5min 描述,均无需再动。
- 批80:阶段A前端渲染测试+镜像状态保留修复收口——**ModelChannels.render.smoke.test.ts**（5例）：有数据三渠道卡片/脱敏key不泄漏明文/dimension徽章1536/空态"暂无渠道配置"/错误态重试+页面刷新按钮；后端AiChannelMirror.status保留修复（不覆盖已停用渠道→沿用既有status首次落库ACTIIVE）+AiChannelMirrorTest增例（6例全绿）；前端五闸门禁全过（tsc/eslint/knip/vitest/build），阶段A后端23例+前端5例=28例测试全绿闭环。
- 批80:阶段A AC 收口 + 真机验收(部分) + P1 方案备选。**AC 收口**:① DataStateBoundary 空态文案去掉冗余 v-if,改静态 empty-title/description(与 ActionAllowlist 同模式);② ModelChannels.vue 类型从 @/api/types 导入(不再从 api 模块再导出);③ upsert/镜像 javadoc 与实现对齐——删除「值未变不刷新 updated_at」的虚假承诺,如实写「每次启动刷新时间戳是 P0 可接受瑕疵」+ status 沿用语义;④ MODEL_KEY_CRYPT_SECRET 补进 .env.example / 部署手册 §3 / API-Key 轮换 SOP 影响范围 grep;⑤ 注释笔误 UiChannelRepository→AiChannelRepository。**测试补钉**:RealModeStartupGuardPlaceholderTest 4 例锁「占位 key 在任何网络调用之前拒启」(chat/embedding/blank/your- 前缀)。前端 ModelChannels.render.smoke 5/5。**真机验收(部分,运行实例未加载本批代码)**:本机 8088 已起(health ping 200,admin/admin123 登录成功),未登录 GET /api/v1/model-channels → 40101「未登录」(鉴权拦截生效);ADMIN token 请求同一路径 → 40400「资源未找到」——运行中的后端尚未加载 ModelChannelController(未重启)。docker compose 中间件:pgvector/redis/minio 显示 unhealthy(4 天未重启),DB 查 sys_ai_channel 因容器 unhealthy 超时未取到。**结论**:鉴权面已实证;功能面(三渠道镜像+脱敏展示)需重启后端加载本批代码后复验,不假装已闭环。**P1 装配方案见回复正文,等拍板再动手。**
- 批81(2026-09-21):阶段A真机验收闭环——用户拍板「允许重启后端」。**环境诊断**:本机 8088 后端实际早已不在(端口无监听,仅存两个无关 SPMS Java 进程),批80 记录的「404 未加载新代码」根因即此;docker 三中间件 unhealthy 系 Docker Desktop 29.6 的 exec/healthcheck 通道假死(容器内服务真实存活:PG 25432/Redis 26379/MinIO 29000 宿主端口全通,docker exec 全部挂起超时属引擎面问题非服务面),与批71 记录的 npipe 缺陷同源。**重启流程**:JDK21 mvn compile 通过 → spring-boot:run 后台起(22s 起,SchemaGuard 33 表自检过)→ 登录首次 50001(Redis 首连超时,Lettuce 池预热)→ 重试即 code=0。**验收结果全绿**:①health UP(db/redis/llm/disk 全 UP,Redis 7.0.15);②ADMIN token GET /api/v1/model-channels → code=0 三渠道镜像全落地(chat qwen3.7-flash+deepseek-v4-flash-0731/embedding qwen3.7-text-embedding dimension=1536/reranker 空 model 如实),脱敏 key `sk-ws-****5Wcs` 明文零外泄,status 全 ACTIVE(批79 状态保留逻辑首次落库生效);③未登录 40101 鉴权拦截实证。**阶段A功能面至此闭环**,批80 挂账「不假装已闭环」清账。P1 装配(编辑/热更新)方案 A 用户已拍板,下一批实施。docker exec 假死已知环境限制在案,不阻塞验收。

- 批82(2026-09-21):P1 方案A 落地——渠道模型配置编辑能力（DB 权威源 + 重启生效 + 全链路测试）。**后端**:`domain/ai/ChannelUpdate.java`（编辑请求 record,7 字段 null=不修改）+ `AiChannelRepository.updateMerged()`（整行更新,9 列+WHERE 键）+ `AiChannelService`（@Service,集中校验:维度铁律 1536 不可漂移/占位 key 拒收/chat 双模型必填/未知渠道 400/不存在 404/API key 可选保留)+ `ModelChannelController.PUT /{channelKey}`（@SaCheckRole ADMIN,DTO 脱敏,标配 restartRequired=true）+ `AuditActionRegistry` 登记 `ai-channel.update`。**装配改造(Task #5)**:`AiModelConfig` 注入 `AiChannelRepository`,turboSpec()/reasonerSpec()/embeddingSpec() 从 DB sys_ai_channel 读生效配置(DB 无记录回落 yml);ApiKeyCrypt 解密 DB 密钥后传给 ChatModel Bean——编辑保存即落 DB,**重启后端生效**(LangChain4j 模型 Bean 在 Startup 装配,热切换留给 P2)。**镜像语义**:`AiChannelMirror` 改为 seedIfAbsent——渠道已存在则跳过 upsert(DB 是权威源,镜像不再覆盖 UI 编辑)。**测试**:AiChannelServiceTest(10 例)+ AiChannelRepositoryTest 新增 updateMerged + AiChannelMirrorTest 新增「已存在不覆盖」+ ModelChannelControllerWebTest 新增编辑 PUT(脱敏 key 不出门+restartRequired)+ LlmEndpointSpecTest 适配 DB 构造器 + LogRedactionContractTest 登记 AiChannelService。46 例全绿。**真机验收**:ADMIN 登录→GET 三渠道→PUT /chat turboModel="qwen4-turbo-test"→code=0 + maskedKey 原样→GET 确认落 DB→PUT 恢复原值→闭环。**前端**:`api/modelChannels.ts` updateModelChannel() + `types.ts` AiChannelUpdatePayload + ModelChannels.vue 编辑 Dialog,P0→P1 标签。前端五闸门禁全过:vue-tsc 0 错/build 31s/vitest 5/5。**

- 批83(2026-09-21):P1 收口——两个 Guard 读 DB 消缺口 + P2 超预期件（重置端点 + 路由显示 + 前端重置按钮）。**A):ModelFingerprintGuard 适配 DB 权威源**:构造函数注入 AiChannelRepository,指纹源从「@Value yml」升级为「先读 sys_ai_channel 的 ACTIVE embedding 行（baseUrl+model）、DB 无记录回落 yml」。与 AiModelConfig.embeddingSpec() 取同一数据源——消掉「模型 Bean 已从 DB 装配但指纹锁还在算 yml 旧值」的静默混用。测试增 fingerprintReadsFromDbWhenRowExists(6→7 例)。**B):RealModeStartupGuard 占位 key 检测读 DB**:构造函数注入 AiChannelRepository,chat/embedding API key 从 yml @Value 升级为 effectiveKey()——DB 有加密行 → ApiKeyCrypt 解密、失败回落 yml。消掉「yml 占位 key 未改但 DB 已编辑真 key→启动被误拦」的假阳性。测试增 dbKeyOverridesPlaceholderPreventsFalseRejection(4→5 例)。**P2-1):渠道重置端点**:`ModelChannelController.POST /{channelKey}/reset`——无条件 upsert yml 当前值覆盖 DB,**是 seedIfAbsent 的反操作**（运维配乱后一键恢复出厂设置）。**P2-2):SSE 路由显示从 DB 取模型名**:DevOpsIntentRouter 注入 AiChannelRepository,effectiveTurboName()/effectiveReasonerName() 优先读 sys_ai_channel 的 turboModel/reasonerModel——编辑后重启「SSE start 事件」不再显示 yml 旧模型名。**P2-3):前端重置按钮**:每张渠道卡片「编辑」旁加「重置为 yml 默认值」（ElMessageBox 二次确认）→POST /reset→保存后提示重启生效。**验证**:后端 1632 用例全量（仅 2 个 @SpringBootTest 集成测试需容器环境,与改动无关）+ 前端 tsc=0 + vitest 5/5 + vue-tsc 0 错。**「模型配置」模块 P0+P1+P2 全段闭环**——安全三道防线（RealModeStartupGuard+ModelFingerprintGuard+ApiKeyCrypt）全读 DB 权威源,编辑/重置/展示/审计全通。

- 批84(2026-09-22):P2-P3 落地——热更新 + 主备降级 + 连通性测试 + 供应商模板,模型配置模块全段收官。**P3-1 热更新**:`RefreshableChatModel`/`RefreshableStreamingChatModel`/`RefreshableEmbeddingModel` 三个原子包装器(AtomicReference delegate,Bean 引用不变、内部可换),`AiModelConfig` 五个 REAL Bean 全部包 Refreshable + 暴露 build* 重建入口;`ChannelRefreshService`(仅 REAL 装配)编辑保存后从 DB 读最新配置重建模型→原子替换,embedding 渠道热更新后同步清语义缓存(防串库),失败包 IllegalStateException 由 Controller 降级 restartRequired=true(配置已落库不丢)。**P3-2 主备降级(方案 A)**:`FallbackChatModel`/`FallbackStreamingChatModel`——主模型挂→备用透明接管,失败计入 resilience4j 熔断器(llm-chat-turbo/llm-chat-reasoner 实例),熔断 OPEN 后直接走备用省一次超时;备用也挂→原样抛出不吞;**流式主备特别处理:已吐 token 后中断不可降级**(否则用户看到两段半截回答),仅首包前失败才切备用。V4 迁移加 sys_ai_channel fallback_* 四列,同存同空约束。**P2-3 连通性测试**:`POST /{channelKey}/test-connectivity`——chat 用独立探针(绕过 Fallback 装饰器,否则主挂了被备用兜住误报「正常」),主备分别 ping 分别报;embedding 真实向量化+维度校验(实测≠1536 明确报维度不符);异常消息截断 200 字符防详情外泄。**P2-4 模型列表下拉**:`POST /{channelKey}/available-models` 调上游 GET /models 拉真实模型 ID 列表,前端 datalist 一键选。**P3-2 供应商模板**:编辑对话框内置阿里云/DeepSeek/OpenAI/自定义四个端点模板一键填充。**前端**:ModelChannels.vue 全套交互(编辑/重置/连通性/模型列表/供应商模板),文案从「重启生效」更新为「即时生效,热更新失败才需重启」。**修复的装配坑**:①ChannelRefreshService 无条件 @Component 但注入 5 个仅 REAL 装配的 Refreshable Bean→MOCK 模式上下文必崩,加 @ConditionalOnProperty(REAL)+Controller 改 ObjectProvider 延迟解析;②五个 Refreshable Bean 同类型两个(turbo/reasoner)→NoUniqueBeanDefinitionException,五个参数全部加 @Qualifier;③静默 catch 契约抓到 3 处(ChannelRefreshService 清缓存/ping 探针、DevOpsIntentRouter 回落 yml)→全部补 WARN/DEBUG 留痕;④Fallback 测试用 lambda 当 ChatModel(非函数式接口)→改 Mockito mock;⑤熔断器滑动窗口 4 只留最近 4 次→断言从 5 改 4(窗口语义不是累计);⑥mockStatic 需 mockito-inline(项目无)→newProbe 抽成包级接缝,测试子类覆写(同 KnowledgeWriteGuardTest 决策)。**测试**:RefreshableModelsTest 8 例 + FallbackChatModelTest 5 例 + FallbackStreamingChatModelTest 5 例 + ChannelRefreshServiceTest 7 例 + ModelChannelControllerWebTest 10 例(新增 reset 热更新 3 例)+ LlmEndpointSpecTest 适配三参构造器,全量 1674 例绿(Windows 下 surefire 偶发 forked JVM 崩溃,加 -Djdk.net.URLClassPath.disableClassPathURLCheck=true 后稳定)。**真机验收**:REAL 模式起服(28.8s,SchemaGuard 过)→登录→GET 三渠道(qwen3.7-flash/deepseek-v5-custom 真实生效中)→PUT chat turboModel=qwen3.7-flash→code=0 + restartRequired=false + 日志「chat 渠道热更新完成」→POST /chat/test-connectivity 主模型 7312ms 连通正常→POST /embedding/test-connectivity 350ms 维度 1536 正常→POST /chat/reset 重置为 yml 默认+restartRequired=false(热更新同步生效)→GET 确认恢复 qwen3.7-flash/deepseek-v4-flash-0731。**「模型配置」模块 P0→P3 全段闭环**:只读展示→编辑落库→热更新即时生效→主备降级→连通性实测→模型列表→供应商模板,安全三道防线+静默 catch 契约+维度铁律全程在位。

- 批86(2026-10-06):**项3B + 告警级别人话化双落地,六闸全绿**(进度档 `2026-10-06-项3B字段契约与告警级别人话化.md`)。①**项3B 方案 A 改道实证**:orval 生成物(199 文件)全 optional/无枚举/无 JSDoc=字段全集**下界**,直换手写必填类型=类型安全大倒退→「替换」改「**编译期字段集对账**」`fieldContract.ts`(三闸=path/method 之外锁字段名集合双向差集;vue-tsc 执行,零运行时零新测试类);**首轮 10 红即真漂移**:6 对补手写 16 字段(后端源码取证 JSDoc)+2 对映射层豁免;orval 本机双坑病历入仓(bin ESM 探测坏→generate() 编程入口;ajv hoist 错位→进程内 require-hook,零树手术)。②**人话化**:P0~P4→紧急/严重/警告/一般/提示、动作风险类型/环境码单源字典(消 ApprovalCenter/ActionAllowlist 已漂移双份)、10 页面 16 处裸码清扫、WorkbenchQueues 色键值域 bug 顺修;判定「原始标签区/配置键/severity 不展示」三面刻意不改。③**门禁清欠**:lint 29→0(22 个是 shot-ticket-detail.cjs 存量钉子,补 scripts/** node globals 块连带清偿)、knip 6 议题清、体积闸**按立法重锚**(9-08 锚后历史增量 4861KB,本批净增≈0 有 dist 实证;vendor-element 962KB 拆包候排)、2 处文案断言跟随。四工具版本零漂移实证(knip/eslint/vue-tsc/vitest 与 HEAD 锁文件同版)。

- 批129(2026-10-17):**P0-2b 核心值班/审批/监控弱契约端点强类型化（Map → Java record）**。✅ 前后端四道契约闸全绿。①**后端强类型重构**:重构 5 组核心高频运维接口：(1) `GET /api/v1/approvals/pending/count` → `PendingApprovalCountResponse(long pending)` 替换 Map;(2) `GET /api/v1/alerts/heartbeat` → `PipelineHeartbeat` 替换 Map;(3) `GET /api/v1/alerts/logs-heartbeat` → `LogsHeartbeat` 替换 Map;(4) `GET /api/v1/alerts/notify-health` → `NotifyHealthResponse` 替换 Map;(5) `GET/PUT/DELETE /api/v1/service-owners` → `ServiceOwnerDto` 系列 record 替换 Map。②**契约联动与自动生成**:更新随库快照 `openapi/api-docs.snapshot.json`；运行 `npm run gen:api` 自动产出 TypeScript 强类型定义；前端编译期契约互证 `fieldContract.ts` 新增 `PipelineHeartbeat` / `LogsHeartbeat` 字段集双向差集断言；OpenAPI 模式测试 `OpenApiSchemaIntegrationTest` 增加批 129 新增 record schema 完整性断言。③**前端与离线验证**:前端 `vue-tsc` 类型检查、`npm run gen:check` 生成物新鲜度、`eslint`、`knip`、`vitest` 107 文件 1863 例单测全部 PASS；Python 验证工具链本地安装 `pyyaml`，`test_grayscale_metrics` 50 例及工作流校验套件 32 例全绿通过。

- 批87(2026-10-06):**继续优化——契约升维+人话化残网+残影清扫+台账清欠,六闸全绿**(进度档 `2026-10-06-批87-契约升维与残影清扫.md`)。①**闸三升维**:字段对 +4(KnowledgeDocCreate/UpdateRequest、AiChannelHistoryView、ChannelProbeResult)+**值域对 +2**(ApprovalMode/EscalateTarget union↔const enum 双向 Exclude——Java enum 字段 springdoc 产 const enum,**修正批86「OpenAPI 产不出枚举」过宽断言**;String 字段承载的 RiskLevel 仍无孪生如实记),29 对 56 断言**第二轮零漂移**(对比首轮 10 红=手写这批维护良好,非没用)。②**人话化残网**:根因=首轮 grep 只扫 `{{}}` 模板渲染漏了 JS 模板字符串与部分页——审计动作码 36 个(`constants/audit.ts` 单源对后端 AuditActionRegistry)+审计表/Trace 时间线 2 渲染、3 处 `生效环境：${...}` 模板字符串、协议四值(OPENAI_COMPATIBLE→OpenAI 兼容等 4 处)、观察窗文案「警告、一般级」顿号化。③**残影清扫**:RC_LABELS/STAGE_LABELS 三处本地副本(Dashboard/TicketDetail/TicketActionSection)归零→utils/ticketPresentation 单源+STAGE_OPTIONS 派生(对象序=状态机推进序钉死注释,批26「同内容双实现」判例落地)。④**knip 7 条配置 hints 全清**(main.ts/test-setup entry redundant、auto-imports.d.ts 等 ignore、element-plus/sass ignoreDependencies——knip 5.88 自识别后冗余,删后 rc=0+hints 段消失)。⑤**台账清欠**:S0-1/S0-4 收尾两行**翻清偿删行**(实证:ci.yml 87-97 flyway migrate+validate+表数校验、220-224 eval-metrics artifact、README 134 评测行、190 eval_compare 门禁——workflows 权限批31 已兑现)、F-3 knip 翻✅(过时情报)、S0-4b 改余两件环境型、P0-2b 恢复条件成立注记(78 端点清单)、P1-4 翻指向批86;**PRD「对功能话」坏打字单行回滚**(非词,疑误触)。57 warnings 判定**不刷绿**(27 测试桩多组件/17 TS 项目已知误报/6 no-v-html 有意 warn——lint 立法「风格不阻塞」,为数字清零改规则=本末)。

- 批88(2026-10-06):**契约闭环双闸 + 残影机械清扫第三波**(进度档 `2026-10-06-批88-契约闭环双闸与残影机械清扫.md`)。①**「底」的新鲜度入闸,四闸联防收官**:三闸(快照)此前靠人肉纪律跑 api:refresh——新增 `tools/ci/check-openapi-freshness.js`(CI 后端 job:OpenApiSchemaIntegrationTest 每次 verify 导出的 target/openapi.json=当次代码活体 vs 随库快照,path/method+**145 schema 属性集双维**——fieldContract 29 对外的全量盲区补面)+`npm run gen:check`(CI 前端 job:typecheck 前,重产+git diff --exit-code;orval v7.21.0 lock 锁版生成幂等正跑实证)。**四闸联动注入-还原全链**:快照注入假字段 fakeBatch88→freshness 红点名修法→gen:check diff 红(重产穿透)→fieldContract TS2344 点名字段→还原复正三连绿。两桩过程案:155/154 陈旧产物结案(mvn 刷新后 154=154,非 profile 差异——对账维度设计经实测成立)、脚本首跑自捉参数序 bug(load('A',…) 把标识当路径,「宁红不静默」先验了报错路径)。②**残影扫描机械化**(批26 判例首跑脚本):exact-hash+key-set 双通道扫全 src 字符串字典——唯一完全命中 STATUS_META 两页逐字双份→`constants/healing.ts` 单源(别名导入模板调用点零改);priority 四键三处同键不同值=判非残影(**同键≠同内容,机械出候选人判生死**);knip 揪 healing 两零消费 export type 即修。③**人话化残网第四处**:getMissingPostmortem 透传后端大写 status 裸渲染→两枚举同词根 .toLowerCase() 桥进字典。④**残网清单分界**:st.status 步骤值域多源未钉(欠账,须后端值域审计,不编值域)、Effectiveness/Integrations d.type 技术/配置面判不改、字典键↔enum 键集对账候选。⑤ci.yml 两步 js-yaml 机器解析+落位断言;vulnerability-scan.yml 核实已随批68 入库=已注册非挂账。六闸:typecheck/lint/knip/vitest 1813/gen:check/freshness 全 rc=0;size 前两跑 rolldown 内存分配崩(vitest 全量刚结束的提交内存墙瞬时态,批45 chromium 残留同族)回稳复跑过。

- 灰度起算(2026-10-06):**用户拍板——灰度试运行两周从即日起算,DoD 七关 Day1=2026-10-06**。剩余验收(连续 7 天 0-P1、值班 ACK 演练 ×3、送达率/诊断触发率真数)全部依赖真实值班数据自 Day1 计时;灰度周报记修复时长对比。工程面纪律:发现必修 bug 照修,不开新大功能面(六闸+四闸契约体系已就位)。CLAUDE.md 状态行与 §7.2 同步刷新。

- 批108(2026-10-15):**给漏洞扫描的首次真跑做飞行前清障——顺手挖出一份「幽灵锁文件」**(承接批107 收尾;用户「继续推进」)。**①先查清事实**:该工作流全部历史 **6 次运行全红**(`34387812361`/`34791042441`/`35545989032`/`36361869361`/`37247727107`/`38190388926`),最后一次 **2026-10-12**;批102 修 tag 后**一次都没真跑过**,下次 schedule 是 **10-18 22:00 UTC**(cron `0 22 * * 0`)。本 token 无 `actions:write`(`gh workflow run` 403),所以只能做**飞行前检查**——别让它因第二个不同原因再红。**②input 逐个对源**:用到的 7 个 input(scan-type/scan-ref/severity/ignore-unfixed/exit-code/format/output)全部存在于 `aquasecurity/trivy-action@v0.28.0` 的 **action.yaml**(注意文件名是 `.yaml` 不是 `.yml`,第一次取 `action.yml` 得 404);该 action 默认装 trivy **v0.56.1**。**③最关键的判断:dev 依赖默认不进报告**。trivy v0.56.1 的 `IncludeDevDepsFlag`(`pkg/flag/package_flags.go`)**没有 `Default:` 字段** → bool 零值 = **false**;过滤发生在 `pkg/scanner/local/scan.go:413` 的 `excludeDevDeps`,它按包的 `Dev` 字段**跨生态**过滤(pnpm 解析器 `pkg/dependency/parser/nodejs/pnpm/parse.go:186-239` 也设该字段);且 `--include-dev-deps` 对 image/rootfs/k8s/vm/sbom 均被显式禁用(`pkg/commands/app.go:274/414/1006/1074/1154`),只对 fs/repo 生效——正是我们用的 `scan-type: fs`。**故 `@orval/*` 那 17 条(`npm audit` 全量口径 **16 high + 1 critical**,全部是 dev 依赖)不会把本闸打红**;生产依赖口径 `npm audit --omit=dev --audit-level=high` = **0 漏洞、退出码 0**,首扫预期是绿的。**④挖出的真问题:幽灵锁文件**。版本库里**同时**有 `package-lock.json` 与 `pnpm-lock.yaml`,而**没有任何安装路径用 pnpm**(CI 两处 `npm ci`、`Dockerfile:21` `npm ci`;pnpm 只出现在 2026-09-14 那批历史文档里)——是迁移到 npm 时的遗留。实测它**4 个声明依赖缺失**(`@element-plus/icons-vue`/`@playwright/test`/`orval`/`sass-embedded`)、**21 个版本与实装不符**(生产依赖 8 个:`dompurify` pnpm=3.4.15 vs 实装 3.4.17、`vue` 3.5.42 vs 3.5.44、`marked` 18.0.13 vs 18.0.6 …)。它**落在 trivy 扫描面内**,会被当成另一份依赖真相解析 → 报出「我们根本没装的那个版本」的漏洞。**逐版本查 GitHub 公告库**(`/advisories?ecosystem=npm&affects=<pkg>@<版本>`):那 8 个错版本的生产依赖**当下 0 条 high/critical**——所以**今天不会因它假红**;但仍**移除**,理由是它是冻在过去的谎言、且哪天其中一个出高危就会变真。**⑤改动**:`git rm` 掉 `pnpm-lock.yaml` + 前端 `.gitignore` 加条目(带「别两份并存」的说明);飞行前结论写进工作流头部注释与台账 §六(给读 10-18 结果的人留预期:预期绿,若红先当真信号查)。**⑥验证**:`npm ci` 后 `typecheck` 退出 0、`vitest` **107 文件 1863 例全过**;工作流 `yaml.safe_load` 解析 OK、**4 步/触发器/action ref/inputs 全部未变**、注释 22→32 行(防 `safe_dump` 吃注释那类事故);全仓 grep 确认**无任何代码/配置引用** `pnpm-lock`。
- 批109(2026-10-15):**修复灰度取数器“未来日期假达标”与口径误标**（用户「继续推进修复」）。🟡 待验收。终点截到运行机本地昨天，今天也不计完整日；纯未来窗口退出 2；不再仅凭无误报记录自动判 DoD。JSON v2 分开请求/实际窗口、采集时间、窗口结束与采集完整，兼容 complete，gate_passed=null。通知标当前进程累计（非窗口增量），转单/诊断充分指标正名；快照标每日 09:20/启动采样近似增量，缺基线/窗口内读数、倒退即失败，不猜零。显式 psql -d/-X/ON_ERROR_STOP 与 UTF-8，stdout 能力检测；原生 Windows 未实测。**本地验证**：50 单测全过；真 PG15.18（253 条迁移语句、41 表，5 条向量专用语句跳过，VECTOR→REAL[]）+ 明确 HTTP 指标夹具，7 集成全过；未来日期/猜零/去-d/错名四项变异分别红 4/1/1/1，恢复 50+7 全绿。CI 改为随机 schema 克隆真迁移表+手算夹具+真实 Spring Boot 抓取面，不写业务行、不消费业务序列、缺环境报错。用户原输出已无凭据留痕，台账 §三 初填但撤销“7 天达标”，不反推新窗口数值。**决策：继续修复观测，暂不扩大灰度/关 DoD**；原因/利弊/方案详见 `docs/09-decisions/2026-10-15-灰度取数边界与口径修复.md`。漏洞扫描另查明手动 run 38733568519/38733401478 红在 setup-trivy@v0.2.1 无法解析，未开始扫描；不删欠账。本批远端 CI：代码提交 8e259af 的 push run **38757540090** 与 PR #15 run **38757578332** 均为 **三业务腿绿、工作流心跳腿红**。PR 腿真迁移库+真实 Spring Boot 的取数 7 集成通过（rc=0）；前端 107 文件/1863 例，后端 2045 例零失败/2 跳过，e2e 60 passed。心跳红明确指向既存扫描 run 38733568519 的 Set up job 失败，非取数器回归；**不抬豁免、不合并**。后续补齐单测的 Windows 可移植性：移除 /usr/bin/false、/bin/echo 依赖，mock 覆盖 q 的成功/SQL失败/执行文件缺失出口，50 例仍绿。
- 批110(2026-10-15):**修漏洞扫描 composite 内部坏钉，补递归依赖闸与 PR 实扫自证**（用户「继续推进」，承接 PR #15 的阻塞）。🔵 进行中，作为独立修红提交承载于同一 PR/固定分支。事实：trivy-action@v0.28.0 自身有效，内部 setup-trivy@v0.2.1 无法解析；仅检查顶层 uses 漏报。本地新递归闸在旧工作流上 exit 1，明确定位该链；改为签名 v0.36.0 tag 对应完整 commit ed142fd0673e97e23eac54620cfb913e5ce36c25，显式 Trivy v0.70.0 后 **11 个 action/workflow 全链通过**（含 setup-trivy 与 cache/restore、cache/save、checkout 的完整 SHA）。递归工具只读 API/YAML，不执行远端代码；17 单测覆盖内部坏钉/完整SHA/分支/子目录/本地与远端复用工作流/循环/403/空检查。摘要器 7 单测覆盖 JSON 缺失/无效、漏洞有无补丁、零目标、annotation 转义且不泄露 Secret 匹配内容。扫描策略 HIGH,CRITICAL、ignore-unfixed=true、exit-code=1 保留，JSON 留档无补丁项并输出可读摘要；缺报告不再静默。工作流增加自身/工具修改限定的 pull_request 真扫，避免每个业务 push 都扫；心跳只看 completed，不把进行中的自证误作健康。不抬基线。首真扫 38763086455 明确抓到 MinIO HIGH；修补依赖后扫描 run **38764845589 success**（两个目标），据此删除已过时 vulnerability-scan.yml 基线条目，而非追认新增红灯。最终实现调整为固定 setup commit + 显式 CLI（9 节点），原始 gate JSON/日志/退出码都保留，第二遍不得冒充第一遍结果；再补4例 shell 原始退出码测试。全量 CI 待验证。
- 批111(2026-10-15):**修首扫抓出的真实 HIGH——MinIO XML 属性替换信息泄露**。🔵 进行中。首扫 run **38763086455**：25s 完成，Set up job 正常、扫描目标 2（pom.xml、前端 package-lock.json），HIGH/CRITICAL 仅 1 条：CVE-2025-59952 / GHSA-h7rh-xfpj-hpcm，io.minio:minio 8.5.12→8.6.0。公告与上游补丁 f7a98d06b25e5464bdd4811b044e25ff9101d37f 已核对。新增 MinioXmlSecurityTest 4 例，直接调 SDK String/Reader XML 解码器与 round-trip/畸形 XML，不 mock、不按版本字符串判断；探针仅用固定假属性值，finally 还原。**旧版红证据**：PR run 38763460638 的后端 2049 例中仅新增 String/Reader 两例失败（4 例中 2 失败、0 错误）；期望占位符字面值，实为固定假属性值，确实复现替换。随后升级 8.6.0（最小修复版），另加真实 SDK + 本地 HTTP 协议夹具，验证 bucketExists/上传/签名链接/删除以及 Boot BOM 下运行时兼容性；不冒充完整 MinIO 端到端，不增加忽略规则。升级后的真实 SDK/XML/HTTP 兼容测试随 PR run 38764845956 的后端2050例全过（0失败/2跳过），扫描38764845589也通过；编译缺失与后续5条依赖补丁另记批112。
- 批112(2026-10-15):**首扫后续五条 HIGH 与 MinIO 依赖布局修红**。🔵 进行中。run 38764494554 的第一遍 gate 报告实际有 5 条 HIGH（第二遍留档竟为 0，不得据此假报绿）：CVE-2026-22731/22733→Boot 3.5.12，CVE-2026-54291/42198→JDBC 42.7.12，CVE-2026-55405→pgvector 1.2.1-beta8。已逐项核对 GitHub 公告；只升 Boot 3.5 补丁线、JDBC 显式托管版本和受影响的 pgvector beta8 模块，不跨全套 AI API。MinIO 升级后 CI 暴露 HttpUrl 编译缺失，根因是 OkHttp5 多平台入口，需要 okhttp-jvm 编译字节码；补 JVM 构件并将 MockWebServer 对齐5.1.0。扫描改为固定 setup v0.2.6 commit + 显式 CLI，第一遍报告/日志/退出码全部留存，第二遍不代替门禁证据；4 例实际工作流 shell 协议测试验证原始非零码不可吞、缺报告不可绿。实际 Maven/后端验证：PR run **38764845956** 后端 **2050例0失败0错误2跳过、空库42表**，补丁确实可解析，MinIO真实XML与HTTP兼容回归通过；扫描 **38764845589 success**，第一/二遍均2目标、0条HIGH/CRITICAL。同提交全量 CI **38764845956（PR）/38764834758（push）全四腿 success**，前端1863/e2e60，PR状态MERGEABLE/CLEAN。现补齐文档/删失效基线/摘要标明报告文件名，最终确认轮待跑；全部绿后按既有修红授权 squash，不冒认用户灰度验收。
- 批120(2026-10-16):**Spring6.2同线真实回补**。✅ 实现与首轮同源完整验收完成。前置官方本项目17JAR+BOM供应全GET200，Boot原Framework入口对齐6.2.19，不跨Boot4/Spring7、不加BOM、不改业务。代码b227399 push38869393684/PR38869398347/scan38869398349全success、10/10+CLEAN；后端2082/0失败/0错误/2既有skip，新SDK七例与旧6.2.17四项安全真实断言红（逐原因/17模块旧classpath实核）通过。前端1863/107文件、浏览器60、真SQL+HTTP+CLI7、指标面通过；本地verify亦1863。原生19→16（关41850/41842/41845三条、新增0，11HIGH+5CRITICAL/6GAV），16/16实际打包；82目标variant逐Boot SHA，Spring16另等于同次Central实际JAR；原66逐字段、所有非Spring runtime GAV/type/classifier全不变。292/297、SBOM296/301、scanner296/两跳缺0；Boot288/287匹配/unknown1/incomplete、原生rc1/full_security_passed=false保留。120例检查器、七类唯一源码注入→FAIL非ERROR→还原；Spring仅正向关闭三条，partial余量5=Spring2+Tomcat3，含新发现不裁剪，不宣称Spring整组清零。收尾增强旧SDK全部七例（三健康+四红），最终HEAD独立验收与合并后main见PR23；不加豁免、不切不完整生产输入、不扩大灰度。持久runtime-spring-patch-2026-10-16.json及《Spring6.2同线回补与部分修补边界》决策；下一批先核Boot3.5.14/Data3.5.12实际供应与托管关系，Jackson维护线另审。
- 批119(2026-10-16):**Junrar真实有界VM兼容减量**。✅ 实现与首轮同源完整验收完成。官方7.6.1实际POM/JAR可交付，定向DM从7.5.5升级（7.x内跨minor，不伪称7.5补丁）。代码92011c8 push38861302432/PR38861308886/scan38861308839全success、10/10+CLEAN；后端2075/0失败/0错误/2既有跳过。SDK上界拒绝与真实JMP→RET双红负对照2失败/0错误/0跳过、旧7.5.5 JAR实核，旧VM由输入List第五次取指即硬退出，不跑恶意RAR死循环，不依赖时钟；修补版六方法（正常VM/真实SDK-RAR/项目Tika入口/Unicode）及全量通过。前端1863、浏览器60、真SQL+HTTP+CLI7、指标面通过。原生20→19（关97370一条/新增0，14HIGH+5CRITICAL/7GAV），19/19实际打包、native_gate_rc1；66目标variant逐Boot SHA绑定，Junrar还与Central实际JAR SHA相等，原65逐字段不变，其他runtime GAV/type/classifier全不变；292/297、SBOM296/scanner296/两跳缺0；Boot288/匹配287/unknown1/incomplete仍保留。116例检查器自测、六类唯一源码注入检出还原，固定来源/许可/哈希的两个健康RAR夹具仅test资源。证据runtime-junrar-patch-2026-10-16.json，决策《Junrar有界VM修补与归档兼容决策》。最终HEAD独立复验见PR22；不加豁免、不切不完整生产输入、不扩大灰度。下一批优先核Spring6.2同线可交付回补，不能同线修的WebMVC与Jackson/Boot另审。
- 批118(2026-10-16):**BC版本族兼容减量**。✅ 实现与首轮同源完整CI验收完成。官方实取1.85，实际树抓出只设属性不生效、额外bcjmail旧版、测试Mail传递路径影响生产scope三类问题，四入口定向DM+测试路径隔离后全部复验；不是仅改版本号。代码9827e55 push38858697709/PR38858702234/scan38858702348全success、10/10+CLEAN；后端2069/0失败/0错误/2既有跳过，旧BC1.81五类真实安全断言5失败/0错误/0跳过、四模块classpath实核，修补版完整通过；前端1863、浏览器60、真SQL+HTTP+CLI7、指标面通过。本批变更前1a5实扫23（历史22+新披露Junrar97370一条）→20（关BC3、对本批新增0）；15HIGH+5CRITICAL，20/20实际打包、native_gate_rc1。65目标variant逐Boot SHA绑定，BC四模块还与Central实际JAR SHA直接相等，既有61字节不变；所有非BC runtime GAV/type/classifier不变，292/297恢复，SBOM296/scanner296/两跳缺0；Boot288/匹配287/unknown1/incomplete仍保留。113例检查器自测+十类唯一源码注入检出还原。证据runtime-bc-patches-2026-10-16.json，决策《BC版本族安全减量与真实加密兼容决策》。最终HEAD须独立复验见PR21；Junrar新披露不掩盖、优先下一批核官方7.6.1供应与归档解析，不混本批，不加豁免、不切不完整生产输入、不扩大灰度。
- 批117(2026-10-16):**Tomcat可交付部分减量+观测/解析库补丁**。✅ 实现与首轮完整同源实跑完成。基线main a3bdd20原生37条HC；官方Central GET证实57全3个embed POM/JAR可用，58仍全404。现Tomcat10.1.57修9条并完整保留3CRITICAL，Micrometer1.15.12/OpenNLP2.5.9/jsoup1.23.2完成组清零，Tika/Netty原50个variant版本/SHA均不变。代码4abf826：push38800823905/PR38800831679/scan38800831821全success，10/10+CLEAN；后端2061/0失败/0错误/2既有跳过，OpenNLP旧版真实安全负对照3失败/0错误/0跳过、修补版全量通过；前端1863、浏览器60、真SQL+HTTP+CLI7。原生37→22（移15/新增0，15HIGH+7CRITICAL，22CVE/8GAV），22/22实际打包、native_gate_rc1；61目标variant逐Boot SHA绑定，TC/OpenNLP/jsoup与实际Central下载SHA直接一致；292runtimeGAV/296SBOM/296scanner，两跳缺0；Boot288/匹配287/unknown1/status=incomplete仍保留。109例检查器/工作流自测、12类注入检出。证据runtime-patches-next-2026-10-16.json+dependency-patch-supply-2026-10-16.json，决策《Tomcat部分减量与解析观测库补丁决策》。最终HEAD独立复验见PR20正文/checks，不能拿4ab绿代替；不加豁免、不改原生红码、不直接替换生产输入、不扩大灰度，Spring/Jackson/加密提供者分批决策。
- 批116(2026-10-16):**Tika/Netty同线漏洞修补与真实运行验收（Tomcat供应另决策）**。✅ 实现与首轮同源验收完成。PR18已真实squash至main b534c15，main CI38793264024四腿success；订正先前误称OPEN PR已合并。最终方案Tika3.2.2 core+standard-package双入口（不是仅升core）、Netty4.1.137.Final；Tomcat10.1.58候选真实CI证实Central缺3个embed构件，已撤回覆盖，原10.1.52的12条HC保留，源码自建/可复现供应另批。完整Tika BOM曾把Netty越线拉4.2.3、Jackson降2.19.2、OpenNLP降2.2.0，专项闸真实判红；现改定向管理不污染Boot。代码4b462b6首轮push38795670581/PR38795675674/scan38795675681全部success，10/10+CLEAN；后端2055/0失败/0错误/2既有跳过、前端1863、浏览器60、真实SQL+HTTP+CLI7；旧Tika3.0.0负对照1失败/0错误。原生HC58→37（移除21/新增0，25HIGH+12CRITICAL，37CVE/11GAV），目标两组0HC；24Tika+19Netty4.1+7tcnative=50variants全部真实Boot SHA绑定。原生门禁码仍1、37/37发现仍实际打包、Boot288/匹配287/unknown1、status=incomplete；原POM输入32/292不直接替换，工程绿≠安全绿≠灰度。99例检查器/工作流自测与10类注入检出，补丁完整2片+原生紧凑1片恢复验SHA，未冒称下载完整库artifact。证据runtime-patches-2026-10-16.json；决策文档《传递依赖首组补丁决策》。收尾补Word/Excel真实解析，最终HEAD独立复验与既有仅修红squash授权按PR19最终checks/正文留痕，不拿4b绿替代。
- 批115(2026-10-16):**原生 runtime SBOM→Trivy 与 Boot 实际库字节独立实验**（用户继续推进，PR18）。🟡 待验收，未合并。批114 PR17经确认squash合并03c4ea9，main38785665611四腿全绿，实际2050/1863/60+取数7集成通过。本批真实run **38786511670/95ae146**：原生CycloneDX2.9.1有297唯一GAV/302变体，Maven树298变体无缺项，Trivy读全297 GAV；但发现**58条HIGH/CRITICAL（42 HIGH+16 CRITICAL，56 CVE、19包）**且同源原生gate **rc1**，绝不是安全已修复。Boot内嵌库289个，SBOM SHA256匹配288，另有jarmode-tools-3.5.12.jar未知；Maven范围中Lombok+13个starter未打包，SBOM比树多4坐标，第二轮38787391235/d8330bc确认全部58条均关联实际打包库字节，4额外坐标也均打包且原树无路径，不能按超树基线当误报；同代码10/10构建/协议成功（2050/1863/60+真实取数7），native安全门禁仍rc1；后续文档收尾以PR最新检查为准。实际紧凑证据2片恢复/验SHA，完整库哈希在artifact不假称已下载。已核Tika/Netty/Spring MVC三条上游公告：6.2受影响线无同线first_patched不能盲跳7.0/Boot4。生产前端/Secrets门禁与阈值不变，实验保留漏洞原码+warning不替代放行；工具/坏输入/吞码失败。新增14行为+5实际shell，加既有71共90例本地验证；真实构建和扫描非fake。详见本批[决策](docs/09-decisions/2026-10-16-原生SBOM实验与运行时漏洞处置决策.md)/[证据](docs/09-operations/evidence/runtime-sbom-probe-2026-10-16.json)。结论：不直接替换生产输入，不扩大灰度；下一批按真实部署字节处置传递漏洞和SBOM边界，分开最小补丁与Spring兼容性评估，不加豁免、不红合并。

- 批114(2026-10-16):**首扫 Java 运行时依赖覆盖核对**（用户「继续推进」，PR #17）。🟡 待验收，未自动合并。实际run **38781339101/HEAD1119e9c**：真实Maven runtime唯一GAV **293**（298个type/classifier变体），Trivy pom仅**32/293匹配，缺261、extra0**，审计status=incomplete；job绿只证明执行成功，不得据0漏洞说安全。独立后置runner取同run原始报告，固定dependency-plugin3.8.1 runtime JSON，不预热首扫。按GAV区分缺包/版本偏差/extra，排除根与test/provided/system子树，optional直依赖保留；只声明GAV级而非字节级覆盖。三份输入SHA与checkout关联。合法差异warning、工具/输入错误报红、--strict未暗中启用；不抬阈值、不加豁免。首轮单条差异注解被截断4086字符不能解析，已改压缩分片+SHA回放（超大件明确仅artifact）；新增17对账/5实际shell协议，加既有49共71例本地通过，三变异（只比数量/忽略版本/跳optional）均红。第二轮38781873040/b4b013b已完整恢复4片96323字节并验SHA，仍293/32/261；32恰是直接依赖去掉Lombok，missing为260传递GAV+Lombok。Lombok已在Boot repackage排除，scope树不等于部署lib清单，不把261全称部署漏扫jar、不加临时豁免；最终HEAD回归继续以PR检查为准。详见[决策/利弊/流程](docs/09-decisions/2026-10-16-Maven首扫覆盖审计与后续输入决策.md)与[首轮证据](docs/09-operations/evidence/maven-scan-coverage-2026-10-16.json)。决策：推进审计，下一批优先Maven原生runtime SBOM独立实扫并核覆盖，不直接把差异变成新生产硬闸；当前覆盖不完整，不扩大灰度、不关闭真人ACK/0/17/观察覆盖。批113已合并3a219ef、main38775720306全四腿success（见PR16合并后评论）。
- 批113(2026-10-16):**Trivy 双扫丢发现：单次扫描+离线同源门禁**（用户「继续推进」，PR #16）。🟡 待验收，未自动合并。main a5e8639 run38767095000全四腿success；本批真实对照38772526074 outside-default gate11个CVE/full仅1个，丢10个。首版探针只查指定CVE导致consistent=true，结论作废，已改全部目标/CVE/包/版本/路径指纹及包覆盖比对。生产只有一次fs，保留含无补丁项的原始报告；严格按0.70.0 Status=fixed派生，非FixedVersion判据，其他发现/包/元数据原样，源/派生SHA256关联；由Trivy convert判原始失败码，不二次解析Maven。真实run **38773493944/HEAD90cadae success**：受控仓库健康时检出传递MinIO CVE，注入429后重扫发现丢失却rc0，同源convert仍保留该CVE且rc1，期间仓库请求0；生产两目标0HIGH/CRITICAL，196包，artifact上传成功（证据来自annotations/元信息，非声称已下载原始artifact）。仅证明机制与修复，不把历史HTTP状态说成429，不把同源当覆盖完整。后置探针、不改变阈值/版本/豁免；新12策略+8探针+5shell/单扫守卫+既有7摘要/17引用，共49例本地通过；指纹加强与全量回归由PR最新HEAD检查追踪，不以旧run冒充。详见[决策/利弊/流程](docs/09-decisions/2026-10-16-Trivy单次扫描与同源门禁.md)与[证据](docs/09-operations/evidence/trivy-single-source-2026-10-16.json)。建议推进报告链路修复，canonical Maven/SBOM覆盖核对另批；不扩大灰度、不关闭0/17/真人ACK/观察覆盖。



- 批107(2026-10-15):**治台账 §七 A6 那只偶发——dev server 中途 reload 打断 e2e 导航,它已把 main 打红过一次**(承接批106 收尾时 main 转红;用户「继续推进」)。**①现象与定性**:main `ed35194` 那次 e2e 红 1 例(`boundary-edge-cases.spec.ts:59`「两窗口同时编辑同一工单」),报错 `page.goto: net::ERR_ABORTED at /tickets/TKT-20261015-0001`。**红只是表象,用例本身没问题**——是 dev server 在用例跑到一半时把页面重启了。事实核对:此前 4 次 main 跑(`676d8a7`/`b14974f`/`7f47f18`/`334860b`)该用例 0 次命中,批106 那轮 5 次 e2e 跑命中 2 次——**故不断言「与批106 无关」**(时序可能被推动),只认定机制与告警无关。**②本机复现(关键:不需要浏览器,看 dev server 日志即可)**:`unplugin-vue-components` + `ElementPlusResolver` **按需**注入 `element-plus/es/components/<x>/style/css` 这类副作用导入,Vite 只在「某视图第一次被转换」时才发现它们 → 重跑 optimizer → **强制整页 reload**。拉 `/src/views/TicketDetail.vue`、`AlertList.vue` 的过程中实测到两次:`dependency optimized: …drawer/style/css` → **`optimized dependencies changed. reloading`**;`dependencies optimized: …table/style/css, tag/style/css` → **又一次 reloading**。而 e2e 里 `page.goto('/tickets/<id>')` 恰恰就是「第一次转换那个懒加载视图」的时刻,reload 与进行中的导航相撞即 `ERR_ABORTED`。**③修(两半,缺一仍会中途 reload)**:`server.warmup.clientFiles`(main.ts / router / `views/*.vue` / `components/**/*.vue`)把依赖发现时机挪到「还没有任何导航」的窗口;`optimizeDeps.include: ['element-plus/es/components/*/style/css']` 用**通配**一次收全组件样式——逐个列组件名必然漏,漏一个就又是一次中途 reload。**④前后对照验证(同一套请求序列)**:修复前 2 次 reload;修复后拉同样 2 个视图 + 另外 10 个 e2e 会走的视图(KnowledgeBase/Monitoring/Effectiveness/Assistant/Dashboard/ActionItemBoard/TicketInsights 等)**0 次 reload**;预打包元数据 `_metadata.json` 里 **element-plus 组件样式条目 27 个**(通配确被展开)、预打包条目共 66。本机四闸:`typecheck` 退出 0、`lint` 0 error(59 warning 同前)、`vitest` **107 文件 1863 例全过**、`npm run build` 退出 0(**改了 vite.config.ts 必须验生产构建**,`optimizeDeps` 只影响 dev 但配置写错会连带炸构建)。**⑤纪律**:`playwright.config.ts` 的 `retries: 0` **刻意不动**——用重试把偶发藏起来,等于把这只账继续挂着;验收判据按台账 A6 原文「连跑 3 次 e2e 不红」执行。**⑥台账 A6 划销留痕**,并把机制与前后对照写进解除动作栏。
- 批106(2026-10-15):**F2「未建单」徽标补浏览器级证据——销掉台账 §七 A5 那笔「三段各自都绿、合起来没人验过」的账**(台账 §七 里 A1~A4 都要真环境,A5/A6 是纯工程缺口,故先做 A5)。**①账的形状**:F2 的修法是「后端派生 `unticketedReason`、前端只显示」,链上三段——(1) `AlertService.unticketedReason()` 只在 **FIRING + 观察级(默认 P2,P3) + 无工单 + 早于补单回溯窗 24h** 四前提同时成立时才派生文案,**缺一即 null、页面什么都不说**(`AlertServiceTest` 钉住);(2) `AlertRowBadges` 的 `v-if` 决定徽标渲不渲染;(3) `el-tooltip :content` 决定悬浮说明是不是后端原文((2)(3) 由 `AlertRowBadges.test.ts` 钉住)。**缺的正是把三段串起来的浏览器级断言**——「真库里真有这样一条告警时,页面上真的看得见」从没被验过。**②交付**:(a) 种子加第 5 件:P2/FIRING/无工单、`last_occurred_at=now()`(列表按它倒序 → 保证落第一页)、`first_occurred_at=now()-26h`(早于 24h 窗)——**形状即真实场景**:Zabbix 侧网络恢复后重投的历史事件;自校验从四件套扩到五件套,缺则 RAISE。(b) `e2e/alerts-unticketed.spec.ts` 2 例:一例断言徽标可见 + 悬浮说明**逐字等于从 API 取的** `unticketedReason`(**期望文案不写死在用例里**——回溯窗从 24h 改成 48h 时用例要跟着红,而不是继续绿着骗人);一例对第一页**逐行对照**「有徽标 ⇔ 后端给了原因」,并要求该页**同时存在「该标」与「不该标」的行**,否则反向断言根本没被行使、用例只是空跑。**③验证**:沙箱重建真 PG 15.18(npm 预编译 + psycopg2)跑完迁移后**灌种子两次**(幂等,1 行),自校验 NOTICE `未建单告警=1`,行形状 P2/FIRING/ticket_id NULL/**26.0h** 四前提齐;vitest **107 文件 1863 例全过**(写法守卫扫过新 spec 无违规)、`vue-tsc` 与 `eslint` 干净、`playwright --list` 发现 2 例(58 → **60 tests / 14 files**);**CI 实跑 60 passed**(run 38719529449,四腿全绿)。**④变异验证(浏览器级断言必须证明它会红)**:把徽标选择器改成不存在的类名 → **e2e 红 3 例/57 passed**,其中正是本批两例(run 38720265774);还原后 **60 passed**(run 38720986710)。**⑤顺带得到的一个数据点**:变异那一跑里第三例红是 `boundary-edge-cases.spec.ts:59`「两窗口同时编辑同一工单」——即台账 §七 **A6 那只已知偶发**(vite 依赖预打包中途 reload 打断导航),本批 3 次 e2e 跑中命中 1 次,A6 仍在账上。**⑥仍未销**(如实):A1~A4 要真环境;A6 待做;漏洞扫描自 10-12 后仍未真跑过(`gh workflow run` 403,等 10-18 22:00 UTC schedule);台账 §三 Week2 六格仍空,**要真库真抓取面才能填**。
- 批105(2026-10-15):**入口文档规模口径清账——写死的数字全在漂,而且漂得没人发现**(承接批104 交付时的只读勘察;用户「可以了」放行)。**①问题**:入口文档里的规模数字全是手写快照,没人复核过。逐个实测后,**四处对不上**:(a) `README.md:102/145` 写前端「106 文件 / 1,855 例」——`npm run verify` 实跑 **107 文件 / 1863 例**(= `src` 105 + e2e 写法守卫 2:`conventions.test.ts`/`login.test.ts`,这两个不在 `src` 下,只数 `src` 就会少数 2);(b) `README.md:71` 写「后端 197 文件 / 37.6K 行,前端 242 文件 / 73.7K 行」——实测后端 `src/main/java` **329 文件 / 59.9K 行**(含测试 532)、前端 `src` **511 文件 / 85.8K 行**(业务 193 + 契约生成物 213 + 测试 105);(c) 同行写「130 个 REST 端点,27 张表,30 条前端路由」——实测 **30 控制器 / 183 个 `@*Mapping`**、**42 表**(CI 空库建账口径 = 41 契约表 + `flyway_schema_history`,出处见 2026-10-13 通知模板决策件)、**35 条唯一路径**(含嵌套子路由 44 个 `path:`);(d) `CLAUDE.md:19` 状态行停在**批97**,落后 7 批;`CLAUDE.md:20` 写「e2e 腿 21 步 / 6 个实弹面 / 回退 12s」——现为 **22 步 / 7 个面 / 10s**(批104 加了台账取数那一面)。**②为什么值得单独成批**:这些数字是新人判断「这项目有多大、测试有多密」的唯一入口,错了不会报错、不会红,只会让人**按错的规模做错的判断**(比如以为端点 130 个、实际 183 个,RBAC 覆盖面评估就会偏)。**③修法不是改数字了事**:数字每批都会漂,所以 (i) 测试规模改成「**以 `npm run verify` 输出为准**——写死就会过期」,(ii) 规模段每个数字**带口径**(哪个目录、含不含测试、含不含生成物),(iii) 段末附**四行复核命令**,谁都能 5 秒对上。**④验证**:后端例数取 CI 后端腿 annotation 原文 `Tests run: 2045, Failures: 0, Errors: 0, Skipped: 2`(不引旧文档);前端 `npm run verify` **本机实跑退出码 0**(typecheck 0 / lint 0 error 59 warning / 令牌 185 定义 102 引用 0 幽灵 / **107 文件 1863 例全过**)+ `npm run knip` 退出码 0;端点/表/路由数按上述命令实测。**⑤仍未销的账**(如实):依赖漏洞扫描自 2026-10-12 那次之后**没再跑过**(`gh run list` 五条全 failure,最近一条正是 baseline 登记的 `38190388926`),`gh workflow run` 本 token 403,只能等 **10-18 22:00 UTC** schedule 或人工 Run;跑绿后**必须删** `.github/workflow-heartbeat-baseline.json` 里那条,留着会永久遮蔽真问题。台账 Week2 取数命令已在 main,但**要真库/真抓取面**,CI 的 e2e 环境读数(送达率无样本、自愈 0 次)**不能当台账数据**——等真环境跑一次再填。
  **⑥顺手把根因补掉**(光改数字,下批还漂):前端腿「单元测试」步原先只有 `npm run test`,**汇总从不落到 annotation**——后端腿早有「Tests run: 2045…」可对账,前端没有,所以那个数字只能靠人手抄、手抄就抄错了。现改为 `tee` 全量日志 + 把 `Test Files`/`Tests`/`failed` 行压成 `::notice::前端测试汇总`(有红转 `::error::`),**规模从 CI 直接可读**。两条路径用固定日志本地回放:全绿 → notice + 退出 0、有红 → error + 退出 1,ANSI 均被剥净。**⑦回放抓到一个我自己写错的地方**:拼 annotation 前缀那条 `sed "s|^|…|"`,**写进文件的收尾分隔符不是 `|` 而是 `/`**(字节级复核 `od -c` 实证),`sed` 直接报 `unterminated 's' command`——CI 上会静默少一段 annotation。改用 `awk -v p=…` 拼前缀,不依赖 sed 分隔符。**教训:写进文件的字节不一定等于我以为写进去的字节,凡关键符号(分隔符/引号/日期里的连字符)落盘后要 `od` 复核,而「本地回放一遍」是唯一能兜住这类错的网。**
  **⑧真 runner 又抓出一条**:首版匹配模式顺手收了 `failed`,结果真 runner 上一堆 `[Vue warn]: … type check failed for prop …` 全被压成 annotation,**把 GitHub 每级 10 条的配额占满,真正的 `Test Files`/`Tests` 汇总行反被挤掉**(该次 run 四腿全绿,annotation 里 10 条全是 Vue warn,一条汇总都没有)。**噪声不只是难看——它会顶掉信号。**已收窄成只匹配那两行汇总并 `head -4` 限量,回放夹具特意塞 12 行 Vue warn 复现:notice 恰好 2 条、退出码 0;有红仍 error + 退出 1。
- 批104(2026-10-15):**灰度台账取数器——把「按周手记」换成可复跑口径，免得 Week2 变成第二个不可回溯窗口**(承接批103 末尾提议;用户批准)。**①问题**:台账 §三 六个槽位要按周填,Week1(10-06~10-12)已如实记「不可回溯,属实际缺口」。查下来根因不是忘了记,而是**没有取数口径**;Week2(10-13~10-19)只剩几天,再靠手记就会重演。**②查证到三件事实**(全部对过 schema/代码,不是推断):(a) 「0-P1 误伤」是**结构性不可回溯**——数据源 `sys_alert.false_positive`/`false_positive_marked_at` 由 V17 引入,而 **V17 文件头注自述日期 2026-10-12**,即 Week1 七天里六天这两列还不存在(⚠️ 日期只能以迁移头注为据:**PR #3 的 squash 把作者信息压平了**,`git log` 对所有文件都返回 squash 提交,无法佐证);(b) 台账写「送达率读 `notify_delivery` 口径」——**库里没有这张表**,它是 Prometheus 计数器 `opsbrain_notify_delivery_total{result=ok|error}`(`BusinessMetrics.java`),照表名查 SQL 永远查不到;(c) 其余几项**早就有日快照列**(`sys_effectiveness_snapshot`:alerts_total/alert_sourced_tickets/diagnosis_total/diagnosis_sufficient/healing_total/healing_succeeded 等),只是没人拼成台账要的行。**③交付**:`tools/verify/grayscale_metrics.py`(六行一次取出,每行带来源列;算不了的明确标「人工登记」并说明原因,不猜不糊)+ `test_grayscale_metrics.py`(25 例口径单测)。**④口径要点(写错不报错、只给错数)**:告警分级列叫 **`level`**(P0~P4)不是 severity;工单创建是 **`create_time`** 不是 created_at;快照列是**累计值**必须「末读数−窗口前读数」**不能 SUM**;误报按 `false_positive_marked_at` 落窗(V17 注释明确要求);「连续 0-P1 天数」要**尾部连续段**(中间断一天重新起算),不是窗口内合格天数总和。**⑤四处变异验证全部红过**:累计列改 SUM → 2 例红;连续天数改成合格天数总和 → 3 例红;分母 0 报 100% → 1 例红;**指标名前缀匹配 → 首版测试没红**(夹具里没有同前缀序列,断言是"因为没有 result=error 行"才通过的,等于没测)——补上 Prometheus 真实会产出的 `_created` 序列夹具后才咬住(2 例红)。**这是变异测试抓出的测试自身缺口,不是产品缺陷,但同样会让口径悄悄错**。**⑥验证**:口径单测 25/25;降级跑(无库无抓取面)逐行点名未取原因、退出码 1;非法窗口退出码 2;**SQL 未在本地执行过**(沙箱无 psql/docker),故接进 CI e2e 腿第 14 步(RBAC 之后)对**真迁移库**跑一遍——列名写错只有真库验得出来。**⑦台账同步三处**:送达率口径订正为指标名、质量数行补取数命令、Week1 缺记补上「结构性不可回溯」的准确归因与 git 无法佐证的说明。**⑧首轮 CI 红了,红得有价值**:push 腿 e2e 第 14 步 exit 1,而 annotation 里只有「Process completed with exit code 1」——因为工具在打出任何判词之前就崩了。沙箱读不到作业日志,所以本轮把**真 PG 搬进沙箱**复现:npm 的 `@embedded-postgres/linux-x64`(预编译 PG 15.18 服务端;**PyPI 的 `postgresql-wheel` 是 0 字节空壳,别用**)+ psycopg2 写的 psql 桩 + 逐句跳过 pgvector/pg_trgm,跑完 19 个迁移建出 **42 张表(与 CI 口径一致)**。真库一跑当场现形:`Taker.q()` 返二元组、调用方按三元组解包 → `ValueError: not enough values to unpack`;这条路径**只在「有库」分支才走**,所以沙箱降级跑与 25 例纯函数单测都碰不到它——**「本地全绿」当时确实没有意义**。**⑨修复与补测**:`q()` 统一成 `(ok, value, err)` 三元组;`main()` 加内部异常护栏(崩了打 `::error::取数器内部异常` 并退出 3——CI 读不到日志,不喊就等于根因隐形);单测补 13 例(取数胶水用 FakeTaker、SQL 形状静态守卫、`main()` 退出码),共 **38 例**;**五处新变异全部红过**(q 退回二元组 / SQL 报错仍判完整 / 分级列写成 severity / 快照列改 SUM / 去掉异常护栏)。**⑩真库对数**(不是只验「不崩」,是验「数对」):灌手算种子——P0/P1 误报标记 3 天 + 三类干扰行(P2 级、未标误报、窗口外)、3 单止损 2.0/8.0/0.5 小时 + 1 单窗口外、4 次自愈(3 成功)+ 1 次窗口外、累计快照窗口前(10-12)与窗口内(10-19)两读数——六行读数与手算**逐一相符**:连续 3 天未达标 / 触发率 30.0%(9/30) / 产报率 66.7%(4/6) / 止损中位 2.0 小时 3 单 / 自愈 4 次成功 3;另起**带令牌校验的假抓取面**验送达率 95.0%(95/100)(`_created` 的 1.76e9 未混入、同前缀 `opsbrain_notify_total` 未误配),摘掉令牌 → 点名 401 且退出 1(不静默)。**⑪CI 步骤加固**:工具崩在判词之前时,annotation 兜底把日志尾巴顶上来(没有结论时,日志本身就是结论);口径单测红了单独压 `::error::口径单测`;三条路径(全绿/工具崩/单测红)已用固定日志**本地回放**验证(退出码 0/1/1)。**⑫两个新教训**:(a) **YAML 改一步别用 `safe_dump` 整体回写**——本轮试过一次,1143 行里吃掉 199 行注释、把 run 块压成转义长串(注释里还带着心跳闸要 grep 的 `heartbeat-max-age-days`),已 revert 改为原文定位替换(净 +18/−6);(b) 有一处「日期格式非法」用例的入参**渲染出来像合法日期**(字节里是斜杠 `/`),断言虽然过了,但读者会以为它在测合法日期——已改成 `2026-13-45` 这种一眼就假的值。**断言过了 ≠ 测对了。**
- 批103(2026-10-15):**把「哨兵还活着吗」搬进 push 门禁——批102 的教训制度化成棘轮闸**(承接批102:修版本号只解决那一例,同类哑化会以别的形式复发)。**①要解决的问题**:**只跑 schedule 的门禁,它的红不在任何人日常视野里**——漏洞扫描连续 5 周在 Set up job 阶段失败,账面上有周巡、实际零覆盖,无人发现。**②新增心跳闸**(ci.yml `workflow-refs` 作业第 3 步):扫出所有带 `schedule:` 的 workflow,逐个查最近一次运行的时间与结论,三种判死——从未跑过 / 超期没跑(容忍间隔由各 workflow 用注释 `heartbeat-max-age-days:` 自declare,默认 10 天) / **失败在 Set up job**(=根本没跑起来)。**③关键分寸(差点做错的地方)**:漏洞扫描发现 HIGH/CRITICAL 时**本就 `exit-code: 1`**——**「上次红」可能是真发现,不能一律判死**。故只有失败在 Set up job 才算哑哨兵;失败在真扫描步则只出 warning 并写明「可能是真发现,请人工看一眼,别当噪声」。**④棘轮式已知欠账**:`.github/workflow-heartbeat-baseline.json` 登记「已知坏了、修复在途」的那次 run——同一 run 只警告不判死;一旦出现更新的运行该条自动失效。理由:当前最近一次失败正是 PR #8 修掉的那次,若不认账,这道闸会从第一天起就红、且我无法自行清除(手动派单 403,要等 2026-10-18 的 schedule)——**一条上线即红又清不掉的闸只会训练人忽略红**。**⑤五条路径全部实跑验证**:真实状态 → 警告+exit 0;删掉欠账登记 → `::error::`+exit 1;容忍间隔改 1 天(实际 3 天) → 判「哨兵停摆」+exit 1;新增一个从未跑过的 schedule 工作流 → 判「哑哨兵」+exit 1;桩造「失败在真扫描步」→ 只警告+exit 0;桩造「上次成功」→ ok。**⑥教训**:闸的分寸比闸本身难——把「真发现」误判成「哑哨兵」会把真信号压成噪声,反之则漏掉哑化;所以判据必须落到**失败在哪一步**,而不是只看 conclusion。
- 批102(2026-10-15):**发现一只「哑哨兵」——依赖漏洞扫描连续 5 周一次都没真跑过**(按用户批准的多轮观察 main 时顺藤摸到)。**①现象**:查 main 近 20 次运行,除 3 次 push 绿外,另有 `schedule/failure × 5`——`依赖漏洞扫描（S5-3.3 增强件）` 在 2026-09-13 / 09-20 / 09-28 / 10-05 / 10-12 **连续五周全红**。**②根因**:`Unable to resolve action aquasecurity/trivy-action@0.28.0`——该仓真实 tag 是 **`v0.28.0`**(带 v),钉错版本导致 job 在 **Set up job 阶段**就失败,即**一次都没真正扫过依赖**。危害比"扫出漏洞"大得多:账面上有周巡、实际上零覆盖,而且它只在 schedule/dispatch 上跑、**不进 push 门禁**,所以五周无人发现。**③修法**:钉回 `@v0.28.0`(2 处),并在 ci.yml 新增 **`workflow-refs` 闸**——扫 `.github/workflows` 里所有 `uses: owner/repo@ref`,逐个拿真 API 验 tag 或 branch 是否存在,不存在即红;grep 一个都没扫到也按失败处理(防模式失效后静默恒绿)。**④验证**:守卫逻辑先在本地对着**坏钉状态**的仓库跑过——6 个引用中准确点名 `aquasecurity/trivy-action@0.28.0` 为 BAD、其余 5 个 ok;修钉后 6/6 ok;空目录下跑则触发「一个 action 引用都没扫到」的自证分支。**⑤教训**:一条只跑 schedule 的门禁,**它的红不会出现在任何人的日常视野里**——所以「依赖可解析」这类前置条件必须放进 push 门禁去守,否则哨兵哑了没人知道。同类风险:凡 `uses:` 钉了具体版本号的,都存在钉错即哑的可能。
- 批101(2026-10-15):**e2e 偏红「同源一族」系统清账——33 处裸 goto 收口 + 写法守卫(4 条,均变异验证过)**(承接批99/100:两次偶发红指向同一族机制,单点修完还会从别处冒出来)。**①盘点**:全套件 `page.goto()` **33 处不带 `waitUntil`**、18 处带;而本应用挂载即建 SSE/WS 长连接,`load` 可能永不触发(=`goto` 默认值),既白等到 actionTimeout,又给「客户端跳转与进行中的 goto 相撞 → `net::ERR_ABORTED`」留出窗口——批100 那例红正是这个机制。另查出 `auth.spec.ts` 自带一份**本地** `loginViaForm`(只填表+单击、无重试),即批99 的点击重试修复**没覆盖到它**。**②修法(单点收口,不逐处贴创可贴)**:新增 `e2e/helpers/nav.ts` 的 `gotoApp()`(统一 `domcontentloaded`),33 处全部改走它;`login.ts` 把「重填+重试」底层原语导出为 `submitLoginForm`,`auth.spec.ts` 改用它(**不换整个 helper**——该文件有用例本就期待 4xx,断言留在各用例里)。**③写法守卫**`e2e/helpers/conventions.test.ts`(4 条):裸 goto / 绕过 `submitLoginForm` 直接点登录按钮 / 裸 `browser.newContext()`(不继承 storageState → 用例长期空过) / `gotoApp` 实现自身不得退化。**④守卫的四处变异验证**:注入裸 goto → 红;注入裸点登录按钮 → 红;注入裸 `browser.newContext()` → 红;把 `gotoApp` 实现改回裸 `page.goto(path)` → **首版抓不到(前三条只扫 .spec.ts)**,补第 4 条后红。**⑤守卫自身的两处假阳也修了**:`request.newContext()`(APIRequestContext,自己 POST 登录拿 token,与浏览器 storageState 无关)与**注释行**里的关键词都不该算违规——首版规则太粗,一跑就误伤,收紧为「只扫代码行 + 只匹配 `browser.newContext`」。**⑥验证**:`npm run verify` exit 0(typecheck 0 / lint 0 err / **107 文件 1863 例**,较上批 +1 文件 +5 例即守卫本身)、`knip` 0。**未验证(如实)**:Playwright 浏览器 CDN 在沙箱不可达,33 处改动**没在本地真浏览器跑过**,判据以 CI 多轮观察为准。
- 批100(2026-10-15):**多 Tab 并发写工单用例的偶发红定案——红只是表象,真毛病是它从没在「两个已登录窗口」上跑过**(PR #5 合并后 main 仍红的另一例)。**①现象**:main(4cde97d)e2e 腿 `boundary-edge-cases.spec.ts:58` 红,1 failed / 57 passed,报错 `page.goto: net::ERR_ABORTED at /tickets/TKT-20261015-0001`;同一 commit 另一腿全绿。**②根因(两层)**:(a) `browser.newContext()` **不继承** `playwright.config.ts` 里 chromium 项目的 storageState——那是 `context` fixture 才注入的(playwright@1.63 `lib/index.js` 里 `browser.newContext({ ...videoOptions, ...options })` 即 context fixture),裸建的上下文是**未登录**的;(b) `/tickets` 没有 `meta.public`,守卫于是 `await promptLogin(...)` 弹一个**没人点**的模态,随后按「初始导航无处可留」回落首页——这次客户端跳转与还在进行中的 goto 抢跑 → `net::ERR_ABORTED`。**③顺带查出更严重的一点**:因为未登录,`ticketLink1.isVisible()` 多数时候为假,整段并发编辑逻辑被 `if` 跳掉,用例只断言了「body 可见」——**长期空过**(假绿的一种:看着在跑,其实没测)。**④修法**:两个 context 显式带 `storageState: e2e/.auth/admin.json`;goto 一律 `domcontentloaded`(本应用挂载即建 SSE/WS 长连接,'load' 可能一直不触发,同 `auth.setup.ts` 的既有说明);工单链接改**硬断言**(e2e 腿第 10 步已灌常驻工单,不可见就是真坏了)——防止它再退回空壳。**⑤验证**:`npm run verify` exit 0(typecheck 0 / lint 0 err / 106 文件 1858 例)。**未验证(如实)**:Playwright 浏览器 CDN 在沙箱不可达,**本改动没在本地真浏览器里跑过**,判据以 CI 多轮观察为准。
- 批99(2026-10-15):**e2e 登录点击落空的偶发红根治——把「诊断」升级成「自愈」**(PR #4 合并后 main 仍红的那一例)。**①现象**:main(3242630)e2e 腿 `role-matrix.spec.ts:28` 红,1 failed / 57 passed;批97 加的诊断第一次自己说出了成因——「点击后 15s 内没有 /api/v1/auth/login 响应——按钮在 boot 期重渲染导致点击落空(原因①),与鉴权无关」。**②定性**:诊断生效了,但**故障还在**;本页 boot 期会异步写 html 的 data-theme/data-radius/data-density,按钮被重渲染时 click 可能落在已失效节点上,而 Playwright 的 actionability 检查在点击那一刻是通过的、它无从知道这次点击没生效,**因此不会自动重试**。这解释了此前多次「同 commit 一腿绿一腿红」的偶发。**③修法**:`e2e/helpers/login.ts` 抽出 `submitUntilRequested`——最多点 3 次、每次**重填表单**(重渲染会把绑定换到新节点,旧节点里填的值可能随节点丢弃);单次等响应从 15s 缩到 8s 以便早点进入重试。**关键纪律:只在「请求确实没发出去」时重试**——请求已出门却没响应是后端那一层的事,再点一次等于重复提交(还会多打一次 login 限流桶),故新增 ①′ 分支单独点名。**④变异验证(三条都红过)**:关掉重试(3→1)→ 2 failed;重试但不重填(删循环内 fill)→ 2 failed;请求已发仍重试(去掉 requestSeen 早退)→ 1 failed;还原后 9/9 绿、文件与备份逐字节相同。**⑤验证**:`npm run verify` exit 0(typecheck 0 / lint 0 err / **106 文件 1858 例**,较上批 +3 例即本次新增)、`npx knip` exit 0。**待 CI 回填**:e2e 腿偶发红的根治只能靠多次 run 观察(单次绿不构成证明)。
- 批98(2026-10-15):**PR #3 squash 合并进 main(63 commits)后,e2e 腿的发布回退门禁在 main 上自红——根因是批97⑩ 的身份判据设计缺陷,已修**(修复 commit 见本次提交)。**①现象**:main(c62088e)首跑 e2e 腿第 14 步 exit 1,注解里 `✓ v_prev 健康(回滚耗时 12438ms)` 已打出、但 DoD#6 判据行没出——说明脚本在「回滚成功之后、结论之前」死了。**②根因(我的设计错,不是环境抖动)**:脚本用「新版独有端点 `alerts/webhook/zabbix`」证伪版本身份,断言「回滚后契约里不该再有它」。该针在**分支上**成立(prev=origin/main 没这端点),可一旦 PR 合进 main,prev 与 cur **同版**,回滚后契约当然仍有它 → `die "回滚没生效"` → 永久红。更深一层:任何「某端点只在某版存在」的针都是**一次性**的,下一次合并同样会失效——把它当长期判据本身就是错的。**③修法(把身份判据从「产品契约」换成「构建物+进程」)**:a) 前置断言两版**构建物指纹**必须不同(按内容哈希,不用路径+大小——实测 8 字节的 `class A`/`class B` 会被判同版、把真有两版的演练误拒;内容哈希只在同源码重编时可能判不同,那是安全方向);b) 运行时身份改查 `/proc/<pid>/cmdline` 是否含本版 class 目录(证「端口上跑的确实是这一版」,且不依赖任何端点);c) `--marker-needle` 保留但**默认空**＝不参与判据,只在两版确有契约差异时显式传。**④CI 侧 prev 的取法也得改**:原先无条件取 `origin/main`,但(1)在 main 上它就是 HEAD(同版);(2)在分支上若本批只改脚本/CI/文档,`origin/main` 与 HEAD 的**构建物完全相同**(实测 `src/`+`pom.xml` 差异 **0 文件**,而 `merge-base` 66a7383 与 HEAD 差 **40 文件**)。故改为取「本轮工作线之前的那一版」= `git merge-base origin/main HEAD`,它等于 HEAD 时(在 main 上)再退一格取 `HEAD^`。**⑤「构建物相同」不能当致命错**:那样每条只改文档的提交都会把这条闸刷红,反而训练人忽略红。改为**响亮警告 + 照常计时**——切换/重启/探活/<300s 与 health UP 判据不受影响,只有「切到了另一版」这一条本轮不可证;警告行同时进 annotation(不留痕的放水比红更坏),结论行也明写「版本身份本轮未证」。**⑥第二轮红案:浅克隆**——改完 prev 取法后分支腿仍红,且**注解里连一行 drill 输出都没有**(说明死在 drill 之前)。根因:`actions/checkout@v4` 默认 `fetch-depth=1`,浅克隆下 `origin/main` 这个 ref **根本不存在**、`git merge-base` 与 `HEAD^` 一律 fatal(本机 `git clone --depth 1` 实测复现);原步骤当初用 `FETCH_HEAD` 正是这个原因。修法=只用 `FETCH_HEAD`,要它的父提交先 `git fetch --deepen=2 origin main`,并加兜底(拿不到父提交就退回 `FETCH_HEAD`——宁可「身份不可证」并响亮警告,也不要因取不到 ref 整步红)。两种场景都在真浅克隆里验过:分支上 prev=`FETCH_HEAD`(c62088e)、main 上 prev=`FETCH_HEAD^`(66a7383=合并前 tip)。**⑦验证**:三条路径本机实跑过——同目录 → 拒演 exit 1;两目录内容一致(模拟 main 合并后) → 拒演并点名「prev 应取 HEAD^」; 同大小不同内容 → 前置放行、进到启动阶段(本机无 JVM/classpath 才停)。CI 侧待本 PR 绿后回填。**教训**:一条门禁的判据如果依赖「本分支相对基线的差异」,它就**只能活在分支上**——合并即自毁。进门禁前要多问一句:**这个断言在 main 上还成立吗?**
- 批97(2026-10-15):**actuator 闸门串红定案 + 业务闭环/RBAC 实弹转为 CI 门禁 + 两处"探测器自身缺陷"修复**(承接批96 的"下一轮再跑闭环演练")。**①串红根因定案(不是配置问题)**:`spring-boot-test-autoconfigure` 的 `ObservabilityContextCustomizerFactory` 对**每一个** Spring Boot 测试默认注入 `management.defaults.metrics.export.enabled=false` + `management.simple.metrics.export.enabled=true` → `@ConditionalOnEnabledMetricsExport("prometheus")` 判 false → **`/actuator/prometheus` 从未注册(404)**、只剩兜底 `SimpleMeterRegistry`;生产形态(真 jar)不受影响。修法=测试类加官方开关 `@AutoConfigureObservability`(metrics 默认 true 即放行);取证靠把诊断拼成**一行**(CI 回帖按行截断,多行断言白丢一轮往返),顺带删掉不可信的 `ClockBean` 探针。**②两个验证面从"本机人工跑一次"转为门禁**:`closed_loop_drill.py`(44 跳硬闸逐跳点名,带 `--psql/--pgurl` 直查库)与 `rbac_walkthrough.py`(14 探针面×三角色=42 格,判据取自源码执法点)接进 e2e 腿第 11/12 步——理由:上一轮它们的结果只存在于会话与文档里,**不可复现不可回归**,而 e2e 腿本就备齐真 jar+真 pg+redis+prom 桩+种子账号。新增 `tools/verify/ci_summary.py`:把证据 JSON 压成一行 `::notice::`/`::error::` 落 check-run annotations(**缺证据按失败处理**),因为托管 runner 的作业日志与附件在沙箱 egress 不通的域上(`gh run download`→`productionresultssa0.blob.core.windows.net` EOF,实测)。**③探测器假阳(比漏报更伤)**:演练的抓取面探针首版只读前 4KB,而 Prometheus 文本**按指标族名排序**、`opsbrain_*` 排在 `jvm_/http_/logback_` 之后 → CI 上把"业务指标明明抓得到"判成硬失败(同一 run 里独立抓取步全量读却数出一堆 `opsbrain_*` 行,矛盾即定位)。修法=先取 256KB、没看到再放大到 8MB 重取,证据里带读取字节数与 `# HELP` 族数;CI 抓取步同步去掉 `head` 截断。**④e2e 偶发红案定性通道**:`role-matrix.spec` 曾红于 `expected /localhost:5173\/$/ received /login`(同 commit 的 PR 腿 58 passed),而三种不同故障(点击落空/非 2xx/token 未落盘)在该断言里长得一模一样 + 日志截图读不到 → 无法定性。现把登录抽成 `devops-platform-frontend/e2e/helpers/login.ts`(抓响应状态、429 单独点名、校验 token 落盘,断言一条不放宽),失败分支由 `e2e/helpers/login.test.ts`(vitest 6 例)钉住——**它们在 e2e 腿里永远走不到**;已反向验证:helper 退回旧形态,6 例中 4 例即红。另核对 token 落盘在导航之前(`app.login`→`setAuthToken`→之后才 `router.replace`),故该校验不会假阳。**验证(CI run 38664704405 三腿全绿)**:后端 **2045 例 / Failures 0 / Errors 0 / Skipped 2** + 空库 **42 表**;e2e **58 passed**;**业务闭环 PASS 42/44(硬失败 0**,软失败 1=MOCK 桩向量下 RAG 语义命中不成立、SKIPPED 1=引用反馈回流);**RBAC 42/42**;抓取面(生产形态)裸抓 401 / 带令牌 200 且含 `opsbrain_*` / 同令牌打 `/actuator/info` 仍 401 / 无凭据 health 200。本地闸门:`run_local_tests.sh` **14/14 退出 0**(含该集成测试类型检查)、`npm run verify` **exit 0**(typecheck+lint+token 审计+**106 文件/1855 例**)。**⑤e2e 假红定案(新诊断第一次上场即命中)**:run 38665168695 角色矩阵 2 例红,报错一行定性`登录被拒（e2e_viewer）：HTTP 429｜响应={"code":"RATE_LIMITED"}`——限流键是**远端 IP**,而 e2e 里 Playwright 串行套件所有请求都来自 127.0.0.1、共用 login 那只桶(10/60s),一轮要发 ~15 次登录(setup 1 + auth.spec 6 + role-matrix 6 + tickets 2)。这也回头定性了 run 38660040500 那桩悬案(同根因,当时断言不带现场)。修法=login 规则 `permits/period-seconds` 改可按环境覆盖(**生产默认仍 10/60s**)、e2e 腿设 `LOGIN_RATE_LIMIT_PERMITS=200`——**抬配额而不是关总开关**(一关 e2e 就不覆盖限流这条链了),429 文案同步指向该开关防漂移。**⑥就绪判据太松(run 38666117982)**:e2e 启动步原先只等「端口应答」,而 health 是探针契约与后续每一步的隐含前提,端口先于 health 就绪 → 抓取面步偶发撞进 health 仍 503 的窗口;更糟的是报错文案把 503 说成「探针端点被拦」(被拦是 401/403,503 是应用**自报状态**——两个方向完全相反,会改错地方)。修法=两段等待(端口→轮询 `/actuator/health` 到 200)、失败即打印组件明细(`show-details: always`)、文案按 401/403 与 503 分开说。**⑦哨兵自证收进仓库并进门禁**:`tools/verify/selftest_scrape_face.py`——Z-监控面 哨兵的坏法通常是「恒 PASS」(比没有哨兵更坏:让人以为有人看着),故用桩服务六态(正确/裸奔/凭据错/端点在但无业务指标/指标排在 4KB 之后/令牌文件缺)逐一核对判词;**变异验证过**:把「裸奔必须 401」判据改成恒真 → 5/6 退出 1;还原历史上那次「单次 4KB 读、无兜底」的 bug → far 态红。该件此前是 `.local-verify/scripts/` 的**本地件**(沙箱重置即丢失、别人复现不了),现入库并接进 e2e 腿(排在闭环演练之前)。**⑧如实记账:两处"证据"其实不可复现**——`.local-verify/evidence/*`(监控审计/闭环 JSON/回退日志/守卫测试 14 例)与沙箱内 `toolchain/` 离线闸门**都从未入库**,已随沙箱重置丢失;此前文档把它们当证据位引用是虚的。已把 4 处引用改成**可复跑命令 + CI annotations**,并明确**权威闸门是 CI**(后端 2045 例 / 闭环 42/44 / RBAC 42/42 / 哨兵 6/6 每次 push 重跑)。**⑨沙箱二次重置的处置**:本地 clone 被重建到基线 commit(2afd3b9)而远端分支仍在 2f87321,用 `git fetch` + `git reset --mixed origin/<branch>` 复位(**不动工作树文件、不 force-push**),远端历史与本轮未提交改动均无损。**⑩最后一个"只靠人工"的验证面进门禁**:发布回退(DoD#6)——e2e 腿把 `origin/main` 编成旧版、分支产物当新版,跑 `rollback-drill.sh` artifact 模式(切换→重启→探活→前滚,计时断言 <300s),版本身份仍用**契约差异**证伪(新版独有的 `/alerts/webhook/zabbix`:新版必须有、旧版必须没有;只看 HTTP 200 会误判——main 有 `/webhook/{system}` 通配映射,两版都回 200)。方案可行性先做了两项核对:main 与分支 `pom.xml` **逐行相同**(diff 0 行 → 可共用一份依赖 classpath)、迁移文件同为 18 个(→ 旧版跑同一个库不会被 Flyway 校验挡住;另备 `--no-flyway` 逃生口,本次未用)。脚本配套:`start_app` 支持各版本自己的资源目录(`--prev-res/--cur-res`)——配置也在版本里,分支 `application.yml` 比 main 多 33 行,拿新配置喂旧代码虽不报错但演的不是那一版。**CI 实测:回滚 12394ms(≈12s)/ 闸 300s,该步耗时 59s**(run 38669508214)。**最终验证:run 38669508214 三腿全绿**(e2e 腿现 21 步:哨兵 6/6 + 抓取面 4 断言 + 闭环 42/44 + RBAC 42/42 + 回退 12s + 浏览器 58 passed;后端 2045 例 0 失败;42 表)。**仍未闭环(如实)**:RAG 语义命中只在 REAL 模式成立(MOCK 下桩向量无语义,`--require-rag-hit` 打开才是真指标)。
- 批96(2026-10-15):**监控接线修复（把"监控自己失真"接上）+ 业务闭环再核查**。第②问决策(D-a 接线三件)当天实施并验证:**抓取面改令牌制**——新增 `ScrapeTokenGuard`(令牌只对 `/actuator/prometheus` 生效/默认关 fail-closed/常量时间比较/文件热读支持轮换) + `WebConfig` 接线;`prometheus.yml` 的 opsbrain job 补 `authorization.credentials_file`;compose 加 `scrape-token-init`(幂等生成,不进 Git) + app/Prometheus 两侧只读挂同一份文件;`scripts/monitoring-init-token.sh` 一键生成/轮换(应用与 Prometheus 都不需要重启)。**依赖规则补供给**:compose 补 postgres/redis exporter、MinIO 置 `MINIO_PROMETHEUS_AUTH_TYPE=public` 开放集群指标端点、prometheus 补三个 job——此前 `pg_up`/`redis_up`/`minio_*` 三条规则无数据源(不误报,也永远不响)。**看板死面板**:「诊断/工具时延」改查真指标 `opsbrain_diagnosis_seconds`,并把此前无人消费的 `opsbrain_diagnosis_queue_depth` 接上。**验证**:`ScrapeTokenGuardTest` 11 例 + `MonitoringConfigContractTest` 3 例本地实跑 **14/14**(jdk4py JRE + ECJ + GitHub .m2 镜像,ECJ 因 JRE 无 javac;证据 `.local-verify/evidence/local-guard-tests.txt`);CI 侧新增 `ActuatorScrapeTokenHttpTest` 6 例走**真实 HTTP**(401/200/越权路径/查询串/health 不受影响),跑在既有测试腿、不改 workflow;契约测试把「引用指标必须有供给方」「opsbrain job 必须有凭据」「三个 exporter job 必须存在」做成门禁。**演练新增 Z-监控面 哨兵**(`--scrape-token-file`:无凭据必须 401、带凭据必须 200 且含 `opsbrain_*`,不给参数记 SKIPPED),并给这个哨兵写了自测(桩服务四态)——**自测当场抓出新代码一个真 bug**(`ctx` 实为另一函数局部变量 → 带凭据抓取恒连接失败),修法=抽 `http_raw()` 模块级 helper 复用。**环境说明(如实)**:本轮沙箱在开跑后被重置,依赖池与运行时全失(与既有的 Maven Central 不可达两回事),已用 PyPI(jdk4py/pgserver/redislite)+GitHub .m2 镜像重建工具链;完整后端(Spring 上下文/HTTP/闭环演练重跑)本轮因离线依赖树未及重建,**改由 CI 的既有测试腿与 e2e 腿承担**,下一轮带 toolchain 时再跑闭环演练。
- 批95(2026-10-15):**D2 半天最小可关闭子集收口 + 三处演练抓出的行为缺陷修复**(验证方案 `docs/09-operations/业务闭环能力验证方案.md`,工具 `tools/verify/closed_loop_drill.py` / `tools/verify/rbac_walkthrough.py` / `scripts/rollback-drill.sh`)。**DoD 实弹**: DoD#6 版本回退 artifact 模式 **19s**(闸 300s,版本身份用**契约差异**证伪——只看 HTTP 200 会误判,main 的通配路由让两版都回 200);DoD#7 RBAC **42/42 格**(14 探针面×三角色,判据取自源码执法点)+ 前端角色矩阵 10 passed。**业务闭环实弹**: 三场景 42 跳断言(高危建单/观察窗降噪/历史回放),硬失败 0;另用「本机假接收端」把通知链最后一跳接上——真 P0 事件 → 触发卡+恢复卡 → **delivered=2/2**,送达判据从「未配置(未知)」变成可测口径。**三处修复(全部由演练抓出,都不是文案问题)**: ①`event_severity` 是**事件属性**却进了 labels 并参与去重身份——运维中途调触发器级别时 PROBLEM/OK 算出两个键 → 恢复配不上原告警、告警永久 FIRING(本轮实测复现;修法=从身份计算中排除,展示仍保留);②历史回放(超出观察窗回溯 24h)既不进观察窗也不被补单扫描捞起 → **静默无单**,值班人会当成漏单(修法=读路径派生 `unticketedReason`,列表/详情显式标注「不会再自动建单」);③渠道回执判据 `body.contains("errcode":0)` 是报文串匹配——等价的合法回执(多一个空格)被判「渠道拒收」(本机假接收端首跑即撞上),改结构化解析并补 7 例单测。**验证资产固化**: 三个此前散在会话里的脚本随本批入库并写进 `tools/verify/README.md`,演练的 F1/F2 断言直接进场景 A/C(防回退)。**同批工程收口**: `sync-openapi.mjs` 对 schema properties 做字典序规范化——springdoc 的反射顺序逐次不同,会让 `gen:api` 产物抖出 135 文件假 diff(真漂移被埋);规范化后连续两次同步 md5 一致。**测试**: 后端全量 **2024 例(2021 过 / 2 跳过 / 1 例 SSE 与并发后端抢端口判定偶发,单独复跑 12/12 过)**、前端五闸 exit 0(1844 例)、E2E 58 passed、CI 三腿绿(后端腿注解 `Tests run: 2011, Failures: 0, Errors: 0, Skipped: 2`)。**待外部条件(非工程缺口)**: 生产机 docker 模式回退一次、真钉钉渠道(送达率真数/ACK 演练×3)、REAL 模式检索命中抽查、Loki/Prometheus 真源——台账号已挂 `docs/09-operations/灰度试运行跟踪台账.md` §七。**同轮另交付两件**: ①告警指标维度扩展与监控体系建设决策 `docs/09-decisions/告警指标维度扩展与监控体系建设-2026-10-15.md`——结论是「先接线再扩维」: `tools/verify/monitoring_audit.py` 实跑点名 3 处硬缺口(抓取面无凭据恒 401→`up=0` 让 OpsBrainDown 常亮误报、看板 2 个死面板引用不存在的 `devops_*`、依赖类 3 条规则无 exporter 供给),15 条规则里今天真正能响的只有 5 条; ②全场景流程流转路线图 `docs/09-operations/业务场景流程流转路线图.md`——13 个业务场景逐条给触发/主链/状态机/交接点/断链表现/验证状态,并列出 15 个后台定时器与 8 个跨场景交接点风险。
- 批94(2026-10-14):**L2 第二个真实信号源——Zabbix 适配器全栈落地,CI 三腿全绿**(决策全文 `docs/09-decisions/多信号源接入-Zabbix适配器-2026-10-14.md`,操作手册 `docs/09-operations/告警接入命名规范.md` §七)。**判据落地**:78 号文档「出现第二个真实接入源时再抽中性模型」——接缝三件新增(`ZabbixSourceAdapter` 归一化 / `AlertSourceNormalizer` 按 supports 选源并列出已注册来源 / `DTO/ZabbixWebhook` 形状不收窄)+接口加 `sourceName()`,核心链零改动。**字段映射**:trigger_name 优先告警名、标签 service 优先于 host、`host/instance/trigger_id` 进 labels、`event_name/description/item_value/opdata` 按 `AlertAnnotationKeys` 字典进注解、**字典键不进 labels**(labels 参与 SHA-256 去重键,文案变动会堆出同源多条且不报错)。**级别 6→5**:Disaster/High/Average/Warning/Information/Not classified→P0/P1/P2/P2/P4/P4,英文/数字/中文三形态与大小写下划线变体都认,认不出**原样透传**(猜 P0 制造误报、猜 P4 漏真故障)。**三处「不许猜」**:时间(`event_date`+`time` 是无时区墙钟,**默认按部署时区**解释、可配 `devops.alert.zabbix.timezone`;解析失败交 null 由核心链标「估算」,**绝不用收到时刻冒充**);去重指纹=`zabbix:<trigger_id>@<host>` **不含 event_id**(真样本 PROBLEM 12002061/OK 12002062 是两条事件,混进指纹会让恢复配不上原告警,表现为「恢复了但告警一直挂着」);适配器不注入 `system` 标签,由核心链回落 `default`(**不是 zabbix**——来源与业务系统两码事,混了按 system 统计全错)。**端点差异有意**:`POST /api/v1/alerts/webhook/zabbix` 负载不认识→**400**(Zabbix 会把动作失败写进事件日志,接入自测即发现;Alertmanager 侧因会给非 200 重投而一律 200),空负载 `{}`→200(媒介类型「测试」按钮),鉴权复用同一 `WebhookGuard`(token/HMAC/限流)。**契约**:body 收 `Map`(springdoc 内联对象,不新增命名 schema)→快照只加一条 path/method 并做**双向自检**(live=新快照绿;摘掉新端点必红且点名);前端生成物随快照刷新(`ReceiveBody`,其余 212 个零漂移=手改快照与活体导出口径一致的实证)。**七处缺陷(全都不报错,详见决策文档 §十)**:①stringify List 缺外层 `[]`②标签合并顺序反(host 盖掉 event)③只认 `event_opdata`④日期只认 ISO 短横线(**Zabbix 默认 `2026.10.13` 点分隔,所有真实事件的时间都解析不出来**)⑤两处 catch 只吞不打⑥`endsAt` 恒为事件时间(**触发中的告警也带结束时间,持续时长算成 0**)⑦漏 import 致编译失败。其中①~⑤由本地闸抓、⑥⑦由 **CI 首轮真跑**抓——本地闸再密也替代不了真编译器。**本地六道闸(全部固化成可复跑的脚本)**:tree-sitter 语法 0 错 / 规则镜像 74 项断言(**含与 Java 源码形态表、尾参三元表达式对账**——镜像不对账等于在核对另一份规则,这条教训来自缺陷④⑥)/ 快照门禁双向 / 静默失效扫描(`样本.replace("A","B")` 的 A 必须是子串)/ 样本变异合法性(变异后必须仍是合法 JSON,否则测试死错地方)/ import 可解析性(tree-sitter 查不出「用了没 import 的类」)。**测试**:适配器 27 例 / 选源 6 例 / 端点契约 8 例 / 服务跨源入口 3 例;后端全量 2011 例、前端五闸、E2E 冒烟**全绿**。

- 批146(2026-10-17):HealthCheckController 原始 `Map<String,Object>` 响应全部 Record 化（`/health`、`/health/ping`、`/health/db`、`/health/ai-model`），仅固定响应结构，不调整路径、HTTP 状态、探针频率语义或付费模型开关。AI 探测的成功/失败子结构用字段可空 Record + `NON_NULL` 精确保留旧 JSON 字段形状；补 ping/disabled 探测的 HTTP 契约断言。OpenAPI/Orval/fieldContract 同步；前端 `gen:check`、`typecheck` 与 Vitest 107 文件/1863 测试通过。后端编译、完整测试和探针 HTTP 行为以当前 PR CI 真跑为合并门禁。
- 批147(2026-10-17):清理 `ServiceOwnerListResponse.items` 的嵌套 Map 契约。仓储 `listAll()` 改为显式 `ServiceOwnerRow`（时间列映射 `LocalDateTime`），Controller 转为 `ServiceOwnerItem` Record；响应 JSON 字段名和值保持不变。更新 OpenAPI 快照、Orval 类型、`fieldContract.ts` 双向字段断言及 `OpenApiSchemaIntegrationTest` schema 字段断言，新增 Controller 记录映射单测。前端类型与生成新鲜度、后端编译/测试、OpenAPI 与 E2E 均由 PR #56 CI 验证通过并合入 main（commit `68d212c`）。
- 批148(2026-10-17):将 `KnowledgeBaseReindexResponse.failures` 从嵌套 `List<Map<String,Object>>` 收口为 `KnowledgeBaseReindexFailure` Record，并把 `KnowledgeDocService.reindexByKnowledgeBase` 的内部统计/失败结果一并强类型化，去掉 Controller 中 unchecked Map 转换；保持失败列表字段 `docId/title/indexStatus/error` 与原值一致。同步 OpenAPI/Orval/fieldContract；`KnowledgeBaseControllerWebTest` 改用 typed service result，`OpenApiSchemaIntegrationTest` 锁定失败项 schema。前端 typecheck 与 CI 前端/后端/OpenAPI/工作流/E2E 全绿；PR #57 已 squash 合入（merge commit `7aabbe7`）。
- 批149(2026-10-17):将 `HealingExecutionDetailResponse.steps` 由 `List<Map<String,Object>>` 改为 `List<HealingExecutionStep>`；保留 `name/status/detail/at` 四字段、损坏 JSON 降级空列表行为，并对未知旧字段容错。同步 OpenAPI 快照、Orval 与 `fieldContract.ts` 双向 key assertions；新增 controller 单测覆盖标准回放与损坏回退，OpenAPI 集成测试锁定 schema。前端生成/类型检查通过，CI 前端/后端/OpenAPI/工作流/E2E 全绿；PR #58 已 squash 合入（merge commit `c7709c8`）。
- 批150(2026-10-17):诊断回放 API 强类型化。session/evidence/hypothesis 由 Map 改成 DTO Records；三个 Repository 回放查询改 RowMapper typed rows，保持原 SQL 列名序列化为 snake_case、原排序/字段值及 null 字段；无 session 采用空对象 record union 保留 `{}` 响应。同步 OpenAPI/Orval 与 `fieldContract.ts` 三组 key 双向断言；补 controller 序列化断言（snake_case + 空对象）、活体 OpenAPI schema 属性/oneOf 断言，更新仓储/Controller 测试。前端 typecheck、后端编译/2109 项测试、OpenAPI 新鲜度、工作流和 E2E 均由 PR #59 CI 验证通过并合入 main（merge commit `056f6b9`）。
- 批151(2026-10-17):将 `GET /api/v1/tickets/metrics/closure/missing-postmortem` 的 repository/ service/ controller 内部 `Map<String,Object>` 数据流改为 `MissingPostmortemRow` record + RowMapper。API 仍返回现有 `MissingPostmortemItemDto`，不改 JSON 字段、时间表示、排序、limit 钳制、空结果语义；新增 WebMvc 契约用例锁定数据值。无 OpenAPI/Orval 变更，因为外部 DTO schema 未改变；PR #60 前端、后端、工作流与 E2E 全绿并合入（merge commit `b6a97c9`）。
- 批152(2026-10-17):审批角标 Controller 改用 `ApprovalService.listPendingPage(1,1)` 的 typed `ApprovalPageResponse.total()`，移除 `Map<String,Object>` 解包与不必要数字类型分支；保持 size=1、pending 数量及 null page→0 行为。更新 HTTP 契约测试，外部 `PendingApprovalCountResponse` schema 未改变，不需 OpenAPI/Orval 更新；PR #61 前端/后端/工作流/E2E 全绿并合入（merge commit `2681823`）；后续批153继续移除 Service 层分页 Map wrapper。
- 批153(2026-10-17):将 `ApprovalService.listPending/listByStatus` 遗留 `Map<String,Object>` wrapper 改为直接返回 `ApprovalPageResponse`，删除通用 Map 分页构造器；Controller 可用的分页 API 仍统一字段类型，原分页钳制/排序/count SQL 不变。更新 service test 从 map key 断言改为 record accessor；外部 API schema 不变，无需生成物变更。PR #62 首轮 E2E 失败后由新 CI run 全绿复验，前端/后端/工作流/E2E 全通过并合入（merge commit `2e7f045`）。
- 批154(2026-10-17):将 KnowledgeCategoryRepository.findDocuments、KnowledgeCategoryService.tree 及 Controller 适配链上的 Map<String,Object> 改为 `KnowledgeCategoryTreeData` typed records；保留按 categoryId/大小写无关 category 名归组、uncategorized 顺序、分类排序、文档顺序与 DTO JSON。新增 service 分组单测，更新 Controller HTTP 测试 stub。外部 `KnowledgeCategoryTreeResponse` 未变；前端 `typecheck`/`gen:check` 本地通过，PR #63 前端/后端/工作流/E2E 全绿并合入（merge commit `0155728`）。
- 批155(2026-10-17):`KnowledgeDocRepository.findCategories` 从 `List<Map<String,Object>>` 改为 `List<KnowledgeCategoryCount>` typed row；service/controller 同步移除 map key 读取与 Number 转换，仍输出既有 `KnowledgeCategoryCountResponse(name,count)`。SQL filter/group/order 不变；新增 controller HTTP 响应字段断言。外部 schema 不变，无 OpenAPI/Orval 更新；PR #64 全部 CI 通过并合入（merge commit `a9be979`）。
- 批156(2026-10-17):将 `KnowledgeBoostRepository.docHealthReport()` 的 `List<Map<String,Object>>` 改为 `List<DocHealthRow>` RowMapper，Controller 使用 typed fields 计算净反馈并映射原有 `KnowledgeDocHealthResponse`；保留 SQL 分组/排序、null 返回空列表、`net=wrong-helpful` 与 JSON 字段语义。新增 WebMvc 响应值断言；外部 DTO 未变，不需 OpenAPI/Orval 更新。本地后端未能编译，PR #65 CI 后端/前端/工作流/E2E 全通过。
- 批157(2026-10-18):将 `SearchController` 三类全局搜索的 JDBC `queryForList`/Map key 解包改为直接 RowMapper 构造既有 `SearchResultItem`；保留查询条件、各类别 LIMIT/排序、合并顺序、总量截断及 null→空字符串语义。新增 WebMvc 测试锁定 HTTP 字段和类别顺序；外部 DTO schema 未变，无需 OpenAPI/Orval 更新。本地因无 Java 且 Maven Wrapper 下载失败未能运行后端测试；PR #66 后端、前端、工作流与 E2E CI 全通过并合入（merge commit `ecff86c`）。
- 批158(2026-10-18):`KnowledgeStatsService.getStats()` 与 `KnowledgeManageController` 由 `Map<String,Object>` 固定字段解包改为 `KnowledgeStatsData`；来源名为动态键的 `bySource` 继续保留 `Map<String,Long>`。统计查询、active/filtered 计算、空 bySource fallback 与 `KnowledgeStatsResponse` JSON 字段保持不变；WebMvc 测试更新为 typed stub 并断言统计值/动态来源键。无外部 schema 变化；PR #67 后端、前端、工作流与 E2E CI 全通过并合入（merge commit `69d680e`）。本地尝试下载 Temurin JDK 21（GitHub release asset），但重定向到 `release-assets.githubusercontent.com` 被环境网络拒绝；Maven Wrapper 下载亦失败。
- 批159(2026-10-18):将 `KnowledgeStatsService.listChunks()` 的分页 wrapper 从 `Map<String,Object>` 改为 `KnowledgeChunkPageData`，Controller 直接映射 typed fields 到既有 `KnowledgeChunkPageResponse`。查询筛选、分页钳制、参数透传和输出 page/size/total/list 字段保持不变；WebMvc 测试改 typed stub 并锁定分页 JSON。外部 schema 不变，无需 OpenAPI/Orval 更新；PR #68 前端、后端、工作流与 E2E CI 全绿并合入（merge commit `1e85f0f`）。
- 批160(2026-10-18):`DevOpsTicketRepository.countFirstResponseStats()`、`TicketService.getFirstResponseStats()` 与 Controller 的固定 Map 数据流改为 `TicketFirstResponseStats` typed record。SQL 条件及有记录才计算均值、NULL MTTA 保持 null、平均值一位小数舍入与异常回退零计数/null 均值不变；新增 HTTP 契约断言。响应 DTO/schema 未变，无 OpenAPI/Orval 更新；PR #69 前端、后端、工作流与 E2E CI 全通过并合入（merge commit `93488bd`）。
- 批161(2026-10-18):将 `DevOpsTicketRepository.countClosureMetrics()`、`TicketService.getClosureMetrics()`、`TicketController.closureMetrics()` 的固定 Map 数据流改为 `TicketClosureMetricsData`。保留 SQL 聚合、null 均值/比例语义、舍入、postmortemRate 分母为 0 时 null、days/window/alertRepository 分支条件、缺行/异常回退及 JSON 全部字段；新增 HTTP typed projection 响应断言。对外 DTO/schema 未变，无需 OpenAPI/Orval 更新；PR #70 后端、前端、工作流与 E2E CI 全通过并合入（merge commit `a42a650`）。
- 批162(2026-10-18):将 `TicketAiAnalysisRepository.feedbackStats()` 的固定聚合 Map 改为 `TicketAiFeedbackStats`，Service 计算层改为 `TicketAiAccuracyStats` typed record，Controller 直接映射到原有响应 DTO；保留计数缺行→0、rated=0 rate→0、三位小数舍入和反馈统计 SQL；更新服务测试并新增 HTTP 字段断言。外部 schema 不变，无 OpenAPI/Orval 更新；PR #71 前端、后端、工作流与 E2E CI 全通过并合入（merge commit `090f829`）。
- 批163(2026-10-18):将 `TicketService.getTicketStats()` 固定外层 `Map<String,Object>` 改为 `TicketStatsData`，保留 `byStatus`/`byPriority` 为动态 `Map<String,Long>`；仍为已知状态与优先级补 0、resolved 合并 RESOLVED+CLOSED、其他 KPI 计数不变。Controller 由 typed fields 映射原 `TicketStatsResponse`，服务测试改 record accessor 并新增 HTTP 字段/动态键断言；无外部 schema 变化，PR CI 待验证。
