# 生产环境数据库变更 Checklist

**版本**: v1.0 | **日期**: 2026-09-13 | **维护**: DevOps Team

本文档指导生产环境数据库变更的安全执行，覆盖增量迁移、回滚预案、Flyway checksum 冲突修复。

---

## 一、前置条件（必须全部满足 ✅）

### 1.1 代码验证
- [ ] **CI 绿灯**：GitHub Actions 所有检查通过
  - Flyway migrate + validate 通过（空库全量建表验证）
  - 单元测试 + 集成测试全绿
  - Schema 自检（SCHEMA_FAIL_FAST）通过
- [ ] **本地测试环境验证**：
  ```bash
  # 开发环境重建库验证
  docker-compose down -v
  docker-compose up -d
  ./mvnw spring-boot:run
  # 验证：1. 应用正常启动  2. 功能冒烟测试通过  3. 日志无异常
  ```

### 1.2 变更评估
- [ ] **变更类型识别**：
  - [ ] 新增表/字段（低风险，无数据迁移）
  - [ ] 新增索引（中风险，评估创建耗时）
  - [ ] 修改字段类型/约束（高风险，需数据迁移）
  - [ ] 删除表/字段/索引（高风险，需确认无依赖）
- [ ] **停机窗口评估**（索引创建参考）：
  - 10 万行以下：<5s，可在线执行
  - 10-100 万行：5-30s，建议低峰期
  - 100 万行以上：>30s，需维护窗口
- [ ] **回滚方案准备**：编写回滚 SQL 脚本（如 `rollback_batch_XX.sql`）

### 1.3 备份准备
- [ ] **数据库全量备份**：
  ```bash
  BACKUP_FILE="backup_$(date +%Y%m%d_%H%M%S).sql"
  docker exec devops-platform-backend-postgres-1 \
    pg_dump -U devops devops_platform > "$BACKUP_FILE"
  echo "备份完成：$BACKUP_FILE"
  ```
- [ ] **备份验证**：
  ```bash
  # 检查备份文件大小（非空）
  ls -lh "$BACKUP_FILE"
  # 检查备份内容完整性（至少包含建表语句）
  grep -c "CREATE TABLE" "$BACKUP_FILE"
  ```
- [ ] **备份存档**：将备份文件复制到安全位置（如对象存储/异地备份）

---

## 二、执行方案选择

### 方案 A：重建库（首次部署 / 可接受停机）

**适用场景**：
- 首次生产部署（空库）
- 开发/测试环境（数据可重建）
- 可接受 5-10 分钟停机窗口

**执行步骤**：
```bash
# 1. 停止应用
docker-compose down

# 2. 删除数据卷（⚠️ 谨慎操作）
docker volume rm devops-platform-backend_postgres-data

# 3. 重启（Flyway 自动执行 V1__baseline.sql）
docker-compose up -d

# 4. 验证应用启动
docker-compose logs -f --tail=50 backend

# 5. 冒烟测试
# - 访问 http://localhost:8080/actuator/health
# - 登录前端，执行关键业务操作
```

**优势**：
- ✅ 简单直接，无 checksum 冲突
- ✅ 数据库状态与 V1 基线完全一致

**风险**：
- ⚠️ 数据全部丢失（仅适用于空库/可重建场景）

---

### 方案 B：增量变更（已有数据 / 最小化停机）

**适用场景**：
- 生产环境已有业务数据
- 需最小化停机时间（<1 分钟）
- 变更为增量操作（新增索引/字段/表）

**执行步骤**：

#### B.1 提取增量 SQL
```bash
# 对比 V1__baseline.sql 变更，提取新增语句
# 示例：批 80 新增了 4 个索引

cat > incremental_batch_80.sql <<'EOF'
-- 批 80: 慢查询审计首批索引（P1 四个）
-- 日期: 2026-09-13

CREATE INDEX IF NOT EXISTS idx_alert_firing_recent
    ON sys_alert (status, created_at DESC)
    WHERE status = 'FIRING';

CREATE INDEX IF NOT EXISTS idx_dsession_queue_fifo
    ON sys_diagnosis_session (status, created_at ASC)
    WHERE status = 'QUEUED';

CREATE INDEX IF NOT EXISTS idx_approval_zombie_scan
    ON sys_approval_request (status, update_time)
    WHERE status = 'APPROVED';

CREATE INDEX IF NOT EXISTS idx_ticket_assignee_time
    ON devops_ticket (assigned_to, created_at DESC);
EOF
```

