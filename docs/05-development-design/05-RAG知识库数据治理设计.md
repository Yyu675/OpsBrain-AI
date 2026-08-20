# 🧹 OpsBrain AI —— RAG 知识库数据治理设计说明书

> **文档定位**：系统性回答「RAG 知识库的增删改、版本、清洗去重、更新时机」应当如何设计。梳理现状、给出三个已拍板决策的详细设计、输出改造清单。
> **决策状态**：三个核心决策已由用户拍板（2026-08-11），本次**仅产出设计文档，不涉及代码改动**；实施按改造清单分 P1/P2 推进。
> **配套阅读**：生命周期语义常量见 `domain/rag/KnowledgeDocLifecycle.java`；切片/向量化执行见 `domain/rag/DocumentIndexer.java`；表结构见 [数据库设计](04-数据库设计.md) 与 `sql/migration_v8_knowledge_doc.sql`。

---

## 一、问题域与设计目标

RAG 的回答质量上限 = 知识库质量。检索到脏数据、重复数据、过期版本，会直接拉低回答准确率，且失败方式往往伪装成「知识库暂无相关文档」（见 6.20 的教训——链路故障被伪装成正常业务响应）。

本设计回答五组问题：

| # | 问题 | 对应章节 |
| :---: | :--- | :--- |
| 1 | 数据清洗去重要做到什么深度？ | 六、更新与去重设计 |
| 2 | 新增 / 修改 / 更新 / 删除如何设计更合理？ | 四、五、六 |
| 3 | 新旧版本都保存，还是删旧留新？ | 四、版本管理设计 |
| 4 | 什么时候删除？什么时候保留？ | 五、删除策略设计 |
| 5 | 知识库实时更新还是定时更新？ | 六、更新与去重设计 |

---

## 二、现状盘点（6.20 / 6.21 已有能力）

| 能力 | 现状 | 实现位置 | 评价 |
| :--- | :--- | :--- | :--- |
| 文档级去重 | content_hash 精确拒绝 + SimHash 近似告警（阈值 10） | `KnowledgeDocService` / `ContentFingerprint` | ✅ 完备 |
| 生命周期状态机 | DRAFT / PUBLISHED / DEPRECATED / ARCHIVED（预留） | `KnowledgeDocLifecycle` | ✅ 语义完备 |
| 索引状态分离 | PENDING / INDEXED / FAILED / SKIPPED | `KnowledgeDocLifecycle` | ✅ 正确区分「已发布」与「可检索」 |
| 更新时机 | 同步全量重建 + 超时降级 PENDING + 补偿任务 | `DocumentIndexer` / `reindex/pending` | ✅ 合理 |
| 版本管理 | 当前带向量 + 历史只存原文 | `sys_knowledge_doc_history` | ✅ 方向正确 |
| 缓存一致性 | 知识变更后清空语义缓存 | `KnowledgeDocService` 各写路径 | ✅ 已接入 |
| 切片级去重 | 仅本文档内 sha256 去重 | `DocumentIndexer.indexTask` | ⚠️ 缺跨文档 |
| 数据清洗 | 无 pipeline | — | ❌ 缺失 |
| 定时清理 | 无 | — | ❌ 缺失 |

**三个真实缺口**：跨文档切片去重、数据清洗 pipeline、定时清理（含孤儿向量对账）。

---

## 三、五条核心设计原则

1. **向量是派生物，正文是本体。** 1536 维向量 ≈ 6KB/切片，比正文大两个数量级。历史只存原文，向量按需再生——这是版本、删除、回滚全部决策的基础。
2. **历史版本永不参与检索。** 同一知识的新旧说法同时命中 topK 会互相矛盾；检索只面向当前 PUBLISHED。
3. **删除必须留审计窗口。** 运维知识的价值在积累（误删即流失）；默认「删除」= 废弃（留正文删向量），物理删仅限合规场景。
4. **更新单位是文档级全量重建。** 切片级 diff 不可行（6.21 已论证：文档中间插一句话，其后所有切片起止位置整体漂移）；代价靠 content_hash 前置判断控制（内容未变则零 API 调用）。
5. **检索一致性靠两件事。** ① `status` 与 `index_status` 两个维度分离，杜绝「已发布」暗示「可检索」；② 知识变更即清空语义缓存，杜绝旧答案继续命中。

