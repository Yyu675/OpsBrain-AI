# 72 · 可插拔接缝落地：向量检索与 LLM 配置

> 承接 `71-模块可插拔化设计与迁移方案.md` 的接缝优先级清单，本轮执行 P1 两项，
> 并顺带清偿基线里排第一的测试欠账（`SagaCompensationManager.compensateStep`）。

---

## 一、本轮做了什么

| # | 事项 | 类型 | 提交 |
|---|------|------|------|
| 1 | 抽出 `Retriever` 接口，向量检索后端可替换 | 重构 | `e0919c2` |
| 2 | LLM 端点配置收敛为 `LlmEndpointSpec` + 单一协议工厂 | 重构 | `fc9dee0` |
| 3 | Saga 补偿编排器 21 例测试，并修复「单步异常中断整批补偿」 | 测试+缺陷修复 | `68e75c0` |
| 4 | 注入 5 个缺陷验证测试检出能力 → 全部命中 → 还原 | 验证 | `c30f766` / `edad24f` |

新增测试 **49 例**（Retriever 契约 11 + LLM 配置 17 + Saga 21），后端总量 930 → 979。

---

## 二、P1-1 向量检索抽 `Retriever` 接口

### 改动前的问题

`DevOpsTools` 直接注入 `HybridRetrieverService`——一个和 pgvector 绑死的实现
（内部是 `JdbcTemplate` 直查 `sys_knowledge_chunk`，SQL 里有 `embedding <=> ?::vector`）。
想换 Milvus / Qdrant 就得改 AI 工具层，而工具层本该只关心「给我几段相关知识」。

### 接口边界怎么划的

这比接口本身更重要。一个抽象只有在**它挡住的东西真的换得掉**时才有价值：

| 决定 | 理由 |
|------|------|
| 入参只有 query / topK / scope | 三者在任何向量库上都有对应概念（query / limit / filter） |
| 出参是 `RetrievedChunk` | 标题/章节/正文/得分，各家共通的返回形状 |
| **不**暴露 SQL、JdbcTemplate、Embedding、距离算子 | 一旦泄漏，换实现时上层照样得改，接口就白抽了 |
| **相似度阈值留在实现内部**，不上提为参数 | 不同后端打分口径不同（余弦 / 内积 / BM25 混合）。让上层传 `0.73` 过去，换后端时这个数字的含义会静默改变——「数值还在、含义变了」这类缺陷极难发现 |
| 新增 `backend()` 标识 | 灰度迁移期两套库同时在线，日志里没有它就分不清一条结果来自哪套存储 |

### 契约测试守的是什么

`RetrieverContractTest`（11 例）守的不是某个实现，而是**换实现不会出事**。
最关键一条：**「无结果」与「服务不可用」必须给出不同返回值**（空列表 vs `null`）。

历史上二者被混为一谈，用户看到的是「知识库暂无相关文档」，
运维于是去补一份**库里本来就有**的文档，真正的存储层配置错误无人察觉。

用**遍历式**而非逐实现写：下一个后端接上来时没人会想起补测试——
而那正是契约最易被破坏的时刻（新后端刚接上，大家关心的是「能查出东西吗」，
不会有人去验「查不动的时候返回的是 null 还是空列表」）。

制造故障态的方式与实现绑定，因此由每个实现自己提供「故障态工厂」
（`Candidate` record 里的三个 `Supplier`），契约用例只管断言**返回值语义**。

---

## 三、P1-2 LLM 配置收敛

### 改动前的问题

`AiModelConfig` 的 5 个 Bean 方法各自手写一遍
`baseUrl / apiKey / modelName / timeout / maxRetries / logRequests`——同样六行复制五份。

真正的代价不是重复，而是这类配置错误的共同特征：
**启动时一切正常，用起来才炸，且报错位置离原因很远**。

| 错法 | 表现 | 为什么难查 |
|------|------|-----------|
| Embedding 漏传 `dimensions` | 写库时 `expected 1536 dimensions, not 3072` | 日志照常打印「输出维度 1536」，排查者先去怀疑建表脚本 |
| reasoner 超时没翻倍 | 只有「复杂堆栈分析」超时，普通对话正常 | 容易被归因成「那个问题太难了」 |
| apiKey 进日志 | 完全没有报错 | 直到密钥被滥用 |

### 收敛结构

```
application.yml
    ↓  (AiModelConfig.turboSpec / reasonerSpec / embeddingSpec —— 纯函数，可单测)
LlmEndpointSpec           厂商中性：地址/凭据/模型名/超时/重试/维度
    ↓  (OpenAiCompatibleModelFactory —— 协议细节的唯一去处)
ChatModel / StreamingChatModel / EmbeddingModel
```

固化到代码里的三条规则：

