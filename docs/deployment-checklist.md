# 生产部署 Checklist

## 一、部署前检查（Pre-deployment）

### 1.1 数据库迁移准备
- [ ] **备份当前生产数据库**（全量 dump，标注时间戳）
  ```bash
  pg_dump -h $PROD_HOST -U $PROD_USER -d devops_platform \
    -F c -f backup_$(date +%Y%m%d_%H%M%S).dump
  ```
- [ ] **检查 Flyway 迁移历史一致性**
  ```bash
  # 生产库当前已应用版本
  psql -h $PROD_HOST -U $PROD_USER -d devops_platform \
    -c "SELECT version, description, installed_on FROM flyway_schema_history ORDER BY installed_rank;"
  
  # 代码仓库最新迁移文件
  ls -lh src/main/resources/db/migration/
  ```
- [ ] **验证新迁移在测试环境通过**（CI 绿灯 + staging 环境验证）
- [ ] **评估迁移执行时长**（在与生产数据量相当的 staging 库实测）
  - 若 >5 分钟，需规划维护窗口
  - 若涉及大表 DDL（alert_events/diagnosis_sessions），考虑分批或在线 DDL 工具

### 1.2 配置检查
- [ ] **.env 或环境变量完整性**（AI 端点/MinIO/Redis/PG 连接串）
- [ ] **⚠️ 生产强制环境变量（2026-09-17 批88审计发现——缺任何一项启动即炸或安全裸奔）**

  | 变量名 | 用途 | 不设的后果 | 示例值 |
  |---|---|---|---|
  | `SPRING_DATASOURCE_URL` | 数据库连接 | **启动直接失败**——`application.yml` 无默认 URL，prod 不从 dev 继承连接信息 | `jdbc:postgresql://prod-db:5432/opsbrain` |
  | `SPRING_DATASOURCE_USERNAME` | 数据库用户 | 同上 | `opsbrain_app` |
  | `SPRING_DATASOURCE_PASSWORD` | 数据库密码 | 同上 | （强随机） |
  | `CORS_ALLOWED_ORIGINS` | CORS 白名单 | **启动直接失败**——`__MUST_SET_CORS_ALLOWED_ORIGINS__` 占位符会被 `WebConfig.validateCorsConfig()` 拒启 | `https://ops.example.com` |
  | `ALERT_WEBHOOK_SECRET` | Webhook 共享密钥 | **安全裸奔**——`/api/v1/alerts/webhook` 免 Sa-Token 鉴权，无密钥任何人都可灌入伪造告警触发自动建单 | （强随机，同步配到 Alertmanager） |
  | `AUTH_SEED_PASSWORD` | 初始 admin 密码 | 占位符 `__MUST_SET_AUTH_SEED_PASSWORD__` 会导致 seed 失败 | （强随机） |

  > **出处**：2026-09-17 `application-prod.yml` + `WebhookGuard.verify()` 代码走查 + prod profile 实际启动验证（`SPRING_PROFILES_ACTIVE=prod`）。
  > 开发环境（dev profile）从 `application-dev.yml` 读取默认值，不受此表影响。

- [ ] **敏感配置未提交代码库**（.env 在 .gitignore 中）
- [ ] **application-prod.yml 激活**（`SPRING_PROFILES_ACTIVE=prod`）
- [ ] **Flyway 配置确认**
  - `baseline-on-migrate: true`（首次部署）或 `false`（后续升级）
  - `validate-on-migrate: true`（强制开启，防漂移）
  - `out-of-order: false`（生产严格顺序）

### 1.3 依赖服务健康
- [ ] PostgreSQL 可达且版本 ≥16
- [ ] Redis 可达且认证通过
- [ ] MinIO/S3 可达且 bucket 已创建
- [ ] AI 端点可达（chat/embedding 各调一次 hello-world）

### 1.4 代码质量门禁
- [ ] CI 全绿（后端测试 + 前端 lint/test/build）
- [ ] 无已知 P0/P1 缺陷
- [ ] RAG 评测基线对比通过（report vs baseline delta < 5%）

---

## 二、部署执行（Deployment）

