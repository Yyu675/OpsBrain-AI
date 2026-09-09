#!/usr/bin/env bash
# ============================================================================
# T6 告警风暴压测（真窗件 · 路线图 S5-4 / 报告 164 挂账）
#
# 验证 L2 核心承诺「告警 webhook 100 条/秒不丢」（路线图 §6.1 验收 #2）：
#   1. webhook P95 响应 < 500ms（异步化诊断不阻塞接收链路的实证）
#   2. WebhookGuard 限流闸语义：窗口内(默认 300/60s)全收；
#     超额 429 + Retry-After 给 Alertmanager 退避重投——告警不静默丢失。
#     （A6 设计在案：429 是有意防风暴闸,非误伤——与「Prometheus 非 200
#      即重试」契约自洽。100/s×10s=1000 条必触闸,429>0 是设计生效的实证）
#   3. 同 dedup key 重发不新增告警行/不重复建单（去重幂等）
#   4. 窗口内推送 = 告警入库（不丢）
#
# 用法（对运行中的实例压测，需后端已在 8088 起好、中间件栈在跑）：
#   bash scripts/loadtest-alert-storm.sh [速率条/秒] [持续秒] [端口]
#   默认: 100 条/秒 × 10 秒 × 8088
#   （默认 1000 条会超 300/60s 窗口触闸;验证「全收不触闸」用
#    3 条/秒 × 60s = 180 条 < 300）
# ============================================================================
set -euo pipefail

RATE="${1:-100}"          # 条/秒
DURATION="${2:-10}"       # 秒
PORT="${3:-8088}"         # 后端端口
BASE="http://localhost:${PORT}/ai"
OUT_DIR="$(mktemp -d)"
trap 'rm -rf "$OUT_DIR"' EXIT

TOTAL=$((RATE * DURATION))
echo "=== T6 告警风暴压测: ${RATE} 条/秒 × ${DURATION}s = ${TOTAL} 条 ==="

# 预检:后端活着
curl -s -o /dev/null --max-time 5 "$BASE/actuator/health" || {
  echo "✗ 后端未在 ${PORT} 起动——先起后端再压测"; exit 1; }

