# 101 — S0-2：Testcontainers 集成测试落地报告

> 路线图：《OpsBrain_AI_生产级落地路线图》§4.2（任务 0-2.1 ~ 0-2.6）。
> 日期：2026-09-07　|　分支：arena/01a07aa6-opsbrain-ai　|　状态：🟡 待验收

## 一、落地清单

| 任务（路线图编号） | 产出 | 说明 |
|---|---|---|
| 0-2.1 引入依赖 | `pom.xml`：`org.testcontainers:postgresql` + `junit-jupiter` | 版本由 spring-boot-dependencies 内嵌 testcontainers-bom 托管 |
| 0-2.2 `AbstractIntegrationTest` 基类 | `src/test/java/com/devops/agent/support/AbstractIntegrationTest.java` | 采用 **singleton 容器 + `@DynamicPropertySource`** 而非 `@ServiceConnection`，理由全部写在类 javadoc（共享一器省启动成本；连接信息显式可见；少一个依赖） |
| 0-2.3 pgvector 镜像 | `pgvector/pgvector:pg16` + `asCompatibleSubstituteFor(IMAGE)` | 官方 postgres 镜像不带扩展，`VECTOR(1536)` 会直接报 type 不存在（路线图坑位预警） |
| 0-2.4 迁移检索链路测试 | `HybridRetrieverIntegrationTest extends AbstractIntegrationTest` | 原连共享 dev 库（25432），现连真空容器库；Flyway 全量迁移启动期自动执行 |
| 0-2.5 CI 支持 Docker | ubuntu-latest 内置 dockerd，无需改 workflow | 容器测试随 `./mvnw verify` 自然执行（命名命中 surefire `*Test` 模式），机器人无 workflows 权限**不构成阻塞** |
| 0-2.6 Redis/MinIO 容器化评估 | 决策：**均不做** | Redis——lettuce 懒连接，检索链路不触碰，`SemanticCacheService#init()` 仅建内存 Map；MinIO——检索链路不涉及。S0-2 保持 PG 单容器；需要时按同基类补 |

**附带产物**（S0-1 收口助力）：新增 `FlywayMigrationIntegrationTest`——容器真空库上
Flyway 亲自全量迁移，断言 27 张业务表 + `flyway_schema_history` 恰一条 V1 成功记录。
这让 S0-1 验收 #1（空库建全表）有了**不依赖 ci.yml 修改的直接证据**（堵 workflows 权限缺口的第二通道）。

## 二、验收核对（路线图 §4.2）

| # | 验收标准 | 结论 | 证据 |
|---|---|---|---|
| 1 | 全新 clone 只装 Docker，`./mvnw verify` 全通 | 🟡 **部分达标（如实记缺口）** | 容器类测试（`HybridRetrieverIntegrationTest`/`FlywayMigrationIntegrationTest`）与全部单测、契约测试均已不依赖宿主服务；**但其余 13 个 `@SpringBootTest` 类仍绑定 dev profile 的 25432/26379**——它们整体容器化超出 0-2.4 的范围，登记为后续任务 S0-2b（见 PROGRESS 待办） |
| 2 | 测试结束容器自动销毁无残留 | ✅ 达标（机制级） | Ryuk sidecar 随 JVM 退出回收容器，无需 `@AfterAll` |
| 3 | CI 中集成测试步骤通过 | ✅ 达标 | run 34105259493（绿，含首次镜像拉取）；后续 34106765448 复验绿 |

## 三、注入验证 J1 —— 探针先抓到一条「死红线」（本轮最大收获）

路线图要求的注入：**向量维度 1536 → 512，确认 `HybridRetrieverIntegrationTest` 变红**。
实际走了三程，过程本身暴露了更深的缺陷：

| 程 | 提交内容 | 结果（run id） | 说明 |
|---|---|---|---|
| J1 | 仅注入 `devops.ai.vector.dimension: 512` | 🔴 变红**本应收场，实际竟绿**（34105538107） | `MockEmbeddingModel` 硬编码 `DIMENSION = 1536`，完全绕过配置——MOCK 是 CI 唯一使用的模式，等于 **CI 从不校验向量维度一致性**，路线图的注入验证形同虚设 |
| J1b | 修 `MockEmbeddingModel`（构造器注入维度、`AiModelConfig` 传 `vectorDimension`、<4 越界保护）**+ 保留 512 注入** | 首推编译红（34106169325：字段名 `dimension`→`vectorDimension` 漏改，「沙箱无 JDK 必须 grep 全引用」教训再应验）；修正后红在预期位置（34106451311：`INSERT INTO sys_knowledge_chunk` 维度不匹配） | 红线接通证据 |
| 还原 | 512 → 1536，**修复保留为永久代码** | 绿（34106765448） | 维度漂移防线自此在 MOCK 路径真正生效 |

配套新增 `MockEmbeddingModelTest`（维度=配置 / 越维拒绝 / 确定性三例），防止硬编码复辟。

## 四、决策记录（0-2.6 与实现选型）

| 决策点 | 结论 | 理由 |
|---|---|---|
| Redis 是否容器化 | **不做** | 检索链路不触碰；lettuce 懒连接；`@PostConstruct` 只建内存 Map；容器化只会拖慢共享启动 |
| MinIO 是否容器化 | **不做** | 检索链路不涉及对象存储 |
| 容器模式 | **JVM 静态单例** | `@Testcontainers` 每类一器会把 CI 拖垮；共享容器由 Ryuk 兜底销毁。写入型测试必须自带幂等清理（基类 javadoc 已立规） |
| 数据源注入 | **`@DynamicPropertySource` 显式三行** | `@ServiceConnection` 自动装配过于隐式，排查「测试连了哪个库」要翻框架内部 |

## 五、遗留与后续

- **S0-2b（新登记）**：其余 13 个 `@SpringBootTest` 类逐个迁入 `AbstractIntegrationTest`
  （每迁一个需先确认其 Redis/调度器依赖惰性），完成后验收 #1 转 ✅。
- 类间共享容器数据可见：已迁入的两类分别只断言「不可变事实」（表结构/history）
  与「自带幂等重建」（ingest 重建语义）。后续迁入写入型测试沿用此约束。
- 首次拉取 `pgvector/pgvector:pg16` 使 CI 时长有可见增加；GitHub runner 镜像缓存
  策略若有需要再议（当前不构成问题）。