#### B.2 手动执行增量 SQL
```bash
# 连入生产数据库
docker exec -it devops-platform-backend-postgres-1 \
  psql -U devops -d devops_platform

# 执行增量 SQL（带计时）
\timing on
\i /path/to/incremental_batch_80.sql

# 验证索引创建成功
\di idx_alert_firing_recent
\di idx_dsession_queue_fifo
\di idx_approval_zombie_scan
\di idx_ticket_assignee_time

# 退出
\q
```

#### B.3 修复 Flyway checksum（关键步骤）
```bash
# 1. 计算新 V1__baseline.sql 的 checksum
# 方法 A：启动应用前预先计算（推荐）
NEW_CHECKSUM=$(docker exec devops-platform-backend-postgres-1 \
  psql -U devops -d devops_platform -tA \
  -c "SELECT CAST(SUM(CRC32(line)) AS INTEGER) FROM (
        SELECT unnest(string_to_array(
          pg_read_file('/docker-entrypoint-initdb.d/V1__baseline.sql'), 
          E'\n'
        )) AS line
      ) AS lines;")

# 方法 B：从 Flyway 错误日志中获取（应用启动失败后）
# 查看应用日志，找到类似这样的错误信息：
#   Migration checksum mismatch for migration version 1
#   -> Applied to database : 123456789
#   -> Resolved locally    : 987654321
# 取 "Resolved locally" 的值

# 2. 更新 Flyway 元数据表
docker exec -it devops-platform-backend-postgres-1 \
  psql -U devops -d devops_platform <<EOF
-- 备份当前记录
CREATE TABLE IF NOT EXISTS flyway_schema_history_backup AS 
  SELECT * FROM flyway_schema_history WHERE version = '1';

-- 更新 checksum（替换 <NEW_CHECKSUM> 为实际值）
UPDATE flyway_schema_history 
SET checksum = <NEW_CHECKSUM>,
    description = description || ' (checksum updated on $(date +%Y-%m-%d))'
WHERE version = '1';

-- 验证更新
SELECT version, checksum, description, installed_on 
FROM flyway_schema_history 
WHERE version = '1';
EOF
```

#### B.4 重启应用并验证
```bash
# 1. 重启应用
docker-compose restart backend

# 2. 观察启动日志（重点关注 Flyway 验证）
docker-compose logs -f --tail=100 backend | grep -E "Flyway|Migration|Validating"

# 3. 验证应用健康
curl http://localhost:8080/actuator/health

# 4. 冒烟测试
# - 登录前端
# - 执行涉及新索引的查询（如「我的工单」列表、告警看板）
# - 检查响应时间（应比变更前更快）
```

**优势**：
- ✅ 保留现有数据
- ✅ 停机时间最短（SQL 执行时间 + 应用重启）

**风险**：
- ⚠️ checksum 手动修复需谨慎（备份 flyway_schema_history 表）
- ⚠️ 增量 SQL 提取错误可能导致状态不一致

---

## 三、验证清单（执行后必查 ✅）

### 3.1 数据库层验证
```bash
# 1. 连入数据库
docker exec -it devops-platform-backend-postgres-1 \
  psql -U devops -d devops_platform

# 2. 验证表数量（V1 基线 + 批 80 后应为 33 张）
SELECT count(*) FROM information_schema.tables 
WHERE table_schema = 'public';

# 3. 验证索引存在（批 80 示例）
\di idx_alert_firing_recent
\di idx_dsession_queue_fifo
\di idx_approval_zombie_scan
\di idx_ticket_assignee_time

# 4. 验证 Flyway 元数据一致
SELECT version, checksum, success, installed_on 
FROM flyway_schema_history 
ORDER BY installed_rank;

# 5. 退出
\q
```

### 3.2 应用层验证
```bash
# 1. 健康检查
curl -s http://localhost:8080/actuator/health | jq .

# 2. 数据库连接池状态
curl -s http://localhost:8080/actuator/metrics/hikaricp.connections.active | jq .

# 3. 查看应用日志（无 ERROR）
docker-compose logs --tail=200 backend | grep -i error

# 4. Flyway 迁移历史端点（可选，需开启 management.endpoints）
curl -s http://localhost:8080/actuator/flyway | jq .
```

### 3.3 功能冒烟测试
- [ ] **登录认证**：管理员/普通用户登录
- [ ] **工单列表**：「我的工单」Tab 加载（验证 idx_ticket_assignee_time）
- [ ] **告警看板**：最近告警列表（验证 idx_alert_firing_recent）
- [ ] **诊断功能**：触发 AI 诊断（验证 idx_dsession_queue_fifo）
- [ ] **审批流程**：查看待审批工单（验证 idx_approval_zombie_scan）

---

## 四、回滚预案

### 4.1 数据库层回滚

