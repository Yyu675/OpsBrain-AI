#!/usr/bin/env bash
# ============================================================================
# FR-2.5 全局风暴模式演练（状态机链路：进入→聚合→退出→摘要自动恢复）
#
# 与 scripts/loadtest-alert-storm.sh 的分工：
#   那个测「webhook 吞吐/限流闸/去重幂等」（100 条/秒 压接收链路）；
#   这个测「风暴模式状态机」（速率超阈值后低级别告警聚合成一张摘要单）。
#   两者不重复——吞吐证明「收得下」，本演练证明「多了会收敛」。
#
# ⚠️ 前置（重要）：webhook 入口有 60 条/分/IP 的限流（RateLimitFilter），
#    而生产风暴进入阈值是 100 条/分——直接灌永远够不到阈值。
#    所以演练必须用「低阈值实例」跑：
#      ALERT_STORM_ENTER_RATE=30 ALERT_STORM_EXIT_RATE=10 \
#        mvn -o spring-boot:run -Dspring-boot.run.profiles=dev
#    演练验证的是状态机逻辑，绝对速率无关；40 条/min 即可触发（<60 限流）。
#
# 用法：bash scripts/drill-storm-mode.sh [条数] [端口]
#   默认 40 条 × 8088。跑完自动清理演练数据（storm-drill-svc / storm-summary / 摘要工单）。
# ============================================================================
set -euo pipefail

COUNT="${1:-40}"
PORT="${2:-8088}"
BASE="http://localhost:${PORT}/ai"
TOKEN="${ALERT_WEBHOOK_SECRET:?需要先 export .env 里的 ALERT_WEBHOOK_SECRET}"

echo "=== FR-2.5 风暴模式演练：批量推入 ${COUNT} 条互异 P3 告警（service=storm-drill-svc）==="

# 一批互异 firing 告警：一个 POST 用 1 个限流配额，但按告警条数计入风暴速率窗口
NOW=$(date -u +%Y-%m-%dT%H:%M:%SZ)
{
  printf '{"receiver":"storm-drill","status":"firing","alerts":['
  for i in $(seq 1 "$COUNT"); do
    [ "$i" -gt 1 ] && printf ','
    printf '{"status":"firing","labels":{"alertname":"StormDrill%03d","severity":"P3","service":"storm-drill-svc"},"annotations":{"summary":"storm drill %d"},"startsAt":"%s","endsAt":"0001-01-01T00:00:00Z","fingerprint":"storm-drill-%03d"}' "$i" "$i" "$NOW" "$i"
  done
  printf ']}'
} > /tmp/_storm_drill_payload.json

curl -s -X POST "$BASE/api/v1/alerts/webhook/drill" \
  -H "Content-Type: application/json" -H "X-Webhook-Token: $TOKEN" \
  --data-binary @/tmp/_storm_drill_payload.json -o /dev/null -w "推送 HTTP=%{http_code}\n"

SATOKEN=$(curl -s -X POST "$BASE/api/v1/auth/login" -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}' \
  | node -pe "JSON.parse(require('fs').readFileSync(0)).data.token")

echo "--- 推送后风暴状态（期望 active=true，ratePerMin≥阈值）---"
curl -s "$BASE/api/v1/alerts/storm-status" -H "satoken: $SATOKEN"; echo

echo "--- 聚合结果（期望：1 条摘要告警 occurrence=${COUNT}，只建 1 张摘要工单）---"
docker exec devops-pgvector psql -U devops -d devops_knowledge_db -c \
  "SELECT id,alert_name,level,status,occurrence_count,ticket_id FROM sys_alert WHERE dedup_key='storm-summary';"

echo "--- 等待 70s 让速率滑窗排空 → 自动退出 + 摘要自动恢复 ---"
sleep 70
echo "--- 退出后风暴状态（期望 active=false）---"
curl -s "$BASE/api/v1/alerts/storm-status" -H "satoken: $SATOKEN"; echo
echo "--- 摘要告警（期望 status=RESOLVED）---"
docker exec devops-pgvector psql -U devops -d devops_knowledge_db -t -c \
  "SELECT id,status,occurrence_count,(resolved_at IS NOT NULL) AS resolved FROM sys_alert WHERE dedup_key='storm-summary';"

echo "--- 清理演练数据 ---"
TRACE=$(docker exec devops-pgvector psql -U devops -d devops_knowledge_db -tA -c \
  "SELECT trace_id FROM sys_diagnosis_session WHERE alert_id IN (SELECT id FROM sys_alert WHERE dedup_key='storm-summary' OR service='storm-drill-svc');" | head -1)
docker exec devops-pgvector psql -U devops -d devops_knowledge_db -c "
BEGIN;
DELETE FROM sys_diagnosis_evidence WHERE trace_id='${TRACE}';
DELETE FROM sys_diagnosis_hypothesis WHERE session_trace_id='${TRACE}';
DELETE FROM sys_diagnosis_session WHERE trace_id='${TRACE}';
DELETE FROM sys_ticket_activity WHERE ticket_id IN (SELECT ticket_id FROM sys_alert WHERE dedup_key='storm-summary');
DELETE FROM sys_devops_ticket WHERE id IN (SELECT ticket_id FROM sys_alert WHERE dedup_key='storm-summary');
DELETE FROM sys_alert WHERE dedup_key='storm-summary';
DELETE FROM sys_alert WHERE service='storm-drill-svc';
COMMIT;"
rm -f /tmp/_storm_drill_payload.json
echo "✓ 演练完成并已清理"