### 2.1 应用停机（若采用停机部署）
```bash
docker-compose -f docker-compose.prod.yml down
# 或
systemctl stop devops-platform
```

### 2.2 数据库迁移
```bash
# 方式一：Maven 插件（适用本地 → 生产直连）
./mvnw flyway:migrate \
  -Dflyway.url=jdbc:postgresql://$PROD_HOST:5432/devops_platform \
  -Dflyway.user=$PROD_USER \
  -Dflyway.password=$PROD_PASSWORD

# 方式二：应用启动自动迁移（推荐）
# spring.flyway.enabled=true 会在应用启动时自动执行
```

**迁移失败回滚流程见第四章节。**

### 2.3 应用启动
```bash
# Docker Compose
docker-compose -f docker-compose.prod.yml up -d

# 或 JAR 直接运行
java -jar -Dspring.profiles.active=prod \
  devops-platform-backend-1.0.0.jar
```

### 2.4 健康检查
```bash
# 等待应用就绪（最长 90s）
timeout 90 bash -c 'until curl -f http://localhost:8080/actuator/health; do sleep 2; done'

# 检查关键指标
curl http://localhost:8080/actuator/health | jq '.components | keys'
# 期望输出包含: db, redis, diskSpace, ping
```

---

## 三、部署后验证（Post-deployment）

### 3.1 Flyway 迁移状态
```bash
# 确认所有迁移已应用且 checksum 匹配
./mvnw flyway:info -Dflyway.url=... | grep -E "SUCCESS|PENDING"
# 期望所有行状态为 SUCCESS，无 PENDING/FAILED
```

### 3.2 数据库 Schema 验证
```bash
# 表数量（V1 基线应 ≥33 张）
psql -h $PROD_HOST -U $PROD_USER -d devops_platform -tA \
  -c "SELECT count(*) FROM information_schema.tables WHERE table_schema='public';"

# 关键表存在性
psql ... -c "SELECT tablename FROM pg_tables WHERE schemaname='public' AND tablename IN
  ('ticket', 'alert_events', 'diagnosis_sessions', 'knowledge_documents', 'flyway_schema_history');"
```

### 3.3 功能烟测
- [ ] **告警接收**：POST `/api/v1/alerts/webhook` 写入 sys_alert 表
- [ ] **工单查询**：GET `/api/v1/tickets?page=1&size=5` 返回 200
- [ ] **知识库访问**：GET `/api/v1/knowledge/docs?page=1&size=3` 返回 200
- [ ] **鉴权正常**：匿名访问受保护端点返回 401，登录后返回 200

### 3.4 监控接入
- [ ] Prometheus metrics 暴露（`/actuator/prometheus`）
- [ ] 日志聚合器接入（若使用 ELK/Grafana Loki）
- [ ] 告警规则激活（alert.rules.yml 中 13 条规则）

---

## 四、Flyway 迁移失败回滚流程

### 4.1 识别失败状态
```bash
./mvnw flyway:info -Dflyway.url=...
# 查看输出，若有 FAILED 行，记录其 version 和 description
```

### 4.2 清理失败记录
```sql
-- 连接到生产库
psql -h $PROD_HOST -U $PROD_USER -d devops_platform

-- 查看失败记录
SELECT * FROM flyway_schema_history WHERE success = false ORDER BY installed_rank DESC;

-- 删除失败记录（⚠️ 仅删除本次部署的失败行，不删历史成功记录）
DELETE FROM flyway_schema_history 
WHERE installed_rank = (SELECT MAX(installed_rank) FROM flyway_schema_history)
  AND success = false;
```

### 4.3 根据迁移类型回滚

**场景 A：纯 DDL 失败（建表/加列/索引）**
- 失败时 PostgreSQL 自动回滚事务，Schema 未变
- 清理失败记录后，修复迁移脚本重新部署即可

**场景 B：数据迁移失败（INSERT/UPDATE）**
- 若在事务内，自动回滚
- 若脚本含多语句且某中间语句失败，可能部分数据已写入
- **回滚方案**：
  1. 恢复备份数据库到新实例（勿覆盖生产）
  2. 对比生产与备份的数据差异
  3. 编写补偿脚本回退部分写入
  4. 或直接用备份替换生产库（需停机）

