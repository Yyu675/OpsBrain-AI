#!/usr/bin/env bash
# 指标抓取令牌：生成 / 查看 / 轮换（2026-10-15）
#
# 背景：应用把 /actuator/**（health 除外）纳入登录校验，Prometheus 不会登录——
# 没有本文件对应的令牌，抓取恒 401：up{job="opsbrain"} 恒 0 → OpsBrainDown 常亮误报，
# 其余 6 条应用规则恒无数据。详见 infrastructure/guard/ScrapeTokenGuard 类注释。
#
# 用法：
#   bash scripts/monitoring-init-token.sh            # 生成（已存在则保留）并打印挂法
#   bash scripts/monitoring-init-token.sh --rotate   # 轮换（应用侧即时生效，无需重启）
#   bash scripts/monitoring-init-token.sh --path     # 只打印文件路径
#
# 设计取舍：令牌只在本地文件，**不进 Git、不进日志**（--rotate 只打印前 8 位指纹便于对账）。
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SECRET_DIR="$ROOT/monitoring/secrets"
SECRET_FILE="$SECRET_DIR/scrape_token"

MODE="${1:-}"

if [ "$MODE" = "--path" ]; then
  printf '%s\n' "$SECRET_FILE"
  exit 0
fi

mkdir -p "$SECRET_DIR"

if [ "$MODE" = "--rotate" ]; then
  # 轮换 = 覆盖文件。应用侧每次请求重读文件（ScrapeTokenGuard）、Prometheus 每轮抓取读一次，
  # 因此**双方都不需要重启**；唯一需要的是先把新令牌写进去（顺序无关，两边都读同一文件）。
  rm -f "$SECRET_FILE"
  echo "🔄 [scrape-token] 旧令牌已删除，正在生成新的……"
fi

if [ ! -s "$SECRET_FILE" ]; then
  # 32 字节随机 → 64 位十六进制：与 compose 里 scrape-token-init 的生成方式一致
  umask 077
  head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n' > "$SECRET_FILE"
  printf '\n' >> "$SECRET_FILE"
  echo "✅ [scrape-token] 已生成：$SECRET_FILE（权限 600，已 gitignore）"
else
  echo "ℹ️  [scrape-token] 已存在，保留不覆盖：$SECRET_FILE"
fi

chmod 600 "$SECRET_FILE" 2>/dev/null || true
FINGERPRINT="$(head -c 8 "$SECRET_FILE")"
echo "   指纹（前 8 位，用于对账，不是完整令牌）：$FINGERPRINT"

cat <<EOF

挂法（三处读同一份文件，任一处缺失都会表现成"抓不到指标"）：
  ① 后端（宿主机跑）：export SCRAPE_TOKEN_FILE=monitoring/secrets/scrape_token   # 启动前
  ② 后端（compose）：已由 docker-compose.yml 挂到 /run/secrets/scrape_token 并注入 SCRAPE_TOKEN_FILE
  ③ Prometheus：已由 compose 挂到 /etc/prometheus/secrets/scrape_token（prometheus.yml 的 credentials_file）

自测：
  # 不带令牌 → 401（预期）
  curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8088/ai/actuator/prometheus
  # 带令牌 → 200 + opsbrain_* 指标
  curl -s -H "Authorization: Bearer \$(cat $SECRET_FILE)" \\
       http://localhost:8088/ai/actuator/prometheus | grep -c '^opsbrain_'
  # 同一令牌访问别的 actuator 端点 → 仍 401（最小权限：令牌只管抓取面）
  curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer \$(cat $SECRET_FILE)" \\
       http://localhost:8088/ai/actuator/info
EOF
