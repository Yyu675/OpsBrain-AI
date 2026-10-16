# 73 · SQL 与资源整合：19 个文件归并为 2 个，并修出一处真实漂移

> 目标：`sql/` 只保留「表结构」与「模拟数据」两个文件；其余资源能整合的整合，
> 过期或冗余的核查后删除。

---

## 一、结果

| 项目 | 整合前 | 整合后 |
|------|--------|--------|
| `sql/` 文件数 | 19（init + mock + 17 个 migration） | **2**（`init.sql` + `mock_data.sql`） |
| 根目录中文报告 | 6 份 | 0 |
| 提交进 Git 的 coverage 报告 | 43 个 HTML / 852KB | 0 |
| 零引用图片资源 | 1 张 / 180KB | 0 |
| `application-prod.yml` | 0 字节 | 真实生产配置 |

删除 67 个文件，仓库体积减少约 1.1MB。

---

## 二、整合过程中发现的真实缺陷（本轮最重要的部分）

### 现象

把 17 个迁移并入 `init.sql` 前，先逐项比对「迁移做了什么」与「init.sql 有没有」。
比对发现 **init.sql 里 `visibility` 出现 0 次**——而它是 C1 权限过滤的核心列。

完整缺失清单：

| 类型 | 缺失项 | 来源迁移 |
|------|--------|---------|
| 列 | `sys_knowledge_doc.visibility` / `owner_dept` | v24 |
| 列 | `sys_knowledge_chunk.visibility` / `owner_dept` | v24 |
| 列 | `sys_user.dept` | v24 |
| 索引 | `idx_chunk_status_visibility` / `idx_chunk_owner_dept` / `idx_doc_visibility` | v24 |
| 约束 | `ck_doc_visibility` / `ck_chunk_visibility` | v24 |
| 约束 | `fk_knowledge_doc_category` | v12 |

### 成因

`AGENTS.md` 旧约定：

> Schema 变更：新增 `migration_vNN_描述.sql`……**同时必须更新 `sql/init.sql`**

同一个事实要求写两处，而**漏写一处不会有任何报错**。
这已经是第二次了——v25 的 `sys_operation_audit` 漏过一次，当时的结论是
「新增迁移后请逐字节对比自检」。事实证明，靠纪律约束双写是无效的。

### 用户可见后果（比上一次严重）

`docker-compose.dev.yml` **只挂载 `init.sql`，不执行迁移**：

```yaml
- ./sql/init.sql:/docker-entrypoint-initdb.d/init.sql:ro
```

也就是说，**按 dev compose 建出来的库根本没有 `visibility` 列**。
而 `HybridRetrieverService` 的检索 SQL 直接在 `WHERE` 里引用它：

```sql
WHERE status = 'ACTIVE' AND ... AND visibility = 'PUBLIC'
```

一查就报错。而 `DevOpsTools` 会把检索异常呈现为「知识库暂不可用」，
排查者会去查向量库、查 embedding 模型、查 API Key——
**很难想到是数据库少了一列**。

### 修法：不是补上双写，而是取消双写

只保留 `init.sql` 一个真相源。双写必然漂移且漂移无声，
改成单文件后这类缺陷在结构上不可能再发生。

`init.sql` 全程 `IF NOT EXISTS` + `DO $$ ... pg_constraint 判重`，
**幂等**，可以对已有库重复执行来补齐缺失结构。

---

## 三、怎么验证「整合前后等价」

静态比对不足以支撑删除 17 个文件的决定，必须真跑。
沙箱无 Docker、无 psql，改用 **`pgserver`**（pip 包，自带 PostgreSQL 二进制
且**包含 pgvector 扩展**）起真实实例：

```
newdb  ← 新的单文件 init.sql
olddb  ← 旧 init.sql + 17 个迁移（按 v11…v27 顺序）
```

再逐项 diff `information_schema` / `pg_indexes` / `pg_constraint`：

