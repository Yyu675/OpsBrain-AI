#!/usr/bin/env bash
# =============================================================================
# 变更事件上报（CI/CD 流水线回调脚本）
#
# 用途：流水线在部署/发布/配置变更完成后调用本脚本，把变更写进平台的
#   sys_change_event——诊断引擎据此把「昨晚 22:00 有一次发布」列为证据，
#   告警工单的自动诊断从「证据不足」升档（实测：一条变更即 INSUFFICIENT→WEAK）。
#
# 幂等：同一 (source, externalId) 重复上报会被服务端忽略（CI 重发属正常）。
#
# 用法（GitLab CI 示例）：
#   report-change:
#     stage: deploy
#     script:
#       - ./report-change.sh
#     variables:
#       OPSBRAIN_BASE: "http://opsbrain:8088/ai"
#       OPSBRAIN_USER: "ci-bot"          # 需 ADMIN 或 OPS 角色
#       OPSBRAIN_PASS: "$CI_BOT_PASSWORD"   # 用 CI 的 masked variable，别写死
#       SERVICE_NAME: "order-service"
#       CHANGE_TYPE: "deploy"            # deploy|config|scale|rollback|…（自由文本）
#       SUMMARY: "deploy ${CI_COMMIT_SHORT_SHA} to prod"
#       EXTERNAL_ID: "${CI_PIPELINE_ID}-${CI_JOB_ID}"
#
# 环境变量：
#   OPSBRAIN_BASE   平台地址（含 /ai 前缀），默认 http://localhost:8088/ai
#   OPSBRAIN_USER / OPSBRAIN_PASS   登录凭据（二选一：或直接给 OPSBRAIN_TOKEN）
#   OPSBRAIN_TOKEN  已登录的 Sa-Token（省去登录请求；token 有过期时间，长期任务建议用账密）
#   SERVICE_NAME    被变更的服务名（必填——诊断按它关联告警）
#   CHANGE_TYPE     变更类型，默认 deploy
#   SUMMARY         一句话说明（必填）
#   CHANGE_TIME     ISO-8601 本地时间，默认当前时间
#   SOURCE          来源系统标识，默认 ci
#   EXTERNAL_ID     幂等键（建议 流水线ID-任务ID）；缺省时每次上报都新增
# =============================================================================
set -euo pipefail

BASE="${OPSBRAIN_BASE:-http://localhost:8088/ai}"
SERVICE_NAME="${SERVICE_NAME:?必须指定 SERVICE_NAME（诊断按服务名关联告警）}"
SUMMARY="${SUMMARY:?必须指定 SUMMARY（一句话说明这次变更）}"
CHANGE_TYPE="${CHANGE_TYPE:-deploy}"
CHANGE_TIME="${CHANGE_TIME:-$(date +%Y-%m-%dT%H:%M:%S)}"
SOURCE="${SOURCE:-ci}"
EXTERNAL_ID="${EXTERNAL_ID:-}"

# ---- 登录拿 token（已有 OPSBRAIN_TOKEN 则跳过） ----
TOKEN="${OPSBRAIN_TOKEN:-}"
if [ -z "$TOKEN" ]; then
  : "${OPSBRAIN_USER:?需提供 OPSBRAIN_USER/OPSBRAIN_PASS 或 OPSBRAIN_TOKEN}"
  : "${OPSBRAIN_PASS:?需提供 OPSBRAIN_USER/OPSBRAIN_PASS 或 OPSBRAIN_TOKEN}"
  LOGIN_RESP=$(curl -s -m 10 -X POST "$BASE/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"username\":\"$OPSBRAIN_USER\",\"password\":\"$OPSBRAIN_PASS\"}")
  TOKEN=$(printf '%s' "$LOGIN_RESP" | grep -o '"token":"[^"]*"' | cut -d'"' -f4)
  [ -n "$TOKEN" ] || { echo "登录失败: $LOGIN_RESP" >&2; exit 1; }
fi

# ---- 组 JSON（python 不可依赖；jq 缺位时用 printf 拼装，转义最小集） ----
esc() { printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'; }
if command -v jq >/dev/null 2>&1; then
  BODY=$(jq -n \
    --arg serviceName "$SERVICE_NAME" --arg changeType "$CHANGE_TYPE" \
    --arg summary "$SUMMARY" --arg changeTime "$CHANGE_TIME" \
    --arg source "$SOURCE" --arg externalId "$EXTERNAL_ID" \
    '{serviceName:$serviceName, changeType:$changeType, summary:$summary,
      changeTime:$changeTime, source:$source, externalId:($externalId|select(.!="")//null) | with_entries(select(.value != null))}')
else
  BODY="{\"serviceName\":\"$(esc "$SERVICE_NAME")\",\"changeType\":\"$(esc "$CHANGE_TYPE")\",\"summary\":\"$(esc "$SUMMARY")\",\"changeTime\":\"$(esc "$CHANGE_TIME")\",\"source\":\"$(esc "$SOURCE")\""
  [ -n "$EXTERNAL_ID" ] && BODY="$BODY,\"externalId\":\"$(esc "$EXTERNAL_ID")\""
  BODY="$BODY}"
fi

# ---- 上报 ----
HTTP_CODE=$(curl -s -m 15 -o /tmp/opsbrain-change-resp.json -w "%{http_code}" \
  -X POST "$BASE/api/changes" \
  -H "Content-Type: application/json" -H "satoken: $TOKEN" -d "$BODY")

if [ "$HTTP_CODE" != "200" ]; then
  echo "变更上报失败 HTTP $HTTP_CODE: $(cat /tmp/opsbrain-change-resp.json)" >&2
  exit 1
fi
cat /tmp/opsbrain-change-resp.json
echo
echo "✅ 变更已登记: $SERVICE_NAME ($CHANGE_TYPE) @ $CHANGE_TIME"