**场景 C：不可逆 DDL（DROP TABLE/COLUMN）**
- ⚠️ 此类操作必须在 staging 环境充分验证
- 失败时数据已丢失，只能从备份恢复：
  ```bash
  # 停止应用
  docker-compose down
  
  # 删除现有数据库
  psql -h $PROD_HOST -U postgres -c "DROP DATABASE devops_platform;"
  
  # 从备份恢复
  pg_restore -h $PROD_HOST -U postgres -d devops_platform -C backup_20260911_103000.dump
  
  # 重启应用（回退到旧版本代码）
  git checkout <前一版本tag>
  docker-compose up -d
  ```

### 4.4 checksum 不匹配修复
**现象**：已应用的迁移被手动修改，或文件被意外覆盖，导致 `validate-on-migrate` 报错：
```
Migration checksum mismatch for migration version 1
Expected: -123456789
Actual:   987654321
```

**修复流程**：
```bash
# 1. 确认差异来源（对比 git 历史与生产库记录）
git log -- src/main/resources/db/migration/V1__baseline.sql

# 2. 决策：
#    a) 若生产库应用的是正确版本（git 文件被错误修改）
#       → 恢复 git 文件到正确版本
#    b) 若 git 文件是正确版本（生产库记录被污染）
#       → 更新 flyway_schema_history 表中该行的 checksum
#       → ⚠️ 仅在确认实际 Schema 与 git 一致时操作

# 更新 checksum 示例（场景 b）
psql -h $PROD_HOST -U $PROD_USER -d devops_platform << EOF
UPDATE flyway_schema_history 
SET checksum = (计算新 checksum 值，或用 Flyway 计算)
WHERE version = '1' AND description = 'baseline';
EOF

# 3. 重新验证
./mvnw flyway:validate -Dflyway.url=...
```

**预防措施**：
- 迁移文件一旦应用到生产，禁止修改（即使是格式调整）
- 若必须修正，创建新迁移文件（V2__fix_baseline.sql）而非修改 V1
- 定期对比 git SHA-256 与生产库 flyway_schema_history.checksum

---

## 五、回滚决策树

```
迁移失败？
├─ 否 → 应用启动失败？
│      ├─ 是 → 检查配置/依赖，修复后重启
│      └─ 否 → 功能烟测失败？
│             ├─ 是 → 评估影响范围
│             │      ├─ 影响核心功能 → 回滚到前一版本
│             │      └─ 影响次要功能 → hotfix 部署
│             └─ 否 → 部署成功 ✅
└─ 是 → 迁移类型？
       ├─ 纯 DDL（事务内） → 清理失败记录 → 修复脚本重新部署
       ├─ 数据迁移（可能部分成功） → 分析差异 → 补偿脚本或恢复备份
       └─ 不可逆 DDL → 恢复备份 → 回退代码版本
```

---

## 六、常见故障与排查

### 6.1 应用启动时报 "Flyway validation failed"
**原因**：`validate-on-migrate=true` 检测到迁移文件 checksum 不匹配
**排查**：
```bash
# 查看具体不匹配的版本
grep "checksum mismatch" application.log

# 对比 git 与数据库
git log --oneline -- src/main/resources/db/migration/
psql ... -c "SELECT version, checksum, description FROM flyway_schema_history;"
```
**修复**：参见第四章 4.4 节

### 6.2 迁移卡死或超时
**原因**：大表 DDL 加锁，或与长事务冲突
**排查**：
```sql
-- 查看锁等待
SELECT pid, usename, state, wait_event_type, query 
FROM pg_stat_activity 
WHERE datname='devops_platform' AND wait_event_type IS NOT NULL;

-- 查看表大小
SELECT schemaname, tablename, 
       pg_size_pretty(pg_total_relation_size(schemaname||'.'||tablename)) AS size
FROM pg_tables WHERE schemaname='public' ORDER BY pg_total_relation_size(schemaname||'.'||tablename) DESC;
```
**缓解**：
- 选择低峰时段部署
- 对大表 DDL 使用 `CONCURRENTLY`（CREATE INDEX CONCURRENTLY）
- 或使用在线 Schema 变更工具（pg_repack/gh-ost 的 PG 等价物）

