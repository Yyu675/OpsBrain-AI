#!/usr/bin/env bash
# =============================================================================
# 告警风暴负载发生器（真窗批件:路线图 S5-4.1「告警风暴 100 条/秒」演练用具)
#
# 用途:对 /api/v1/alerts/webhook 打封闭负载,验证:
#   ① 稳态吞吐(默认 100 RPS × 120s,12,000 发) app 不抖;
#   ② dedup 严律(同一战火指纹反复递交 → 仅 occurrence 递增不新建单);
#   ③ 分源严律(不同 service → 各自独立建单);
#   ④ 回包时延分位(p50/p95/p99 + 状态码账)。
#
# 前置:
#   app 已起栈(docker compose up -d);
#   X-Webhook-Token 已按 application.yml § webhook.token 配置并同步本脚本;
#   注意澜:默认滑窗闸 300 发/60s ✅——压 100 RPS 需临时调高闸门,否则要打的是
#     429 拒闸演练(这也是有价值的用例,见联合测试清单 T-08)。
#
# 用法:
#   BASE=http://localhost:8080/ai TOKEN=xxx RPS=100 DURATION=120 ./alert_loadgen.sh
#   BASE=http://localhost:8080/ai TOKEN=xxx RPS=300 DURATION=60 MODE=reject ./alert_loadgen.sh
#
# 产物:当前目录 loadgen-<ts>.csv 逐发行(time,code,cost_s);汇总打印至 stdout。
# =============================================================================
set -euo pipefail

BASE="${BASE:-http://localhost:8080/ai}"
TOKEN="${TOKEN:?必须提供 X-Webhook-Token: TOKEN=xxx}"
RPS="${RPS:-100}"
DURATION="${DURATION:-120}"
MODE="${MODE:-storm}"          # storm=同指纹风暴 | storm-mixed=风暴掺新源 | reject=无 token 拒闸演练
OUT="loadgen-$(date +%Y%m%d-%H%M%S).csv"
TSV="$(mktemp)"
# xargs 派生的 bash -c 子 shell 只继承「导出」的变量与函数——下列全部必须 export,
# 否则并发工人产出空环境(打向空 BASE/无 token,观察面即失真)。
export BASE TOKEN MODE TSV RPS

echo -n >"$OUT"
echo "== 负载参数: RPS=$RPS DURATION=${DURATION}s MODE=$MODE 目标≈$((RPS*DURATION)) 发 =="

payload() {
  local n="$1" svc="lab-svc-a" inst="10.99.0.1"
  if [ "$MODE" = "storm-mixed" ]; then
    # 十分之一掺为新源——测分源建单严律
    if [ $((n % 10)) -eq 0 ]; then svc="lab-svc-$((n % 5 + 2))"; inst="10.99.1.$((n % 5 + 2))"; fi
  fi
  printf '{"receiver":"opsbrain-webhook","status":"firing","alerts":[{"status":"firing","labels":{"alertname":"LabHighCpuLoad","severity":"critical","service":"%s","module":"app","instance":"%s:9100"},"annotations":{"summary":"loadgen synthetic cpu > 90","description":"真窗压测合成告警,可整体会收"},"startsAt":"%s"}]}' \
    "$svc" "$inst" "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
}

fire() {
  local n="$1" body t1 t2 code
  body="$(payload "$n")"
  t1=$(date +%s%N)
  if [ "$MODE" = "reject" ]; then
    code=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE/api/v1/alerts/webhook" \
      -H "Content-Type: application/json" -d "$body")
  else
    code=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE/api/v1/alerts/webhook" \
      -H "Content-Type: application/json" -H "X-Webhook-Token: $TOKEN" -d "$body")
  fi
  t2=$(date +%s%N)
  echo "$(( (t2-t1)/1000000 )),$code" >>"$TSV"
}
export -f fire payload 2>/dev/null || true

N=$((RPS * DURATION))
echo "-- 发射中 $(date +%T) --"
START=$(date +%s)
# 开放回路:每秒一批批拉打单发,发出即不等控制节奏; 并发件备 $RPS
seq 1 "$N" | xargs -P "$RPS" -I{} bash -c 'fire {}'
END=$(date +%s)

cat "$TSV" | sort >>"$OUT"; rm -f "$TSV"
ELAPSED=$((END - START)); [ "$ELAPSED" -lt 1 ] && ELAPSED=1
TOTAL=$(wc -l <"$OUT")
echo "== 汇总 =="
echo "总发 $TOTAL 发 / ${ELAPSED}s = 实测 $(awk "BEGIN{printf \"%.1f\", $TOTAL/$ELAPSED}") RPS(有效吞吐受服务端限速闸影响属预期)"
awk -F, '{c[$2]++} END {for (k in c) printf "状态码 %s : %d 发 (%.1f%%)\n", k, c[k], c[k]*100/'"$TOTAL"'}' "$OUT" | sort
echo "时延分位(ms):"
# 分位计算走外部 sort -n + 定点抽行,不依赖 gawk 的 asort(macOS/BSD awk 亦可)。
cut -d, -f1 "$OUT" | sort -n > "${OUT}.sorted"
awk -v n="$TOTAL" 'NR==int((n+1)/2){p50=$1} NR==int(n*0.95)+1{p95=$1} NR==int(n*0.99)+1{p99=$1} END{p95=(p95=="")?$1:p95; p99=(p99=="")?$1:p99; printf "p50=%d p95=%d p99=%d max=%d\n", p50, p95, p99, $1}' "${OUT}.sorted"
rm -f "${OUT}.sorted"
echo "逐发流水: $OUT"
echo ""
echo "== 判定建议 =="
echo "storm 模式后查工单数: 风暴指纹应只消化为 occurrence 升高+少量新单(按掺入新源比例),"
echo "  SELECT count(*) FROM sys_devops_ticket WHERE title LIKE '%LabHighCpuLoad%';"
echo "  SELECT alert_name, occurrence_count FROM sys_alert WHERE alert_name='LabHighCpuLoad' ORDER BY id;"
echo "429 出现 = 限流闸在工作(预期拒闸路径可比照);若 MODE=storm 下 2xx 占比 <90% 请查并发轨道与闸门配置。"