1. `REASONER_TIMEOUT_MULTIPLIER = 2` —— 以前是裸的 `timeout * 2` 出现在两个方法里；
2. `LlmEndpointSpec.embedding(...)` **强制**传维度，构造期校验；
3. `.streaming()` 把重试数**显式归零** —— LangChain4j 1.1.0 的流式 builder
   根本没有 `maxRetries`，留个非 0 值会让配置**说谎**。

另外覆盖了 record 默认 `toString`：默认实现会把 apiKey 原样打出来，
任何一处 `log.info("spec={}", spec)` 都会把密钥写进日志且毫无征兆。

### 为什么协议工厂不抽接口

现在只有一种协议在用。过早抽接口会得到一个只有单实现、又挡不住任何东西的间接层，
真到要换的那天接口形状多半也不合适。当前收益最高的一步是
**「协议细节只出现在一个文件里」**，需要多协议并存时再抽，那时才知道该抽成什么形状。

---

## 四、发现并修复的真实缺陷：Saga 单步异常中断整批补偿

### 成因

`SagaCompensationManager` 类注释写着

> 规则 2 **尽力而为**：单步补偿失败不中断后续补偿，最大化清理脏数据

但**没有任何代码保证它**。`compensateStep` 内部的 `try` 只包住了补偿动作调用，
而它之前的 `execRepo.updateState(id, COMPENSATING)` 在 `try` 之外。

### 用户可见后果

数据库瞬断时那一行会抛出，异常一路冒出 `compensateSaga` 的 for 循环——
后面几步的脏数据（如已建但该作废的工单）**再也没人清理**，
且补偿结果里也看不出它们被漏掉，只报了抛异常的那一笔。
运维按结果去人工处理，会漏掉真正残留的那几条。

补偿失败本就无声（主流程早已返回、用户毫无感知），这种漏报使它更难被发现。

### 修法

把 `compensateStep(record)` 包进 try-catch，意外异常按失败计并继续下一步。
——把注释里的约定变成代码里的保证。

---

## 五、注入-还原验证（硬纪律）

新写的测试若抓不到人为缺陷，就等于没写。本轮一次性注入 5 个缺陷（提交 `c30f766`），
CI 全红后还原（`edad24f`，与注入前逐字节一致）。

| 注入项 | 是否被抓到 | 报错信息 |
|--------|-----------|---------|
| D1 检索向量化失败降级为空列表 | ✅ | 「向量化链路故障必须返回 null…」expected `<null>` but was `<[]>` |
| D2 reasoner 超时不翻倍 | ✅ | expected `<120000>` but was `<60000>`（命中 2 条用例） |
| D3 apiKey 进 `toString` | ✅ | expected `<false>` but was `<true>` |
| D4 Saga 单步异常中断整批 | ✅ | `RuntimeException: 库连接断了` 冒出测试方法 |
| D5 反射根因不解包 | ✅ | 「落库的失败原因必须包含业务消息，实际=InvocationTargetException」 |

**5/5 命中，且每条报错信息都直指真正的原因**——这一点同样重要：
测试失败时若只有 `expected true but was false`，排查者还得回头读代码才知道坏在哪。

---

## 六、可插拔进度

`71` 号文档定义的接缝优先级清单执行情况：

| 优先级 | 接缝 | 状态 |
|--------|------|------|
| P0 | 通知渠道 `Notifier` | ✅ 上轮完成（`NotifierContractTest` 7 例） |
| P1 | 向量检索 `Retriever` | ✅ **本轮完成**（契约测试 11 例） |
| P1 | LLM 配置收敛 | ✅ **本轮完成**（17 例） |
| P2 | 告警来源接口 | ⏸ 标为**中风险**——Alertmanager/Zabbix/云监控字段差异大，中性模型易退化为最小公倍数丢信息 |
| — | 工单存储 | ❌ **明确排除**：不会换 PG，抽了是无用间接 |

`domain` 层的 interface 数量：**1 → 3**（`OpsExecutor` / `Notifier` / `Retriever`）。
这个数字本身说明了 `71` 号文档那条结论的分量——「整个 domain 层只有 1 个 interface」
才是「换实现要改一堆类」的根因，而不是模块划分不好。

---

## 七、下一步建议

1. **`ToolExecutionRepository` 的 12 个写方法** —— 全部 `catch(Exception) return 0`，
   审计写失败时静默。基线里还有此类记账，值得单独一轮排查「哪些吞异常是对的、哪些不是」；
2. **`KnowledgeIngestionService` 摄取链路补测试** —— 基线「确属缺口」里排第二；
3. **P3 Flyway 接管 27 个手工 SQL** —— 仍受阻于无 `.github/workflows/` 写权限；
4. `run_audit.py` 接入 CI —— 同样受阻于 workflows 权限。

第 3、4 项需要用户在 GitHub 上补一次 workflows 权限，或由用户手工改一次 `ci.yml`。
