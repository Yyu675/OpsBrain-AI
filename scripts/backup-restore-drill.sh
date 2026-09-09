#!/usr/bin/env bash
# ============================================================================
# T3 备份恢复演练（真窗件 · 路线图 §13.1「可生产」验收:备份脚本存在且有演练记录）
#
# 演练流程（破坏性验证,只在 dev 中间件栈上做）:
#   1. 全量备份: pg_dump 结构+数据 + MinIO 对象清单(mc ls 导出)
#   2. 破坏模拟: 截断核心业务表(工单/告警/知识文档)
#   3. 恢复: psql 导回备份
#   4. 一致性断言: 恢复后行数 = 备份时行数(逐表)
#   5. 演练记录落盘 docs/09-operations/ backup-restore-演练记录.md
#
# 用法: bash scripts/backup-restore-drill.sh
# 前置: dev 中间件栈在跑(pgvector 25432 / minio 19000),后端可不在跑
# ============================================================================
set -euo pipefail

PG_CONTAINER="devops-pgvector"
PG_USER="devops"
PG_DB="devops_knowledge_db"
PG_PASS="devops_password"
STAMP=$(date +%Y%m%d-%H%M%S)
BACKUP_DIR="target/backup-drill-$STAMP"
TABLES="sys_devops_ticket sys_alert sys_knowledge_doc sys_knowledge_chunk sys_knowledge_category sys_user"

mkdir -p "$BACKUP_DIR"
echo "=== T3 备份恢复演练 @ $STAMP ==="
echo "备份目录: $BACKUP_DIR"

# ---------- 1. 全量备份 ----------
echo "--- [1/4] pg_dump 全量备份 ---"
docker exec -e PGPASSWORD="$PG_PASS" "$PG_CONTAINER" \
  pg_dump -U "$PG_USER" -d "$PG_DB" --no-owner --no-privileges --clean --if-exists \
  > "$BACKUP_DIR/full-backup.sql" \
  && echo "✓ 备份文件: $(wc -l < "$BACKUP_DIR/full-backup.sql") 行, $(du -h "$BACKUP_DIR/full-backup.sql" | cut -f1)"

# MinIO 对象清单(附件/归档桶的「有什么」账本;对象本体在 compose 卷里,
# 生产环境用 mc mirror 同步到异地——本演练记录清单作恢复核对基准)
docker exec devops-minio mc ls --recursive local/ > "$BACKUP_DIR/minio-manifest.txt" 2>/dev/null \
  && echo "✓ MinIO 对象清单: $(wc -l < "$BACKUP_DIR/minio-manifest.txt") 项" \
  || echo "ℹ MinIO 清单不可得(容器别名或 mc 配置差异),对象本体在 compose 卷——生产走 mc mirror"

# ---------- 2. 备份时行数账 ----------
echo "--- [2/4] 备份时行数账 ---"
: > "$BACKUP_DIR/row-counts-before.txt"
for t in $TABLES; do
  c=$(docker exec "$PG_CONTAINER" psql -U "$PG_USER" -d "$PG_DB" -tA -c "SELECT COUNT(*) FROM $t;")
  echo "$t=$c" | tee -a "$BACKUP_DIR/row-counts-before.txt"
done

# ---------- 3. 破坏 + 恢复 ----------
echo "--- [3/4] 破坏模拟: 截断 $TABLES ---"
for t in $TABLES; do
  docker exec "$PG_CONTAINER" psql -U "$PG_USER" -d "$PG_DB" -c "TRUNCATE $t CASCADE;" >/dev/null
  c=$(docker exec "$PG_CONTAINER" psql -U "$PG_USER" -d "$PG_DB" -tA -c "SELECT COUNT(*) FROM $t;")
  echo "  $t → $c (已清空)"
done

echo "--- [3/4] 恢复: 导回备份 ---"
docker exec -i -e PGPASSWORD="$PG_PASS" "$PG_CONTAINER" \
  psql -U "$PG_USER" -d "$PG_DB" -v ON_ERROR_STOP=1 -f - < "$BACKUP_DIR/full-backup.sql" > "$BACKUP_DIR/restore.log" 2>&1 \
  && echo "✓ 恢复执行完成(详见 restore.log)"

# ---------- 4. 一致性断言 ----------
echo "--- [4/4] 一致性断言: 恢复后行数 vs 备份时 ---"
PASS=1
: > "$BACKUP_DIR/row-counts-after.txt"
for t in $TABLES; do
  before=$(grep "^$t=" "$BACKUP_DIR/row-counts-before.txt" | cut -d= -f2)
  after=$(docker exec "$PG_CONTAINER" psql -U "$PG_USER" -d "$PG_DB" -tA -c "SELECT COUNT(*) FROM $t;")
  echo "$t=$after" >> "$BACKUP_DIR/row-counts-after.txt"
  if [ "$before" = "$after" ]; then
    echo "  ✓ $t: $before == $after"
  else
    echo "  ✗ $t: 备份 $before ≠ 恢复 $after"
    PASS=0
  fi
done

# 应用层探活(可选:后端在跑时)
APP_PROBE=$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 http://localhost:8088/ai/actuator/health 2>/dev/null || echo "n/a")
echo "应用层探活: /actuator/health → ${APP_PROBE}(200=恢复后应用仍健康)"

if (( PASS )); then
  echo ""
  echo "✓ T3 演练通过: 备份→破坏→恢复→一致性 五表全对账"
  echo "  备份产物: $BACKUP_DIR/full-backup.sql ($(du -h "$BACKUP_DIR/full-backup.sql" | cut -f1))"
  echo "  演练记录将写入 docs/09-operations/(由调用方/批处理落档)"
else
  echo "✗ 演练失败——行数对账不齐"
  exit 1
fi
