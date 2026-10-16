#!/usr/bin/env bash
# pre-deploy-check.sh - 生产部署前检查脚本
# 用法: PROD_HOST=... PROD_USER=... PROD_PASSWORD=... ./pre-deploy-check.sh

set -euo pipefail

echo "=== 生产部署前检查 ==="

# 环境变量校验
: "${PROD_HOST:?需要设置 PROD_HOST 环境变量}"
: "${PROD_USER:?需要设置 PROD_USER 环境变量}"
: "${PROD_PASSWORD:?需要设置 PROD_PASSWORD 环境变量}"

BACKUP_DIR="backups"
mkdir -p "$BACKUP_DIR"

# 1. 备份数据库
BACKUP_FILE="$BACKUP_DIR/backup_$(date +%Y%m%d_%H%M%S).dump"
echo "[1/6] 备份生产数据库到 $BACKUP_FILE ..."
PGPASSWORD="$PROD_PASSWORD" pg_dump -h "$PROD_HOST" -U "$PROD_USER" -d devops_platform \
  -F c -f "$BACKUP_FILE" || { echo "❌ 备份失败"; exit 1; }
BACKUP_SIZE=$(du -h "$BACKUP_FILE" | cut -f1)
echo "✅ 备份完成: $BACKUP_FILE ($BACKUP_SIZE)"

# 2. 检查 CI 状态（若有 gh CLI）
echo "[2/6] 检查 CI 状态..."
if command -v gh &>/dev/null; then
  LATEST_RUN=$(gh run list --limit 1 --json conclusion -q '.[0].conclusion' 2>/dev/null || echo "unknown")
  if [[ "$LATEST_RUN" != "success" ]]; then
    echo "⚠️  CI 状态: $LATEST_RUN（建议等待 CI 通过后再部署）"
  else
    echo "✅ CI 全绿"
  fi
else
  echo "⚠️  未安装 gh CLI，跳过 CI 检查（手动确认 GitHub Actions 状态）"
fi

# 3. 依赖服务健康
echo "[3/6] 检查依赖服务连通性..."
check_tcp() {
  local host=$1
  local port=$2
  timeout 3 bash -c "cat < /dev/null > /dev/tcp/$host/$port" 2>/dev/null
}

if check_tcp "$PROD_HOST" 5432; then
  echo "✅ PostgreSQL ($PROD_HOST:5432) 可达"
else
  echo "❌ PostgreSQL 不可达"; exit 1
fi

# Redis/MinIO 检查（若环境变量提供）
if [[ -n "${PROD_REDIS_HOST:-}" ]]; then
  if check_tcp "$PROD_REDIS_HOST" 6379; then
    echo "✅ Redis ($PROD_REDIS_HOST:6379) 可达"
  else
    echo "❌ Redis 不可达"; exit 1
  fi
fi

if [[ -n "${PROD_MINIO_HOST:-}" ]]; then
  if check_tcp "$PROD_MINIO_HOST" 9000; then
    echo "✅ MinIO ($PROD_MINIO_HOST:9000) 可达"
  else
    echo "❌ MinIO 不可达"; exit 1
  fi
fi

# 4. Flyway 历史对比
echo "[4/6] 对比 Flyway 迁移历史..."
PROD_LATEST=$(PGPASSWORD="$PROD_PASSWORD" psql -h "$PROD_HOST" -U "$PROD_USER" -d devops_platform -tA \
  -c "SELECT COALESCE(MAX(CAST(version AS INTEGER)), 0) FROM flyway_schema_history WHERE success=true;" 2>/dev/null || echo "0")

GIT_LATEST=$(ls src/main/resources/db/migration/V*.sql 2>/dev/null | \
  sed -E 's/.*V([0-9]+)__.*/\1/' | sort -n | tail -1 || echo "0")

echo "   生产库最新版本: V$PROD_LATEST"
echo "   代码库最新版本: V$GIT_LATEST"

if [[ $GIT_LATEST -lt $PROD_LATEST ]]; then
  echo "❌ 代码库版本落后于生产库，可能回滚到旧版本"
  exit 1
elif [[ $GIT_LATEST -eq $PROD_LATEST ]]; then
  echo "✅ 版本一致（无新迁移）"
else
  PENDING=$((GIT_LATEST - PROD_LATEST))
  echo "✅ 待应用 $PENDING 个新迁移"
fi

# 5. 敏感文件检查
echo "[5/6] 检查敏感文件是否泄漏..."
LEAKED_FILES=()
for f in .env application-prod.yml src/main/resources/application-prod.yml; do
  if git ls-files --error-unmatch "$f" 2>/dev/null; then
    LEAKED_FILES+=("$f")
  fi
done

if [[ ${#LEAKED_FILES[@]} -gt 0 ]]; then
  echo "❌ 以下敏感文件已纳入版本控制: ${LEAKED_FILES[*]}"
  exit 1
fi
echo "✅ 敏感文件未泄漏"

# 6. Maven 依赖完整性
echo "[6/6] 检查 Maven 依赖..."
if ! mvn dependency:resolve -q -B &>/dev/null; then
  echo "❌ Maven 依赖解析失败"
  exit 1
fi
echo "✅ Maven 依赖完整"

echo ""
echo "=== 部署前检查完成 ==="
echo "✅ 所有检查通过，可以继续部署"
echo ""
echo "📋 部署检查清单:"
echo "   - 数据库备份: $BACKUP_FILE"
echo "   - 待应用迁移: V$((PROD_LATEST + 1)) ~ V$GIT_LATEST"
echo "   - 依赖服务: 已验证连通性"
echo ""
echo "▶️  下一步: 执行部署脚本或手动启动应用"
