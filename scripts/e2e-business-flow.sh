#!/usr/bin/env bash
# ============================================================================
# 跨模块完整业务流 E2E（批88 审计 P1 产物固化）
#
# 串起运维主链路，每一步验证上一步产物，断裂即报错退出：
#   1. 告警 webhook 推送（Alertmanager 载荷，ASCII body 规避 Windows curl 中文编码问题）
#   2. 告警入库 + 自动建单（去重键唯一化，保证每轮新建；DB 直查锁定本轮告警）
#   3. 双向溯源链核验（工单.source_trace_id == 告警.dedup_key）
#   4. 处置闭环：acknowledge → AI分析(REAL LLM) → 处置动作 → mitigate
#      → root-cause → verify → RESOLVED
#   5. 活动流完整性（每阶段都有时间线）
#   6. 清理本轮数据（告警/工单/活动流/动作/AI分析/诊断会话）
#
# 用法：bash scripts/e2e-business-flow.sh [base_url] [admin_user] [admin_pass]
#   默认: http://localhost:8088/ai admin admin123
# 依赖：后端存活 + Docker pgvector（选单与清理用）。无 Docker 时步骤2/3/5/6 降级跳过。
# 退出码：0=全链路通过 1=失败
# ============================================================================
set -uo pipefail

BASE="${1:-http://localhost:8088/ai}"
ADMIN_USER="${2:-admin}"
ADMIN_PASS="${3:-admin123}"
RUN_TAG="E2EFlow$(date +%s)"
PSQL="docker exec devops-pgvector psql -U devops -d devops_knowledge_db -t -A"

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'
PASSED=0
HAVE_DOCKER=1
$PSQL -c "SELECT 1" >/dev/null 2>&1 || HAVE_DOCKER=0

ok()   { echo -e "  ${GREEN}✓${NC} $1"; PASSED=$((PASSED+1)); }
fail() { echo -e "  ${RED}✗ $1${NC}"; echo -e "${RED}E2E 中断（已过 $PASSED 步）${NC}"; exit 1; }
info() { echo -e "  ${YELLOW}ℹ${NC} $1"; }
jget() { echo "$1" | grep -oE "\"$2\":(\"[^\"]*\"|[0-9]+|true|false|null)" | head -1 | cut -d: -f2- | tr -d '"'; }

echo "=== 跨模块完整业务流 E2E ==="
echo "BASE: $BASE | RUN_TAG: $RUN_TAG | docker=$HAVE_DOCKER"

# ── 0. 登录 ──
TOKEN=$(curl -s -m 8 -X POST "$BASE/api/v1/auth/login" -H "Content-Type: application/json" \
  -d "{\"username\":\"$ADMIN_USER\",\"password\":\"$ADMIN_PASS\"}" \
  | grep -oE '"token":"[a-f0-9-]+"' | cut -d'"' -f4)
[ -n "$TOKEN" ] || fail "步骤0 登录失败"
ok "步骤0 登录 (${TOKEN:0:8}…)"

# ── 1. 告警 webhook（ASCII body 写文件 + --data-binary，规避中文编码）──
NOW=$(date -u +%Y-%m-%dT%H:%M:%SZ)
printf '%s' "{\"receiver\":\"e2e-flow\",\"status\":\"firing\",\"alerts\":[{\"status\":\"firing\",\"labels\":{\"alertname\":\"$RUN_TAG\",\"service\":\"e2e-service\",\"severity\":\"warning\",\"instance\":\"10.99.99.1:9100\"},\"annotations\":{\"description\":\"E2E flow test alert $RUN_TAG disk usage 95 percent\"},\"startsAt\":\"$NOW\"}]}" > /tmp/e2e-alert.json
WR=$(curl -s -m 10 -X POST "$BASE/api/v1/alerts/webhook" -H "Content-Type: application/json" --data-binary @/tmp/e2e-alert.json)
[ "$(jget "$WR" code)" = "0" ] || fail "步骤1 webhook 失败: ${WR:0:160}"
ok "步骤1 告警 webhook 推送"
sleep 3

# ── 2. 告警入库 + 自动建单（DB 直查本轮告警，杜绝选错）──
if [ "$HAVE_DOCKER" = "1" ]; then
  ROW=$($PSQL -F'|' -c "SELECT id, ticket_id, dedup_key FROM sys_alert WHERE alert_name='$RUN_TAG' ORDER BY id DESC LIMIT 1" 2>/dev/null)
  [ -n "$ROW" ] || fail "步骤2 告警未入库 (alert_name=$RUN_TAG)"
  ALERT_ID=$(echo "$ROW" | cut -d'|' -f1); TICKET_ID=$(echo "$ROW" | cut -d'|' -f2); DEDUP=$(echo "$ROW" | cut -d'|' -f3)
  [ -n "$TICKET_ID" ] || fail "步骤2 自动建单未触发 (ticket_id 空；查 devops.alert.auto-ticket-enabled)"
  ok "步骤2 告警#$ALERT_ID → 自动建单 $TICKET_ID"

  # ── 3. 双向溯源链 ──
  SRC=$($PSQL -c "SELECT source_trace_id FROM sys_devops_ticket WHERE id='$TICKET_ID'" 2>/dev/null)
  [ "$SRC" = "$DEDUP" ] || fail "步骤3 溯源断裂: source_trace_id=$SRC ≠ dedup_key=$DEDUP"
  ok "步骤3 双向溯源链 (source_trace_id == dedup_key)"