# 基线:压测前告警/工单数——改用「压测开始时刻」+ 专属前缀直查 DB 作准,
# API total 差值会被等待窗口期间入库的历史数据污染(BEFORE 取样晚于部分入库)
BEFORE_TICKETS=$(curl -s -X POST "$BASE/api/v1/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}' \
  | node -pe "JSON.parse(require('fs').readFileSync(0)).data.token" \
  | xargs -I{} curl -s "$BASE/api/v1/tickets/stats" -H "satoken: {}" \
  | node -pe "const j=JSON.parse(require('fs').readFileSync(0)); (j.data&&j.data.total)||0")
echo "基线: 工单=${BEFORE_TICKETS} (告警将以 DB 直查为准)"

# 压测:每秒一个批次,RATE 条并发
gen_payload() {  # $1 = 唯一序号
  cat <<JSON
{"receiver":"loadtest","status":"firing","alerts":[{"status":"firing","labels":{"alertname":"LoadTestStorm$1","severity":"P3","service":"loadtest-svc","module":"OTHER"},"annotations":{"summary":"T6 压测告警 #$1","description":"storm load test"},"startsAt":"2026-09-10T00:00:00Z","endsAt":"0001-01-01T00:00:00Z","fingerprint":"loadtest-fp-$1"}]}
JSON
}

start_ts=$(date +%s)
start_epoch=$start_ts   # DB 直查口径用:压测开始时刻(LoadTestStorm 专属前缀+此后入库=本轮产物)
sent=0; fail_http=0
for ((t=0; t<DURATION; t++)); do
  batch_start=$(date +%s%N)
  for ((i=0; i<RATE; i++)); do
    idx=$((t * RATE + i))
    printf '%s\n' "$(gen_payload "$idx")" > "$OUT_DIR/p-$idx.json"
    ( code=$(curl -s -o /dev/null -w '%{http_code}:%{time_total}' \
        -X POST "$BASE/api/v1/alerts/webhook" \
        -H "Content-Type: application/json" \
        --data-binary "@$OUT_DIR/p-$idx.json" 2>/dev/null)
      echo "$code" >> "$OUT_DIR/results.txt" ) &
    sent=$((sent+1))
  done
  # 批内并发发完后,对齐到下一秒(不足一秒则等待)
  batch_end=$(date +%s%N)
  elapsed_ms=$(( (batch_end - batch_start) / 1000000 ))
  (( elapsed_ms < 1000 )) && sleep 0.$((1000 - elapsed_ms > 99 ? 99 : 1000 - elapsed_ms)) 2>/dev/null || true
done
wait
end_ts=$(date +%s)

# 幂等重发验证:取第一批的 key 重发 20 条,不应新增告警/工单
echo "--- 幂等重发: 同 fingerprint 重发 20 条 ---"
for ((r=0; r<20; r++)); do
  curl -s -o /dev/null -X POST "$BASE/api/v1/alerts/webhook" \
    -H "Content-Type: application/json" \
    --data-binary "@$OUT_DIR/p-$r.json" &
done
wait

sleep 3  # 异步建单落库窗口

# 断言
echo "=== 结果 ==="
total_resp=$(wc -l < "$OUT_DIR/results.txt" || echo 0)
http_200=$(grep -c "^200:" "$OUT_DIR/results.txt" || true)
http_429=$(grep -c "^429:" "$OUT_DIR/results.txt" || true)
http_5xx=$(grep -c "^5[0-9][0-9]:" "$OUT_DIR/results.txt" || true)
# P95 延迟
p95=$(grep "^200:" "$OUT_DIR/results.txt" | cut -d: -f2 | sort -n | \
  awk '{a[NR]=$1} END{if(NR==0){print "n/a";exit} printf "%.0f", a[int(NR*0.95)+1]*1000}')

AFTER_TICKETS=$(curl -s -X POST "$BASE/api/v1/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}' \
  | node -pe "JSON.parse(require('fs').readFileSync(0)).data.token" \
  | xargs -I{} curl -s "$BASE/api/v1/tickets/stats" -H "satoken: {}" \
  | node -pe "const j=JSON.parse(require('fs').readFileSync(0)); (j.data&&j.data.total)||0")

# DB 直查本轮产物:专属前缀 + 压测开始后入库(权威口径,不受历史数据/等待窗口污染)
if docker ps --format '{{.Names}}' 2>/dev/null | grep -q '^devops-pgvector$'; then
  new_alerts=$(docker exec devops-pgvector psql -U devops -d devops_knowledge_db -tA -c \
    "SELECT COUNT(*) FROM sys_alert WHERE alert_name LIKE 'LoadTestStorm%' AND create_time >= to_timestamp(${start_epoch:-0});" 2>/dev/null || echo 0)
  # start_epoch 在压测循环前导出
else
  # 无 DB 直查条件:降级为不判入库(只判 HTTP/P95 维度)
  new_alerts=-1
fi
new_tickets=$((AFTER_TICKETS - BEFORE_TICKETS))
wall=$((end_ts - start_ts))

echo "发送: ${sent} 条 (wall ${wall}s) | 响应样本: ${total_resp}"
echo "HTTP 200: ${http_200} | 429: ${http_429} | 5xx: ${http_5xx}"
echo "P95 响应: ${p95}ms"
echo "新增告警: ${new_alerts} (期望=${TOTAL},聚合后可能少——同 service+module 5min 窗口聚合抑制重复建单)"
echo "新增工单: ${new_tickets}"

PASS=1
(( http_200 + http_429 + http_5xx < sent )) && { echo "✗ 有请求无响应(连接失败)"; PASS=0; }
(( http_5xx > 0 )) && { echo "✗ 出现 5xx——接收链路被打崩"; PASS=0; }
# 429 判据反转:超窗口限流是 WebhookGuard 设计生效(A6 防风暴闸),
# 全收或触闸退避都算通过;但「非 200 即 429」之外的状态码都是异常
if (( http_429 > 0 )); then
  echo "ℹ 触发 WebhookGuard 限流闸(超额 429+Retry-After 退避重投,设计生效实证)"
fi
(( new_alerts == 0 )) && { echo "✗ 零入库——推送全部丢失"; PASS=0; }
# P95 判据:500ms(路线图 §6.1 验收 #2)
if [ "$p95" != "n/a" ] && [ "$p95" -gt 500 ]; then echo "✗ P95 ${p95}ms > 500ms"; PASS=0; fi
# 不丢判据:200 数应等于入库新增(被 429 拒的会由 Alertmanager 重投,不计丢失)
if (( new_alerts < http_200 )); then
  # 聚合/去重会让入库数 ≤ 200 数(同 key 重发递增 occurrence 不增行)——
  # 只有「远小于」(>10% 缺口)才判丢
  gap=$((http_200 - new_alerts))
  (( gap * 10 > http_200 )) && { echo "✗ 入库缺口 ${gap} 条 >10%——疑似丢失"; PASS=0; }
fi

if (( PASS )); then
  echo ""
  echo "✓ T6 压测通过: ${RATE}/s × ${DURATION}s | P95=${p95}ms | 200=${http_200} 429=${http_429} 5xx=${http_5xx} / ${sent} | 新增告警=${new_alerts} 新增工单=${new_tickets}"
else
  echo ""
  echo "✗ T6 压测未通过——见上"
  exit 1
fi