---

## 四、版本管理设计（已拍板：当前带向量 + 历史原文）

### 4.1 数据分布

| 表 | 内容 | 是否参与检索 |
| :--- | :--- | :--- |
| `sys_knowledge_doc` | 当前版本正文 + `content_hash` / `simhash` / `index_status` / `chunk_count` | ✅ 当前 PUBLISHED + INDEXED |
| `sys_knowledge_doc_history` | 历史版本原文（含 content_hash） | ❌ 永不 |
| `sys_knowledge_chunk` | 当前版本的切片 + 向量（`doc_id` / `content_hash` 关联） | ✅ |

### 4.2 边界规则（确认既有行为并冻结）

| 规则 | 说明 | 依据 |
| :--- | :--- | :--- |
| 检索范围 | 只查当前 `PUBLISHED` 且 `index_status = INDEXED` | 原则 2 |
| 历史只存原文 | 不存向量，回滚时按需重建 | 原则 1 |
| 回滚 = 重新提交 | 历史内容作为一次新的 update（version 递增），而非倒退版本号——倒退破坏 CAS 且丢失「曾回滚过」事实 | 6.21 |
| version 只代表内容版本 | 仅 `update`（内容/元信息变更）递增；发布/废弃/回滚不递增 | 6.21 已冻结 |
| 零成本判断 | 内容未变（content_hash 相同）则跳过重建向量 | 原则 4 |

**结论**：方案 A 自洽且已被 6.21 实现验证，**无需改动**。回滚重建向量的唯一成本窗口已由「内容未变跳过」约束到最小。

---

## 五、删除策略设计（已拍板：保留期分层 + 定时清理）

### 5.1 生命周期状态机

```
DRAFT ──发布──▶ PUBLISHED ──废弃──▶ DEPRECATED ──归档──▶ ARCHIVED ──物理删──▶ （删除）
                  │                    │                        │
                  └──────── 恢复(restore=重新发布) ◀───────────┘
```

| 状态 | 正文 | 向量 | 参与检索 | 何时进入 | 何时离开 |
| :--- | :---: | :---: | :---: | :--- | :--- |
| `DEPRECATED` | 保留 | 删 | 否 | 用户点「删除」（5 秒 UX 撤销）| 保留期满 → 归档；或 restore |
| `ARCHIVED` | 转对象存储 | 已删 | 否 | 废弃保留期满（定时任务）| 归档保留期满 → 物理删 |
| 物理删 | 无 | 无 | 否 | 归档保留期满 / 合规强制 | 不可逆，留审计 |

### 5.2 何时删除、何时保留（决策表）

| 场景 | 处置 | 理由 |
| :--- | :--- | :--- |
| 内容过时 / 被新文档替代 | **废弃**（默认） | 运维知识价值在积累，正文留作历史查阅 |
| 内容确定错误 / 必须彻底清除 | **物理删**，强制 `complianceReason` | 审计举证；错误知识不应残留误导 |
| 用户误删 | 5 秒 undo + 系统保留期 | 撤销窗口 + 审计窗口双层保护 |
| 容量压力 / 冷数据 | **归档**（正文转对象存储）| 热库只放活跃知识 |
| 合规要求删除 | **物理删**，记审计 | 举证可用，不可逆 |

### 5.3 定时清理任务（三个 ScheduledTask，本期新增）

| 任务 | 触发 | 动作 | 失败处理 |
| :--- | :--- | :--- | :--- |
| `DeprecateToArchiveJob` | 每日低峰 | `status=DEPRECATED` 且 `update_time < now - 废弃保留期` → 归档：正文转对象存储、写入 `archive_path`、状态置 `ARCHIVED` | 单条失败仅记 ERROR，不中断批次 |
| `ArchivePurgeJob` | 每日低峰 | `status=ARCHIVED` 且 `archived_at < now - 归档保留期` → 物理删（级联历史/标签/切片），记审计 | 同上 |
| `OrphanVectorCleanupJob` | 每小时 | `sys_knowledge_chunk` 中 `doc_id` 不在 `sys_knowledge_doc` 的孤儿向量 → 删除 | 幂等，仅告警 |