**场景 A：增量 SQL 执行失败（未修改 checksum）**
```bash
# 1. 删除部分创建的索引
docker exec -it devops-platform-backend-postgres-1 \
  psql -U devops -d devops_platform <<EOF
DROP INDEX IF EXISTS idx_alert_firing_recent;
DROP INDEX IF EXISTS idx_dsession_queue_fifo;
DROP INDEX IF EXISTS idx_approval_zombie_scan;
DROP INDEX IF EXISTS idx_ticket_assignee_time;
EOF

# 2. 回滚代码版本
git checkout <previous-commit>
docker-compose down
docker-compose up -d
```

**场景 B：checksum 修复错误（应用启动失败）**
```bash
# 1. 恢复 Flyway 元数据备份
docker exec -it devops-platform-backend-postgres-1 \
  psql -U devops -d devops_platform <<EOF
DELETE FROM flyway_schema_history WHERE version = '1';
INSERT INTO flyway_schema_history 
  SELECT * FROM flyway_schema_history_backup WHERE version = '1';
EOF

# 2. 回滚代码版本
git checkout <previous-commit>
docker-compose restart backend
```

**场景 C：数据损坏（最坏情况）**
```bash
# 1. 停止应用
docker-compose down

# 2. 恢复备份
BACKUP_FILE="backup_20260913_103045.sql"  # 替换为实际备份文件
docker exec -i devops-platform-backend-postgres-1 \
  psql -U devops -d devops_platform < "$BACKUP_FILE"

# 3. 回滚代码版本
git checkout <previous-commit>
docker-compose up -d
```

### 4.2 回滚验证
- [ ] 应用正常启动
- [ ] 健康检查通过
- [ ] 核心功能可用（登录、查询工单、查看告警）
- [ ] 数据完整性检查（关键业务数据计数）

---

## 五、常见问题与排查

### Q1: Flyway checksum 不匹配，应用启动失败

**错误日志示例**：
```
Migration checksum mismatch for migration version 1
-> Applied to database : 123456789
-> Resolved locally    : 987654321
```

**原因**：V1__baseline.sql 内容变更，但数据库中记录的 checksum 仍是旧值

**解决方案**：参考「方案 B - B.3 修复 Flyway checksum」

---

### Q2: 索引创建耗时过长，阻塞业务

**症状**：执行 `CREATE INDEX` 后长时间无响应

**原因**：表数据量大，索引创建需扫描全表

**应对措施**：
```sql
-- 1. 查看当前进度（另开一个连接）
SELECT pid, query, state, query_start, now() - query_start AS duration
FROM pg_stat_activity
WHERE query LIKE '%CREATE INDEX%';

-- 2. 评估是否继续等待（参考停机窗口）
-- 如需中止（⚠️ 谨慎操作）：
SELECT pg_cancel_backend(<pid>);  -- 温和中止
-- 或
SELECT pg_terminate_backend(<pid>);  -- 强制中止

-- 3. 改用并发索引创建（不阻塞写入，但耗时更长）
CREATE INDEX CONCURRENTLY idx_ticket_assignee_time
    ON devops_ticket (assigned_to, created_at DESC);
```

---

### Q3: 备份恢复后数据不一致

**症状**：部分最新数据丢失

**原因**：备份时间点与故障时间点之间有增量写入

**预防措施**：
- 实施 WAL 归档 + PITR（Point-In-Time Recovery）
- 启用逻辑复制（Streaming Replication）
- 定期演练备份恢复（参考 T3 备份恢复演练脚本）

---

## 六、后续改进建议

### 6.1 自动化增强
- [ ] **迁移脚本生成器**：自动提取 V1 diff，生成增量 SQL
- [ ] **checksum 计算工具**：一键计算新 V1 checksum，避免手动错误
- [ ] **回滚脚本自动生成**：从增量 SQL 反推回滚语句

### 6.2 监控增强
- [ ] **Flyway 迁移监控**：接入 Prometheus，记录迁移耗时/成功率
- [ ] **索引健康度监控**：`pg_stat_user_indexes` 监控索引使用率
- [ ] **慢查询监控**：`pg_stat_statements` 识别未优化查询

### 6.3 流程规范化
- [ ] **变更评审会议**：生产变更前团队评审
- [ ] **演练制度**：每季度演练一次备份恢复
- [ ] **变更日志**：记录每次生产变更的时间/内容/执行人

---

## 七、相关文档

- **CI 流程说明**：`.github/workflows/ci.yml` + `docs/08-benchmark/151-168/183-*.md`
- **V1 基线文件**：`src/main/resources/db/migration/V1__baseline.sql`
- **备份恢复演练**：`tools/ci/backup-restore-demo.sh`（T3 脚本）
- **Flyway 官方文档**：https://flywaydb.org/documentation/

---

**Checklist 版本**: v1.0  
**最后更新**: 2026-09-13  
**维护者**: DevOps Team
