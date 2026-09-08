# 100 — S0-1：Flyway 迁移版本化落地报告

> 路线图：《OpsBrain_AI_生产级落地路线图》§4.1（任务 0-1.1 ~ 0-1.6）。
> 日期：2026-09-07　|　分支：arena/01a07aa6-opsbrain-ai　|　状态：🟡 待验收（含 1 项收尾挂账）

## 一、决策记录：D-01 与方案 B 的实际形态

路线图 §4.1 的前提（"25 个手写 `migration_vNN_*.sql` 文件"）在动手前已被证伪：
历史迁移早在 **2026-08-27 已全部并入 `sql/init.sql`**（1123 行、27 张表），
仓库中不存在任何增量迁移文件。因此：

- **D-01 采纳方案 B（归档历史迁移、合并为单基线）**——用户未表态时按路线图
  明示的推荐执行，并可反悔；方案 B 在本仓库的实际形态降为两件事：
  1. `init.sql` 整体迁入 classpath（Flyway 托管）；
  2. 铲除"代码侧建表"这个**第二真相源**（比文件漂移更隐蔽的双写）。
- 阻塞决策 D-01 中本来要归档的"25 个迁移文件"不存在，归档不产生任何操作。

## 二、落地清单

| 任务（路线图编号） | 产出 | 说明 |
|---|---|---|
| 0-1.1/0-1.2 引入 Flyway、重放整合基线 | `pom.xml`：`flyway-core` + `flyway-database-postgresql`（BOM 托管）、`flyway-maven-plugin`；`src/main/resources/db/migration/V1__baseline.sql` | 即原 `init.sql` 内容，SQL **零改动**，仅换文件头；git mv 等价保留历史 |
| 0-1.3 baseline-on-migrate | `application.yml`：`spring.flyway.enabled/locations/baseline-on-migrate/validate-on-migrate` 显式化（全局生效于 dev/prod） | 存量无版本表数据库首启：V1 标记已应用并跳过，其后增量正常 |
| 0-1.4 删除临时 DDL 执行路径 | 删 `Knowledge{Category,Tag}SchemaInitializer` + 两 `ensureSchema()`；CI 旧 `psql -f sql/init.sql` 步骤改挂账（见 §五）；`SchemaGuard` 文档/提示语更新 | ensureSchema 的 DDL+回填与基线**逐字重复**（init.sql 381/422/438-469 行、520/530-538 行逐项比对确认），删除为零行为变化 |
| 0-1.5/0-1.6 迁移命名+校验+契约测试 | `FlywayMigrationContractTest`（3 测试） | 命名 `V{n}__描述.sql`、版本唯一、V1 存在、`sql/` 顶层禁 DDL（`mock_data.sql` 数据脚本放行）、`VECTOR(1536)` 与 §3.3 铁律联动 |

同步更新：`docker-compose.dev.yml`（改挂 V1 为 PG 首启脚本）、
`docker-compose.yml` + `scripts/docker-initdb.sh`（改为预建 V1 基线）、
`README.md` 快速开始、`AGENTS.md §3.5`（规则整体改写为 Flyway 版，保留
双写漂移的历史教训段）、`AGENTS.md §3.3`（向量维度引用位）、
4 处代码 javadoc 中 `init.sql` 字样。

## 三、验收核对（路线图 §4.1）

| # | 验收标准 | 结论 | 证据 |
|---|---|---|---|
| 1 | 空库 `flyway migrate` 一次建出全部 27 张表 | 🟡 间接达标 | CI 中 `psql` 桩把 V1 内容预灌空库、`ON_ERROR_STOP=1` 全绿（run 34102402282）→ 基线 SQL 完备性已证；「Flyway 亲自对**空**库迁移」受 ci.yml 权限阻塞（§五），收尾验证 |
| 2 | 已有库 `flyway migrate` 不报错、不重复变更 | ✅ 达标 | CI 库由 psql 预建、无版本表 → `baseline-on-migrate` 跳过 V1 后零迁移，全部 `@SpringBootTest` 绿（同上 run） |
| 3 | 手工改已应用迁移文件 → `flyway validate` 报错 | 🟡 挂账 | 依赖 CI 中的 validate 步骤（§五）；代码侧 `validate-on-migrate: true` 已配，行为随 §五 一并验收 |
| 4 | CI 中 `flyway validate` 步骤存在且通过 | 🔴 挂账 | 机器人无 workflows 权限不能改 `.github/workflows/ci.yml`，修复 diff 已备好（§五） |

**K 探针（注入-还原，验证红线真实存在，非纸面规则）**：

- **K1** `sql/init_legacy.sql` 注入 CREATE TABLE → run 34102706523 🔴，
  **唯一一条失败注解**精确点名 `FlywayMigrationContractTest`/`init_legacy.sql`；
  还原后 run 34102980023 🟢。
- **K2** `V2__inject_bad.sql` 注入坏迁移（引用不存在类型）→ run 34103252356 🔴，
  全部上下文测试 `Failed to load ApplicationContext`（migrate 失败即拒绝启动）；
  还原后 run 34103519481 🟢——**第二程直接转绿即 PG DDL 事务回滚无残留**
  （失败迁移未在历史表留下需 repair 的脏记录）。

## 四、行为基线

对运行时零行为变化：删掉的 `ensureSchema()` 是只增不改的幂等安全网，
其内容与基线逐字重复；启动期 Flyway 比它们更早执行且职责完全覆盖。
`SchemaGuard` 保留原机制（存量库 baseline 跳过、本就残缺的库仍需它兜底）。

## 五、收尾挂账（恢复条件：GitHub App 获 workflows 权限）

**阻塞事实**：推送含 `.github/workflows/ci.yml` 变更的提交被拒——
`refusing to allow a GitHub App to create or update workflow ... without workflows permission`。
此前 workflow 变更均由用户账号（Yyu675）推送（如 674429c）。

需要用户配合：**在 Arena 重新连接 GitHub / 或手工应用以下两处改动**。改动内容
（已在本分支工作区验证过的最终文案）：

1. 将 ci.yml 的「初始化数据库 Schema」步骤整段替换为三行注释（schema 交给 Flyway，
   首个 `@SpringBootTest` 即空库 migrate 验收 #1），并**删除 `sql/init.sql` 兼容桩**；
2. 在「上传测试报告」前插入步骤：
   ```yaml
      - name: Flyway 迁移校验
        run: |
          ./mvnw -B -ntp flyway:validate \
            -Dflyway.url=jdbc:postgresql://localhost:25432/devops_knowledge_db \
            -Dflyway.user=devops \
            -Dflyway.password=devops_password \
            -Dflyway.locations=classpath:db/migration
   ```

完成后验收 #1（纯空库 Flyway）与 #3/#4（validate 步骤）一次性收口，
本报告状态即可从"待验收"转 ✅。

## 六、遗留与后续

- `flyway-maven-plugin` 版本依赖 starter-parent 的 pluginManagement，
  未在 CI 执行过（无 JDK 沙箱）；若收尾步骤红在插件版本解析，补
  `<version>${flyway.version}</version>` 即可。
- 下一阶段（S0-2 Testcontainers）将为「空库 Flyway 全迁移」提供不依赖
  CI workflow 文件的本地可复现验证，届时 #1 可获第二层证据。