> **孤儿向量为什么需要对账**：`DocumentIndexer.removeVectors` 是尽力而为（失败仅告警），doc 物理删除或崩溃时可能残留 chunk；定时比对是最后防线。

### 5.4 保留期参数（可配置，默认值待拍板）

```yaml
devops:
  knowledge:
    retention:
      deprecated-days: 90      # 废弃后保留多少天再归档
      archived-days: 365       # 归档后保留多少天再物理删
    cleanup:
      enabled: true            # 定时清理总开关
      cron-deprecate: "0 30 3 * * ?"   # 每日 03:30
      cron-orphan: "0 0 * * * ?"       # 每小时
```

---

## 六、更新与去重设计（已拍板：现状 + 跨文档切片去重）

### 6.1 更新策略（确认现状，补批量导入异步化）

| 路径 | 策略 | 说明 |
| :--- | :--- | :--- |
| 单篇手动发布/更新 | **同步 + 超时降级**（现状）| 发布即生效；单篇实测 ~11.6s（2 文档 29 切片）可接受；超时降级 PENDING，由 `reindex/pending` 补偿 |
| 批量导入（多文档） | **异步批量任务**（本期新增）| 几十篇同步向量化会阻塞接口；走独立线程池 + 状态回查 |
| 定时补偿 | `reindex/pending`（现状）| 一次网络抖动不应让文档永久不可检索 |

> **切片级增量 diff 不纳入**：文档中间插一句话，其后所有切片起止位置整体漂移，新老切片无法对应（6.21 已论证）。更新单位恒为**文档级全量重建**。

### 6.2 文档级去重（已有，确认）

| 关卡 | 机制 | 处置 |
| :--- | :--- | :--- |
| 精确 | content_hash 部分唯一索引（仅 DRAFT/PUBLISHED）| 拒绝，40021 + `duplicateDocId` 供前端跳转 |
| 近似 | SimHash 汉明距离 ≤ 10（实测标定：应判重复上界 7、应判不同下界 24）| 告警不阻断，`nearDuplicates` 返回前端 |

### 6.3 跨文档切片去重（本期新增）

**问题**：多个文档含同一段 SOP（如「重启服务步骤」被多篇手册重复粘贴），检索时 topK 会被同内容段落反复占满，模型看到的信息量实际只有 1 段。

| 方案 | 做法 | 优点 | 缺点 |
| :--- | :--- | :--- | :--- |
| **A. 检索结果去重（推荐，必做）** | `HybridRetrieverService` 返回前按 `content_hash` 去重，保留每段的一个副本 | 零入库侵入；成本极低；不改切片数据 | 仍占用检索开销（先取回再去重） |
| B. 入库跨文档去重 | 切片写入前查库中已有 content_hash，重复则跳过 | 省向量存储与检索开销 | 重复段落可能来自不同文档且各自上下文不同，删除会丢归属；需处理「后建文档引用先建切片」的链接问题 |
| C. 方案 A + 可选 B（开关） | 默认 A，B 作配置开关 | 兼顾 | 实现量最大 |

**推荐 A**：检索侧去重捕获主要收益（topK 不被重复段落占满），且不引入入库侧的一致性问题。B 作为后续可选项，通过 `devops.ai.retrieval.dedup-cross-doc` 开关控制。

### 6.4 数据清洗 pipeline（本期新增基础版）

摄取前对文档内容做清洗，**脏数据不进向量库**：

| 规则 | 处置 | 理由 |
| :--- | :--- | :--- |
| 空内容 / 纯空白 | 拒收（40001）| 零信息量切片纯浪费向量 |
| 纯符号 / 无有效字符 | 拒收 | 同上 |
| 乱码检测（非法 UTF-8、`�` 替换符密集） | 拒绝 + 明确提示 | 脏数据即错答案 |
| HTML 残留（`<div>` `<p>` 等标签）| 剥离为纯文本 | 从网页/富文本导入的常见形态 |
| Markdown 规范化 | 去多余空行、统一标题层级、剥离图片引用 `![...]` | 降低切片噪声 |
| 连续重复粘贴段落 | 检测相邻重复块并告警（不自动删，人工确认）| 自动删可能误伤 |