| 维度 | 结果 |
|------|------|
| COLUMNS（表名.列名 + 类型 + 长度 + 可空 + 默认值） | **356 项完全一致** |
| INDEXES（含完整 indexdef） | **122 项完全一致** |
| CONSTRAINTS（含 `pg_get_constraintdef`） | **31 项完全一致** |

补充验证：

- `mock_data.sql` 在新 schema 上执行 `rc=0`；
- 6 张种子数据表的**行数与内容**（不只是行数）逐一比对一致；
- 反向扫描迁移里的 `INSERT`，发现 v13 的标签字典回填未并入——已补。
  这一项 schema 比对抓不到（它是数据回填不是结构），
  靠的是单独写的「迁移 INSERT 是否都在 init.sql 中」检查。

---

## 四、CI 为什么不需要改

`ci.yml` 里有：

```bash
for f in $(ls sql/migration_v*.sql | sort -V); do ... done
```

我没有 workflows 写权限，所以先确认删完文件后这一步是否仍能通过。
实测：`ls` 无匹配时报错只进 **stderr**，命令替换的非零退出码
**不触发 `set -e`**，循环体零次执行，该步骤 `exit 0`。

因此**保留 `init.sql` 这个文件名**就足够了——正好绕开权限限制。
CI 实跑确认：真实 PostgreSQL 上建库成功、后端全部测试通过。

---

## 五、其余清理（每项都做了引用核查）

| 删除项 | 核查依据 |
|--------|---------|
| 根目录 6 份中文报告 | 2026-07 的一次性过程记录（「已修复 6 个问题」「待规划」）。引用的 `sql/ticket_extensions.sql` 与 `devops-platform-backend/` 路径**均已不存在**；除 benchmark 01 号文档建议清理它们外**零引用** |
| `coverage/` 43 个 HTML | 每次 `npm run test:coverage` 重新生成的产物。它随每次测试变动，会让 diff 塞满与代码无关的噪音。已补 `.gitignore`（根目录 + 前端各一处） |
| `hero-operations.jpg` | 全仓搜索零引用（`Home.vue` 用的是 `image_0_yi19x4.jpg`）。删除后前端 build 通过 |

### `application-prod.yml`：0 字节 → 真实配置

空文件与「没有这个文件」在**运行行为**上等价，但在**阅读**上不等价：
它让人以为「生产已经确认过、无需特殊配置」，于是没人再去核对
那些默认值只适合本地的项。

补写时刻意克制——**只写代码中真实读取的键**：

- `devops.schema.fail-fast`（`SchemaGuard` 读，默认 false，生产应为 true）
- `devops.security.auth-enabled`（`WebConfig` 读，生产恒 true 且不留环境变量口子）
- `devops.ai.mode`（不写死 REAL，避免误部署就烧额度）
- `logging.level.dev.langchain4j: WARN`（DEBUG 会把模型响应打进日志）

一度写了 `spring.jpa.show-sql: false`，核查后**删掉**：
项目从未配置过该键，默认就是 false，写进去是无依据的装饰性配置。

---

## 六、关于 knip 报的「未使用导出」

前端 `npm run knip` 报出 21 个未使用函数 + 85 个未使用类型。
**本轮不动它们**：这些是公共 API 面（如 `getApproval`、各类 `Query` 接口），
多数是为调用方预留的，逐个删除风险高于收益，
且与本轮「文件级整合」的目标不同。留作后续单独评估。

---

## 七、后续建议

1. **Flyway 仍然值得做**，但优先级下降了——单一幂等脚本已消除双写漂移这个主要风险，
   Flyway 的增量价值变成「记录执行历史」与「团队协作时的顺序保证」；
2. `SchemaGuard` 的关键列清单应随 init.sql 变更同步维护——
   它是「新 JAR + 旧数据卷」这一场景的最后一道防线
   （官方镜像只在数据目录为空时执行 initdb 脚本，复用老卷时 init.sql 根本不会跑）。