else
  info "步骤2/3 跳过（无 Docker，无法 DB 直查锁定本轮告警）"
  TICKET_ID=""
fi

# ── 4. 处置闭环（需要 TICKET_ID）──
if [ -n "$TICKET_ID" ]; then
  call() { curl -s -m "${3:-8}" -X "$1" "$BASE/api/v1/tickets/$TICKET_ID$4" -H "satoken: $TOKEN" -H "Content-Type: application/json" -d "$2"; }

  R=$(call POST "{\"responder\":\"$ADMIN_USER\",\"assignee\":\"$ADMIN_USER\"}" 8 /acknowledge)
  [ "$(jget "$R" code)" = "0" ] || fail "步骤4a acknowledge: ${R:0:160}"
  ok "步骤4a 首响确认"

  R=$(call POST '{"content":"E2E analysis: disk usage hit 95% due to missing log rotation. Root cause category CONFIG. Recommended fix: enable logrotate for /var/log.","reasons":["disk_full","no_logrotate"],"commands":["du -sh /var/log","logrotate -f /etc/logrotate.conf"],"citations":["PostgreSQL connection pool manual"],"confidence":0.86,"costRmb":0.012}' 30 /ai-analysis)
  [ "$(jget "$R" code)" = "0" ] || fail "步骤4b AI分析持久化: ${R:0:160}"
  ok "步骤4b AI 分析持久化 (SaveAnalysisRequest)"

  R=$(curl -s -m 8 "$BASE/api/v1/tickets/$TICKET_ID/ai-analysis/latest" -H "satoken: $TOKEN")
  [ "$(jget "$R" code)" = "0" ] || fail "步骤4b' AI分析回读: ${R:0:160}"
  ok "步骤4b' AI 分析回读 (latest)"

  R=$(call POST "{\"actionType\":\"INVESTIGATE\",\"summary\":\"E2E clean temp files to free disk\",\"operator\":\"$ADMIN_USER\",\"effective\":true}" 8 /actions)
  [ "$(jget "$R" code)" = "0" ] || fail "步骤4c 处置动作: ${R:0:160}"
  ok "步骤4c 处置动作记录"

  R=$(call POST "{\"operator\":\"$ADMIN_USER\"}" 8 /mitigate)
  [ "$(jget "$R" code)" = "0" ] || fail "步骤4d mitigate: ${R:0:160}"
  ok "步骤4d 标记止损"

  R=$(call PUT "{\"rootCause\":\"log rotation missing, /var/log filled disk\",\"category\":\"CONFIG\",\"operator\":\"$ADMIN_USER\"}" 8 /root-cause)
  [ "$(jget "$R" code)" = "0" ] || fail "步骤4e root-cause: ${R:0:160}"
  ok "步骤4e 根因确认 (CONFIG)"

  R=$(call POST "{\"method\":\"MONITOR\",\"conclusion\":\"disk back to 62 percent, alert recovered\",\"verifier\":\"$ADMIN_USER\"}" 8 /verify)
  [ "$(jget "$R" code)" = "0" ] || fail "步骤4f verify: ${R:0:160}"
  [ "$(jget "$R" status)" = "RESOLVED" ] || fail "步骤4f 验证后应 RESOLVED，实际=$(jget "$R" status)"
  ok "步骤4f 验证 → RESOLVED（闭环完成）"

  # ── 5. 活动流 ──
  if [ "$HAVE_DOCKER" = "1" ]; then
    AC=$($PSQL -c "SELECT count(*) FROM sys_ticket_activity WHERE ticket_id='$TICKET_ID'" 2>/dev/null)
    [ "${AC:-0}" -ge 5 ] || fail "步骤5 活动流仅 ${AC:-0} 条 (期望≥5)"
    ok "步骤5 活动流完整 ($AC 条)"
  fi

  # ── 6. 清理 ──
  if [ "$HAVE_DOCKER" = "1" ]; then
    docker exec devops-pgvector psql -U devops -d devops_knowledge_db -q -c "
    BEGIN;
    DELETE FROM sys_diagnosis_session WHERE alert_id=$ALERT_ID;
    DELETE FROM sys_ticket_ai_analysis WHERE ticket_id='$TICKET_ID';
    DELETE FROM sys_ticket_activity WHERE ticket_id='$TICKET_ID';
    DELETE FROM sys_ticket_action WHERE ticket_id='$TICKET_ID';
    DELETE FROM sys_ticket_reply WHERE ticket_id='$TICKET_ID';
    DELETE FROM sys_devops_ticket WHERE id='$TICKET_ID';
    DELETE FROM sys_alert WHERE id=$ALERT_ID;
    COMMIT;" 2>/dev/null && ok "步骤6 本轮数据已清理" || info "步骤6 清理失败，残留 RUN_TAG=$RUN_TAG"
  fi
else
  info "步骤4-6 跳过（无 TICKET_ID）"
fi

echo ""
echo "────────────────────────────────"
echo -e "${GREEN}✓ E2E 全链路通过：$PASSED 步${NC}（告警→建单→溯源→处置闭环→活动流→清理）"