> **清洗是前置校验而非后置修复**：`KnowledgeDocService.create/update` 的 `validateForSave` 中扩展校验；清洗失败拒绝入库，不给用户「已保存但不可用」的中间态。

---

## 七、改造实施方案（P1 / P2）

> 每个方向给出：目标 → 详细设计（类/SQL/配置/接口）→ 关键决策 → 风险规避 → 验证方式。**P1 三项可并行实施，互不依赖**；P2 依赖 P1 基础（存储、框架）。

---

### P1-1 检索结果 content_hash 去重

**目标**：topK 不被同一段 SOP 的重复段落占满（多篇文档含相同段落时，检索结果信息量 = 1 段）。

**详细设计**
- 改动点：`domain/rag/HybridRetrieverService.java` 的检索返回处（6.20 已改为 JdbcTemplate 直查，SQL 需带出 `content_hash`）
- 去重算法：**先多取再去重**——`maxResults` 扩大 3 倍查询 → 按 `contentHash` 用 `LinkedHashSet` 保留首个副本 → 截断回 `maxResults`。理由：只取 topK 再去重会少于 topK 条，模型上下文利用率下降
- 观测：返回结构带 `dedupedCount`（本次去重条数），日志 `[Retriever] 去重 N 条重复段落`；避免「去重无感」的静默行为
- 配置：`devops.ai.retrieval.dedup-result: true`（默认开，可关作对照）

**关键决策**

| 决策点 | 选择 | 理由 |
| :--- | :--- | :--- |
| 去重键 | `content_hash`（切片 sha256）| 精确可靠；检索 SQL 已能带出 |
| 去重时机 | topK×3 取回后、返回前 | 捕获检索侧全部收益；不触碰入库数据 |
| 跨 doc 同段落归属 | 保留首个命中（按相关度降序自然优先）| 模型引用的是最相关的文档出处，语义正确 |

**风险与规避**：无入库侵入，无数据风险。仅多查 3 倍候选（成本可控，maxResults 本就不大）。

**验证方式**：单测（构造 2 篇含相同段落文档 → 检索返回去重后仍满 topK 且无重复 hash）；`scripts/verify_knowledge_doc_api.sh` 增场景：两文档共享段落 → 检索命中各 1 个副本。

---

### P1-2 定时清理任务（KnowledgeRetentionScheduler）

**目标**：废弃文档不再永久堆积；孤儿向量不再残留（doc 已删但 chunk 残留的最终防线）。

**详细设计**
- 新增 `application/schedule/KnowledgeRetentionScheduler.java`（`@Scheduled`），三任务独立 `@Scheduled` 方法 + 独立事务
- 配置段 `devops.knowledge.cleanup`：`enabled`（总开关）、`cron-orphan`（孤儿对账，默认每小时）、`cron-scan`（超期扫描，默认每日 03:30）、`batch-size`（每轮处理上限 500，防长事务）、`archive-enabled`（归档动作开关，**P1 默认 false**）

**任务 1：孤儿向量对账（P1 实现，无依赖）**
```sql
-- 分批：先取游离 doc_id（chunk 里有、doc 表没有），再删除
DELETE FROM sys_knowledge_chunk
 WHERE doc_id IN (
   SELECT DISTINCT c.doc_id FROM sys_knowledge_chunk c
    LEFT JOIN sys_knowledge_doc d ON d.id = c.doc_id
    WHERE d.id IS NULL LIMIT 500)
```
幂等、每轮限量、清前计数记日志。孤儿成因：`DocumentIndexer.removeVectors` 尽力而为（失败仅告警）、崩溃中断、物理删异常。

