# T3 备份恢复演练记录（真窗件 · 路线图 §13.1「可生产」验收补签）

**演练日期**：2026-09-10
**执行脚本**：`scripts/backup-restore-drill.sh`（可重复执行，产物落 `target/backup-drill-{时间戳}/`）
**演练环境**：本机 dev 中间件栈（docker-compose.dev.yml：pgvector 25432 / MinIO 19000）+ 后端 8088（MOCK 模式）

---

## 一、演练结论

**✅ 通过**。备份→破坏→恢复→一致性对账六表全对，应用层恢复后探活健康（`/actuator/health` 200）。

| 验收点 | 结果 |
| :-- | :--- |
| 备份脚本存在 | ✅ `scripts/backup-restore-drill.sh`（pg_dump `--clean --if-exists` 全量 + MinIO 对象清单） |
| 演练记录存在 | ✅ 本文档 |
| 恢复一致性 | ✅ 六表行数逐一 ==（见下表） |
| 应用层恢复后健康 | ✅ health 200（含 db UP） |

## 二、一致性对账（20260910-040447 轮）

| 表 | 备份时 | 恢复后 | 判定 |
| :-- | :--: | :--: | :---: |
| sys_devops_ticket | 10 | 10 | ✓ |
| sys_alert | 51 | 51 | ✓ |
| sys_knowledge_doc | 1 | 1 | ✓ |
| sys_knowledge_chunk | 1 | 1 | ✓ |
| sys_knowledge_category | 0 | 0 | ✓ |
| sys_user | 1 | 1 | ✓ |

备份产物：`target/backup-drill-20260910-040447/full-backup.sql`（604K，含结构+数据，`--clean --if-exists` 恢复语义=先 DROP 后建）。

## 三、演练中发现并修复的三个脚本缺陷（防再踩）

1. **`pg_dump` 缺 `--clean --if-exists`**：恢复时 `CREATE TABLE flyway_schema_history` 撞已存在对象 → `ON_ERROR_STOP` 在第一条 CREATE 即中止，业务表全部没恢复。加 `--clean`（恢复前先 DROP）后通过。
   > 教训：**全量备份的恢复语义必须在演练里实测**——`pg_dump` 默认产物假设「目标库是空的」，而真实故障恢复的目标库几乎必然残留半套对象。
2. **`psql -cA -c` 参数错位**：bash 变量展开把 `-cA` 当成了独立 SQL，TRUNCATE 实际靠第二个 `-c` 生效但每表打一条 ERROR 噪声。改单 `-c`。
3. **演练破坏范围含 `sys_user` 但演示用 admin 同源于它**：TRUNCATE 后登录全 401，后续造数据脚本失败。处置：重启后端让 `AuthDataInitializer` 自动补种（顺带验证了该组件的幂等性）——演练脚本对 `sys_user` 的破坏是**有意的**（验证用户数据也在备份恢复面内），不是缺陷。

## 四、MinIO 对象面（附件/归档）

本演练记录了对象清单（`mc ls --recursive`）作为恢复核对基准。**对象本体在 compose 卷内**，生产环境备份策略为 `mc mirror` 同步异地——对象级异地容灾演练超出本真窗范围，挂账待生产部署时首演。

## 五、后续例行要求（运维口径）

- 生产部署后**每月一次**重跑本演练（`bash scripts/backup-restore-drill.sh`），结果追加本文档表格。
- 备份产物不进 git（`target/` 已忽略），生产备份保存期与轮转策略由部署方定（建议 ≥ 30 天 + 异地一份）。
