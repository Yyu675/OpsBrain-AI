#!/usr/bin/env bash
# ============================================================================
# API 全接口回归脚本（批88 审计产物固化）
#
# 用途：对环境中的全部 API 端点做健康检查与鉴权验证，
#       按模块分组，输出通过/失败/跳过明细。
#
# 用法：
#   bash scripts/api-regression.sh [base_url] [admin_user] [admin_pass]
#   默认: http://localhost:8088/ai admin admin123
#
# 退出码：0=全部通过 1=存在失败
# ============================================================================
set -euo pipefail

BASE="${1:-http://localhost:8088/ai}"
ADMIN_USER="${2:-admin}"
ADMIN_PASS="${3:-admin123}"

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'
PASS=0; FAIL=0; SKIP=0

# ── helpers ──

get_token() {
  curl -s -m 8 -X POST "$BASE/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"username\":\"$ADMIN_USER\",\"password\":\"$ADMIN_PASS\"}" \
    | grep -oE '"token":"[a-f0-9-]+"' | cut -d'"' -f4
}

run() {
  local label="$1" method="$2" url="$3" expect="${4:-200}" token="${5:-}" body="${6:-}"
  local extra=""; [ -n "$token" ] && extra="-H satoken:$token"
  local b=""; [ -n "$body" ] && b="-H Content-Type:application/json -d $body"
  local resp; resp=$(curl -s -m 6 -w "\n%{http_code}" -X "$method" "$BASE$url" $extra $b 2>/dev/null) || true
  local code=$(echo "$resp" | tail -1 | tr -d '[:space:]')
  local ms
  ms=$(echo "$resp" | head -1 | grep -oE '"code":[0-9]+|"status":"[^"]*"' | tr '\n' ' ' | head -c 60)
  if [ "$code" = "$expect" ]; then
    echo -e "  ${GREEN}PASS${NC} $method $url → $code $ms"; PASS=$((PASS+1))
  else
    echo -e "  ${RED}FAIL${NC} $method $url → $code (expected $expect) $ms"; FAIL=$((FAIL+1))
  fi
}

run_anon() {
  run "$1" "$2" "$3" "$4" "" ""
}

# ── main ──

echo "=== API 全接口回归 ==="
echo "BASE: $BASE  |  User: $ADMIN_USER"
echo ""

TOKEN=$(get_token)
if [ -z "$TOKEN" ]; then
  echo -e "${RED}FATAL: 无法获取 token，后端是否正常？${NC}"
  exit 1
fi
echo "Token: ${TOKEN:0:12}..."
echo ""

# ==================== M1: 对话 / SSE ====================
echo "── M1: 对话 / SSE ──"
run  "SSE流式POST"  POST  "/api/v1/chat/stream" 200 "$TOKEN" '{"query":"ping"}'
run  "SSE流式GET"   GET   "/api/v1/chat/stream?query=ping" 200 "$TOKEN"

# ==================== M5: 知识库 ====================
echo "── M5: 知识库 ──"
run  "文档列表"     GET   "/api/v1/knowledge/docs?page=1&size=3" 200 "$TOKEN"
run  "分类聚合"     GET   "/api/v1/knowledge/docs/categories" 200 "$TOKEN"
run  "热门标签"     GET   "/api/v1/knowledge/docs/tags/hot" 200 "$TOKEN"
run  "统计信息"     GET   "/api/v1/knowledge/stats" 200 "$TOKEN"
run  "切片浏览"     GET   "/api/v1/knowledge/chunks?page=1&size=5" 200 "$TOKEN"

# ==================== M7: 工单 ====================
echo "── M7: 工单 ──"
run  "工单列表"     GET   "/api/v1/tickets?page=1&size=5" 200 "$TOKEN"
run  "工单统计"     GET   "/api/v1/tickets/stats" 200 "$TOKEN"
run  "标签热榜"     GET   "/api/v1/tickets/tags/hot" 200 "$TOKEN"

# ==================== M8: 告警 ====================
echo "── M8: 告警 ──"
run  "告警列表"     GET   "/api/v1/alerts?page=1&size=5" 200 "$TOKEN"

# ==================== 治理 / 审计 / Tracing ====================
echo "── 治理 & 审计 ──"
run  "审计-操作日志" GET   "/api/v1/audit/operations?page=1&size=3" 200 "$TOKEN"
run  "审计-AI调用"  GET   "/api/v1/audit/ai-calls?page=1&size=3" 200 "$TOKEN"
run  "Agent统计"    GET   "/api/v1/agent/traces/stats" 200 "$TOKEN"
run  "Saga待介入"   GET   "/api/v1/saga/attention?limit=5" 200 "$TOKEN"
run  "审批列表"     GET   "/api/v1/approvals?page=1&size=3" 200 "$TOKEN"
run  "审批待处理数" GET   "/api/v1/approvals/pending/count" 200 "$TOKEN"
run  "看板概览"     GET   "/api/v1/dashboard/overview" 200 "$TOKEN"
run  "团队"         GET   "/api/v1/users" 200 "$TOKEN"
run  "治理-策略"    GET   "/api/v1/governance/risk-policies" 200 "$TOKEN"
run  "治理-动作"    GET   "/api/v1/governance/actions" 200 "$TOKEN"
run  "自愈-执行列表" GET  "/api/v1/healing/executions?page=1&size=3" 200 "$TOKEN"
run  "自愈-执行器"  GET   "/api/v1/healing/executors" 200 "$TOKEN"

# ==================== 鉴权矩阵 ====================
echo "── 鉴权矩阵 ──"
run_anon "匿名→工单"          GET   "/api/v1/tickets" 401
run_anon "匿名→知识库"        GET   "/api/v1/knowledge/docs" 401
run_anon "匿名→Saga"          GET   "/api/v1/saga/attention" 401
run_anon "匿名→审批"          GET   "/api/v1/approvals" 401
run_anon "匿名→审计"          GET   "/api/v1/audit/operations" 401

# ==================== 健康检查 ====================
echo "── 健康检查 ──"
run_anon "业务健康"            GET   "/api/v1/health" 200

# ==================== 汇总 ====================
echo ""
TOTAL=$((PASS + FAIL + SKIP))
echo "────────────────────────────────"
echo -e "通过: ${GREEN}$PASS${NC}  /  失败: ${RED}$FAIL${NC}  /  跳过: ${YELLOW}$SKIP${NC}  /  总计: $TOTAL"
if [ "$FAIL" -gt 0 ]; then
  echo -e "${RED}✗ 存在 $FAIL 个失败端点，请排查${NC}"
  exit 1
else
  echo -e "${GREEN}✓ 全部 $PASS 个端点通过${NC}"
fi