**任务 2：超期废弃扫描（P1 先做扫描统计，归档动作等 P2-4）**
```sql
SELECT id, title, update_time FROM sys_knowledge_doc
 WHERE status = 'DEPRECATED' AND update_time < now() - interval '90 day'
 ORDER BY update_time LIMIT 500
```
- `archive-enabled=false`（P1）：仅统计 + ERROR/WARN 日志报告「N 篇废弃超期待归档」，不产生半成品行为
- `archive-enabled=true`（P2-4 落地后）：正文转对象存储 → 写 `archive_path`/`archived_at` → 状态置 `ARCHIVED`

**任务 3：归档超期物理删（依赖 P2-4，P1 仅保留框架）**
```sql
DELETE FROM sys_knowledge_doc WHERE status = 'ARCHIVED' AND archived_at < now() - interval '365 day'
```
级联清理历史/标签/切片（复用 `KnowledgeDocService.purge` 的级联逻辑），删前记审计（`complianceReason=归档超期自动清理`）。

**关键决策**

| 决策点 | 选择 | 理由 |
| :--- | :--- | :--- |
| P1 归档动作 | **扫描统计先行，归档动作留 P2** | 存储未落地就归档会产生「正文去了哪」的中间态；统计可先行暴露堆积量 |
| 处理批次 | 500/轮 + 独立事务 | 防长事务锁表；单条失败不中断批次（与 6.9 批量部分成功原则一致）|
| 自动物理删 | 必须 `archive-enabled` 双开关 + 审计留痕 | 不可逆操作需显式确认开启 + 举证 |

**风险与规避**：孤儿删除是 `DELETE`，靠 `LEFT JOIN ... IS NULL` 精确定位避免误删；批次限量防锁；总开关 `enabled=false` 可整体关闭。

**验证方式**：`scripts/verify_knowledge_retention.sh`——① 手工造孤儿 chunk（删 doc 留 chunk）→ 跑任务 → 确认清理；② 改 `update_time` 为 100 天前 → 跑扫描 → 确认统计日志；③ 幂等：重跑不重复删、不报错。

---

### P1-3 数据清洗基础版（KnowledgeContentCleaner）

**目标**：脏数据不进向量库（6.20 教训：检索到脏数据会伪装成「无相关文档」）。

**详细设计**
- 新增 `domain/rag/KnowledgeContentCleaner.java`，返回 `CleanResult(content, cleaned, reason)`（清洗后文本 + 是否被清洗 + 拒收原因）
- 接入点：`KnowledgeDocService.validateForSave` 中**前置调用**；拒收抛 `IllegalArgumentException` → Controller 40001
- 规则（**保守原则**：只剥离明确噪声，不删代码块/正文）：

| 规则 | 实现 | 处置 |
| :--- | :--- | :--- |
| 空 / 纯空白 | `content.isBlank()` | 拒收 |
| 无有效字符 | 去空白后有效字符（汉字/字母/数字）数 < 5 | 拒收 |
| 乱码 | U+FFFD 替换符占比 > 5% 或非法代理对 | 拒收 + 提示「内容疑似乱码」 |
| HTML 残留 | 正则剥离 `<[^>]{1,80}>`（白名单保护代码块内 `<` `>`）| 剥离 + 标记 `cleaned` |
| Markdown 规范化 | 折叠 3+ 连续空行为 2；剥离 `![...](...)` 图片引用 | 剥离 + 标记 |
| 重复粘贴段落 | 检测相邻重复块（连续 ≥2 次且 ≥200 字）| **告警不拒收**，记 WARN |

**关键决策**

| 决策点 | 选择 | 理由 |
| :--- | :--- | :--- |
| 拒收 vs 静默清洗 | 确定性噪声（空/乱码）**拒收**；规范性噪声（HTML/空白）**静默清洗**并标记 | 拒收给用户明确反馈；静默清洗避免「已保存但内容被改」的困惑 |
| 代码块保护 | HTML 剥离正则跳过 fenced code block 段 | 运维知识核心在命令行/配置，误删即破坏 |
| 重复段落 | 只告警不自动删 | 自动删可能误伤有意的重复强调 |

**风险与规避**：清洗规则保守 + 白名单保护代码块，最大风险是「该清洗的没清洗」（漏检），而非「误删」（误删）。漏检不影响正确性，只是保留噪声。

