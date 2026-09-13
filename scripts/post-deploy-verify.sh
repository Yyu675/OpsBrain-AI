#!/usr/bin/env bash
# post-deploy-verify.sh - 生产部署后验证脚本
# 用法: PROD_HOST=... PROD_USER=... PROD_PASSWORD=... APP_URL=http://localhost:8080 ./post-deploy-verify.sh

set -euo pipefail

echo "=== 生产部署后验证 ==="

# 环境变量校验
: "${PROD_HOST:?需要设置 PROD_HOST 环境变量}"
: "${PROD_USER:?需要设置 PROD_USER 环境变量}"
: "${PROD_PASSWORD:?需要设置 PROD_PASSWORD 环境变量}"
: "${APP_URL:=http://localhost:8080}"

FAILED_CHECKS=0

# 1. 应用健康检查
echo "[1/6] 应用健康检查 ($APP_URL) ..."
if timeout 90 bash -c "until curl -sf $APP_URL/actuator/health >/dev/null 2>&1; do sleep 2; done"; then
  HEALTH=$(curl -s "$APP_URL/actuator/health" | jq -r '.status' 2>/dev/null || echo "UNKNOWN")
  if [[ "$HEALTH" == "UP" ]]; then
    echo "✅ 应用健康状态: UP"
  else
    echo "❌ 应用健康状态: $HEALTH"
    ((FAILED_CHECKS++))
  fi
else
  echo "❌ 应用未在 90 秒内就绪"
  ((FAILED_CHECKS++))
fi

# 2. Flyway 迁移状态
echo "[2/6] Flyway 迁移状态..."
FLYWAY_INFO=$(mvn -q flyway:info \
  -Dflyway.url="jdbc:postgresql://$PROD_HOST:5432/devops_platform" \
  -Dflyway.user="$PROD_USER" \
  -Dflyway.password="$PROD_PASSWORD" 2>&1 || echo "")

PENDING_COUNT=$(echo "$FLYWAY_INFO" | grep -c "Pending" || true)
FAILED_COUNT=$(echo "$FLYWAY_INFO" | grep -c "Failed" || true)

if [[ $FAILED_COUNT -gt 0 ]]; then
  echo "❌ 存在 $FAILED_COUNT 个失败的迁移"
  echo "$FLYWAY_INFO" | grep "Failed"
  ((FAILED_CHECKS++))
elif [[ $PENDING_COUNT -gt 0 ]]; then
  echo "❌ 存在 $PENDING_COUNT 个未应用的迁移"
  ((FAILED_CHECKS++))
else
  SUCCESS_COUNT=$(echo "$FLYWAY_INFO" | grep -c "Success" || true)
  echo "✅ 所有迁移已应用 ($SUCCESS_COUNT 个)"
fi

# 3. 数据库表完整性
echo "[3/6] 数据库表完整性检查..."
TABLE_COUNT=$(PGPASSWORD="$PROD_PASSWORD" psql -h "$PROD_HOST" -U "$PROD_USER" -d devops_platform -tA \
  -c "SELECT count(*) FROM information_schema.tables WHERE table_schema='public';" 2>/dev/null || echo "0")

if [[ $TABLE_COUNT -ge 33 ]]; then
  echo "✅ 表数量: $TABLE_COUNT (≥33)"
else
  echo "❌ 表数量: $TABLE_COUNT (<33，预期基线至少 33 张表)"
  ((FAILED_CHECKS++))
fi

# 关键表存在性
CRITICAL_TABLES=("ticket" "alert_events" "diagnosis_sessions" "knowledge_documents" "flyway_schema_history")
MISSING_TABLES=()

for table in "${CRITICAL_TABLES[@]}"; do
  EXISTS=$(PGPASSWORD="$PROD_PASSWORD" psql -h "$PROD_HOST" -U "$PROD_USER" -d devops_platform -tA \
    -c "SELECT EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema='public' AND table_name='$table');" \
    2>/dev/null || echo "f")

  if [[ "$EXISTS" != "t" ]]; then
    MISSING_TABLES+=("$table")
  fi
done

if [[ ${#MISSING_TABLES[@]} -eq 0 ]]; then
  echo "✅ 关键表完整 (${CRITICAL_TABLES[*]})"
else
  echo "❌ 缺失关键表: ${MISSING_TABLES[*]}"
  ((FAILED_CHECKS++))
fi

# 4. 功能烟测
echo "[4/6] 功能烟测..."

# 4.1 告警接收端点（可能需要认证，仅测试可达性）
ALERT_STATUS=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$APP_URL/api/v1/alerts" \
  -H "Content-Type: application/json" \
  -d '{"alertname":"deployment-test","status":"firing","labels":{"severity":"info"}}' 2>/dev/null || echo "000")

if [[ $ALERT_STATUS =~ ^(200|201|401|403)$ ]]; then
  echo "✅ 告警端点可达 (HTTP $ALERT_STATUS)"
else
  echo "⚠️  告警端点返回 HTTP $ALERT_STATUS（可能是配置问题）"
fi

# 4.2 健康检查详细组件
HEALTH_COMPONENTS=$(curl -s "$APP_URL/actuator/health" | jq -r '.components | keys | join(", ")' 2>/dev/null || echo "unknown")
echo "   健康检查组件: $HEALTH_COMPONENTS"

if [[ "$HEALTH_COMPONENTS" =~ "db" ]] && [[ "$HEALTH_COMPONENTS" =~ "redis" ]]; then
  echo "✅ 数据库与 Redis 组件健康"
else
  echo "⚠️  未检测到 db/redis 组件（检查 actuator 配置）"
fi

# 5. Prometheus metrics 暴露
echo "[5/6] Prometheus metrics 检查..."
METRICS_COUNT=$(curl -s "$APP_URL/actuator/prometheus" 2>/dev/null | grep -c "^jvm_" || echo "0")

if [[ $METRICS_COUNT -gt 0 ]]; then
  echo "✅ Prometheus metrics 已暴露 ($METRICS_COUNT 个 JVM 指标)"
else
  echo "❌ Prometheus metrics 未暴露"
  ((FAILED_CHECKS++))
fi

# 6. 日志完整性（检查最近 10 行是否有 ERROR）
echo "[6/6] 应用日志检查..."
if [[ -f "logs/application.log" ]]; then
  ERROR_COUNT=$(tail -100 logs/application.log | grep -c "ERROR" || true)
  if [[ $ERROR_COUNT -gt 5 ]]; then
    echo "⚠️  最近 100 行日志中有 $ERROR_COUNT 个 ERROR（建议排查）"
    tail -20 logs/application.log | grep "ERROR" | head -3
  else
    echo "✅ 日志正常（ERROR 数量: $ERROR_COUNT）"
  fi
else
  echo "⚠️  未找到 logs/application.log（可能日志路径不同）"
fi

# 总结
echo ""
echo "=== 部署后验证完成 ==="
if [[ $FAILED_CHECKS -eq 0 ]]; then
  echo "✅ 所有关键检查通过，部署成功"
  exit 0
else
  echo "❌ 有 $FAILED_CHECKS 项检查失败，请排查后重新验证"
  exit 1
fi