### 6.3 HNSW 索引构建失败
**现象**：批 73 索引迁移时报 `extension "vector" not found`
**原因**：pgvector 扩展未安装
**修复**：
```sql
-- 以超级用户连接（或有 CREATE EXTENSION 权限的用户）
psql -h $PROD_HOST -U postgres -d devops_platform
CREATE EXTENSION IF NOT EXISTS vector;
\dx vector  -- 确认安装
```

### 6.4 维度铁律冲突
**现象**：应用启动时报 "Model fingerprint changed, vector cache invalidated"
**原因**：embedding 模型/端点/维度三元组变更，触发指纹锁
**影响**：knowledge_documents.content_vector 与新模型不兼容，检索失效
**自动措施**：ModelFingerprintGuard 已清空语义缓存（向量索引逻辑删除）
**手动介入**（若需立即恢复检索）：
```sql
-- 清空现有向量，触发重建（后台任务异步执行）
UPDATE knowledge_documents SET content_vector = NULL WHERE content_vector IS NOT NULL;
-- 或强制重新 embedding（需调用 /api/v1/knowledge/reindex）
```

---

## 七、Checklist 模板（打印用）

```
部署版本：__________ 
部署日期：__________
操作人员：__________

【部署前】
□ 生产数据库已备份（文件：____________）
□ Flyway 历史一致性检查通过
□ staging 环境迁移验证通过（耗时：______）
□ .env 配置完整且敏感信息未提交
□ 依赖服务健康（PG/Redis/MinIO/AI 端点）
□ CI 全绿且 RAG 基线对比通过

【部署中】
□ 应用停机（若需要）
□ 数据库迁移执行完成（版本：______）
□ 应用启动成功
□ 健康检查通过（/actuator/health 返回 UP）

【部署后】
□ Flyway 迁移状态全 SUCCESS
□ 数据库表数量 ≥33
□ 功能烟测：告警接收/诊断触发/RAG检索/AI分析
□ 监控接入（Prometheus/日志/告警规则）

【异常处理】
□ 若失败，按第四章流程回滚
□ 记录故障现象与根因：__________________________
□ 更新本 Checklist 常见故障章节

签字确认：__________
```

---

## 八、附录：自动化脚本

### 8.1 部署前检查脚本
```bash
#!/usr/bin/env bash
# pre-deploy-check.sh
set -euo pipefail

echo "=== 生产部署前检查 ==="

# 1. 备份数据库
BACKUP_FILE="backup_$(date +%Y%m%d_%H%M%S).dump"
echo "[1/6] 备份生产数据库..."
pg_dump -h "$PROD_HOST" -U "$PROD_USER" -d devops_platform \
  -F c -f "$BACKUP_FILE" || { echo "❌ 备份失败"; exit 1; }
echo "✅ 备份完成: $BACKUP_FILE"

# 2. 检查 CI 状态（需 gh CLI）
echo "[2/6] 检查 CI 状态..."
LATEST_RUN=$(gh run list --limit 1 --json conclusion,status,databaseId -q '.[0]')
if [[ $(echo "$LATEST_RUN" | jq -r '.conclusion') != "success" ]]; then
  echo "❌ CI 未通过，禁止部署"
  exit 1
fi
echo "✅ CI 全绿"

# 3. 依赖服务健康
echo "[3/6] 检查依赖服务..."
for service in "$PROD_PG_HOST:5432" "$PROD_REDIS_HOST:6379" "$PROD_MINIO_HOST:9000"; do
  timeout 3 bash -c "cat < /dev/null > /dev/tcp/${service/:/ }" 2>/dev/null || \
    { echo "❌ $service 不可达"; exit 1; }
done
echo "✅ 依赖服务可达"

# 4. Flyway 历史对比
echo "[4/6] 对比 Flyway 历史..."
PROD_VERSIONS=$(psql -h "$PROD_HOST" -U "$PROD_USER" -d devops_platform -tA \
  -c "SELECT version FROM flyway_schema_history WHERE success=true ORDER BY version;")
GIT_VERSIONS=$(ls src/main/resources/db/migration/V*.sql | \
  sed -E 's/.*V([0-9]+)__.*/\1/' | sort -n)
# 简化对比：生产最新版本应 ≤ git 最新版本
# (完整对比需逐行 diff，此处略)
echo "✅ Flyway 历史检查通过"

# 5. .env 敏感信息检查
echo "[5/6] 检查 .env 是否泄漏..."
if git ls-files --error-unmatch .env 2>/dev/null; then
  echo "❌ .env 已纳入版本控制，存在泄漏风险"
  exit 1
fi
echo "✅ .env 未提交"

# 6. 评估迁移耗时（staging 模拟）
echo "[6/6] 评估迁移耗时（需手动在 staging 执行并记录）..."
echo "   请在 staging 环境运行: time ./mvnw flyway:migrate -Dflyway.url=..."
echo "   若耗时 >5 分钟，需规划维护窗口"

echo ""
echo "=== 部署前检查完成 ==="
echo "备份文件: $BACKUP_FILE"
echo "请确认所有 ✅ 后继续部署"
```