**验证方式**：`KnowledgeContentCleanerTest` 单测（HTML 剥离/乱码拒收/空内容拒收/代码块保留/长文档完整性）；`verify_knowledge_doc_api.sh` 增场景：乱码文档 40001、HTML 文档入库后切片不含标签。

---

### P2-4 归档对象存储落地

**目标**：`ARCHIVED` 状态真正启用，正文转对象存储（复用 6.14 MinIO 基础设施）。

**详细设计**
- `sql/migration_v10_knowledge_archive.sql`：`sys_knowledge_doc` 加 `archived_at TIMESTAMP`、`archive_path VARCHAR(512)`
- 复用 `infrastructure/storage/MinioConfig` + `TicketAttachmentService` 模式：对象键 `archive/{docId}/{version}_{yyyMMddHHmm}.md`
- `KnowledgeDocService.archive(docId)`：读正文 → 存 MinIO → 写 `archive_path`/`archived_at` → 状态置 `ARCHIVED`（**正文列保留**，供回看；archive_path 为审计与后续「彻底清理正文」备份）
- `restore` 增强：`ARCHIVED` 文档回滚时先按 `archive_path` 取回正文再走 update 重建向量
- 配置：归档桶名、`archive-enabled` 开关

**关键决策**

| 决策点 | 选择 | 理由 |
| :--- | :--- | :--- |
| 正文列是否置 NULL | **保留** | 运维知识几十 KB，TEXT 不占资源；置 NULL 产生「回看即 404」风险，得不偿失 |
| 归档失败 | 记 ERROR，状态回滚为 DEPRECATED（不产生「标记了归档但没存上」的中间态）| 与 6.14「先存对象再写元数据」同向 |

**风险与规避**：MinIO 不可用时归档任务跳过（`archive-enabled` 自动降级），不阻塞主流程（Fail-Safe，6.14 已确立）。

**验证方式**：`verify_knowledge_archive.sh`——废弃超期 → 归档 → `mc ls` 确认对象 + `archive_path` 写入 + 状态 ARCHIVED；MinIO 停用 → 归档跳过不报错；ARCHIVED restore 成功重建向量。

---

### P2-5 批量导入异步化

**目标**：几十篇文档批量导入不阻塞接口（单篇同步 ~11.6s 不可接受）。

**详细设计**
- 新增 `application/import/KnowledgeImportService.java` + 独立线程池（复用 `DocumentIndexer.indexExecutor` 模式，2 线程）
- 接口：`POST /api/v1/knowledge/import`（数组，逐篇走 `create()`：清洗→去重→向量化）+ `GET /api/v1/knowledge/import/{batchId}`（进度：总数/成功/失败/错误明细）
- batch 进度存 Redis（TTL 24h）或内存 Map；单篇失败不中断批次，结果汇总（6.9 部分成功原则）
- 与单篇 `POST /docs` 并存，互不影响

**关键决策**

| 决策点 | 选择 | 理由 |
| :--- | :--- | :--- |
| 异步边界 | 仅批量导入异步；单篇手动保持同步 | 单篇要「发布即生效」的即时反馈（6.21 已拍板）|
| 失败处理 | 逐篇隔离，失败记原因 | 一篇重复/乱码不影响其余导入 |

**风险与规避**：并发导入与单篇编辑同文档的冲突靠乐观锁（version CAS，6.11）兜底。

**验证方式**：`verify_knowledge_import.sh`——10 篇混合（含 1 篇重复、1 篇乱码）→ 成功 8/失败 2 如实上报；batchId 状态回查完整；重复篇带 duplicateDocId。

---

### P2-6 跨文档入库切片去重（实验性开关）

**目标**：可选地省向量存储与检索开销。

**详细设计**
- 配置 `devops.ai.retrieval.dedup-cross-doc: false`（默认关）
- 开启时：`DocumentIndexer.indexTask` 切片写入前按 `content_hash` 查库中已有切片，重复则**跳过写入**并记录 `dedupCount`

**关键决策**

| 决策点 | 选择 | 理由 |
| :--- | :--- | :--- |
| 默认状态 | **关** | 检索去重（P1-1）已覆盖主要收益；入库去重引入语义问题（下条）|
| 重复切片归属 | 跳过写入的切片检索时命中**既有切片**，模型引用的出处是既有文档 | 这是刻意取舍：省存储，但新文档的重复段落不产生自己的引用 |

**风险与规避**：本质是「以引用错位换存储」，仅适合超大知识库。P1-1 落地后评估是否仍需本项；若检索去重已足够，本项**可搁置**（文档明示取舍即可）。

**验证方式**：开关开启后重复段落不入库（chunk 表无新增）；检索命中的出处为既有文档；关闭后行为不变。

---

### P2-7 多来源治理（来源标签 + 权威分级）

**目标**：多来源（Confluence/钉钉/Wiki/手动）导入时，来源可追溯、权威可识别。

**详细设计**
- `sys_knowledge_doc` 现有 `knowledge_source`（默认 "SOP"）扩展为枚举：`MANUAL` / `CONFLUENCE` / `DINGTALK` / `WIKI`；新增 `authority_level`：`OFFICIAL`（官方文档）/ `EXPERIENCE`（一线经验）/ `COMMUNITY`（社区）
- 检索过滤/加权：`devops.ai.retrieval.min-authority`（低于该级别的文档不参与检索，或仅作降权）
- 新增 `GET /api/v1/knowledge/sources`（来源分布统计）；前端文档卡片展示来源徽标
- SimHash 近似去重增强：跨来源重复（同知识多来源导入）返回 `nearDuplicates` 时附「合并建议」（以 OFFICIAL 为准）

**关键决策**

| 决策点 | 选择 | 理由 |
| :--- | :--- | :--- |
| 权威过滤 vs 加权 | 默认**仅展示不过滤**；过滤开关后续按需 | L1 知识量小，过滤会漏答案；展示权威让用户自行判断 |
| 数据迁移 | 存量文档 `knowledge_source` 置 `MANUAL` | 兼容既有数据，无迁移脚本 |

**风险与规避**：纯增量字段，无破坏性。检索过滤默认关，避免误伤。

**验证方式**：`verify_knowledge_source.sh`——不同来源文档入库 → sources 统计正确 → 前端徽标渲染 → 开启过滤后低权威文档不参与检索。

---

## 八、与既有决策的衔接

| 既有决策 | 衔接方式 |
| :--- | :--- |
| 6.20 语义缓存治理 | 保留「知识变更清空缓存」；本设计的废弃/归档/物理删各写路径同样触发清缓存 |
| 6.21 文档生命周期 | 本设计是 6.21 的延伸：状态机/版本/去重骨架不动，补齐跨文档去重与定时清理 |
| 6.13 标签归一化 | 标签治理不涉及向量，与本设计正交 |
| 向量维度 1536 铁律 | 不因数据治理改变 |

---

## 九、待后续拍板的参数

| 参数 | 建议默认值 | 影响 |
| :--- | :--- | :--- |
| 废弃保留期 | 90 天 | 决定审计/撤销窗口长度（P1-2）|
| 归档保留期 | 365 天 | 决定冷数据何时彻底删除（P1-2/P2-4）|
| 归档存储方案 | MinIO（已有 6.14 基础）| P2-4 |
| 检索去重开关 | `dedup-result: true` | P1-1，可关作对照 |
| 清洗规则开关 | 全开，阈值保守 | P1-3 |
| 跨文档入库切片去重开关 | 默认关（可搁置）| P2-6 |
| 权威分级过滤开关 | 默认关（仅展示）| P2-7 |
| 定时任务执行时间 | 孤儿每小时 / 扫描每日 03:30 | 低峰期避开业务 |

---

## 十、文档状态

**状态**：✅ 当前有效 | **最后更新**：2026-08-11 | **负责人**：Claude (AI Assistant)
**下一步**：P1 三项（检索去重 / 定时清理框架+孤儿对账 / 数据清洗）可并行实施，均可在现有骨架增量完成、非重写；P2 依赖 P1 基础。是否现在启动 P1 实施，由用户确认。