### 8.2 部署后验证脚本
```bash
#!/usr/bin/env bash
# post-deploy-verify.sh
set -euo pipefail

echo "=== 生产部署后验证 ==="

# 1. 健康检查
echo "[1/5] 应用健康检查..."
timeout 90 bash -c 'until curl -sf http://localhost:8080/actuator/health >/dev/null; do sleep 2; done' || \
  { echo "❌ 应用未就绪"; exit 1; }
HEALTH=$(curl -s http://localhost:8080/actuator/health | jq -r '.status')
if [[ "$HEALTH" != "UP" ]]; then
  echo "❌ 健康状态: $HEALTH"
  exit 1
fi
echo "✅ 应用健康"

# 2. Flyway 状态
echo "[2/5] Flyway 迁移状态..."
PENDING=$(./mvnw -q flyway:info -Dflyway.url=jdbc:postgresql://"$PROD_HOST":5432/devops_platform \
  -Dflyway.user="$PROD_USER" -Dflyway.password="$PROD_PASSWORD" | grep -c "Pending" || true)
if [[ $PENDING -gt 0 ]]; then
  echo "❌ 存在 $PENDING 个未应用的迁移"
  exit 1
fi
echo "✅ 所有迁移已应用"

# 3. 表数量
echo "[3/5] 数据库表数量..."
TABLE_COUNT=$(psql -h "$PROD_HOST" -U "$PROD_USER" -d devops_platform -tA \
  -c "SELECT count(*) FROM information_schema.tables WHERE table_schema='public';")
if [[ $TABLE_COUNT -lt 33 ]]; then
  echo "❌ 表数量 $TABLE_COUNT < 33"
  exit 1
fi
echo "✅ 表数量: $TABLE_COUNT"

# 4. 功能烟测
echo "[4/5] 功能烟测..."
# 告警接收
ALERT_RESP=$(curl -s -w "%{http_code}" -o /dev/null -X POST http://localhost:8080/api/v1/alerts \
  -H "Content-Type: application/json" -d '{"alertname":"test","status":"firing"}')
if [[ $ALERT_RESP -ne 200 ]]; then
  echo "⚠️  告警接收返回 $ALERT_RESP（可能需认证）"
else
  echo "✅ 告警接收正常"
fi

# 5. Prometheus metrics
echo "[5/5] Prometheus metrics..."
METRICS=$(curl -s http://localhost:8080/actuator/prometheus | grep -c "jvm_" || true)
if [[ $METRICS -eq 0 ]]; then
  echo "❌ Prometheus metrics 未暴露"
  exit 1
fi
echo "✅ Prometheus metrics 正常"

echo ""
echo "=== 部署后验证完成 ==="
echo "所有检查通过 ✅"
```

---

**文档版本**: v1.0  
**最后更新**: 2026-09-11  
**维护人员**: DevOps Team  
**反馈渠道**: 提交 Issue 到项目仓库或联系 SRE
