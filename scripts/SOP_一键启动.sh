#!/usr/bin/env bash
# ==============================================================================
# T2 一键启动脚本（真窗件 · 路线图 S5-2「部署文档」/批 70 跑通实测步骤沉淀）
#
# 从零拉起完整开发栈: 中间件(compose) → 后端(spring-boot:run) → 前端(vite dev)
# 每步带探活断言,任一步失败即停并给出诊断提示——不静默带病继续。
#
# 用法:
#   bash scripts/SOP_一键启动.sh          # 全栈拉起(后台起后端/前端,适合本地开发)
#   AI_MODE=MOCK 默认;REAL 需 ALIBABA_API_KEY 环境变量
#
# 已验证路径(批 70 实测): compose 7 容器 ~1min 全健康 → 后端 ~22s 启动 →
# health UP → 登录/四域 API code=0 → SSE 流式 → Vite ~10s ready → 代理 200。
# ==============================================================================
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
BACKEND_LOG="${TMPDIR:-/tmp}/opsbrain-backend.log"
FRONTEND_LOG="${TMPDIR:-/tmp}/opsbrain-frontend.log"
PORT="${BACKEND_PORT:-8088}"
FRONT_PORT="5173"

step() { echo ""; echo "=== [ $1 ] $2 ==="; }
die()  { echo "✗ $1"; echo "  诊断提示: $2"; exit 1; }

# ---------- 0. 前置自检 ----------
step "0/4" "前置自检"
command -v docker >/dev/null || die "Docker 未安装" "安装 Docker Desktop 后重试"
docker info >/dev/null 2>&1 || die "Docker daemon 未运行" "启动 Docker Desktop 后重试"
if [ -n "${JAVA_HOME:-}" ]; then
  "$JAVA_HOME/bin/java" -version 2>&1 | head -1
else
  java -version 2>&1 | head -1 || die "无 JDK" "本仓库要求 JDK 21(AGENTS §技术栈),设置 JAVA_HOME"
fi
echo "AI_MODE=${AI_MODE:-MOCK}(未设时默认 MOCK)"

# ---------- 1. 中间件栈 ----------
step "1/4" "起中间件栈(compose: pgvector/redis/minio/prometheus/alertmanager/node-exporter/adminer)"
docker compose -f docker-compose.dev.yml up -d >/dev/null 2>&1 \
  || die "compose 起栈失败" "查看日志: docker compose -f docker-compose.dev.yml logs"
for i in $(seq 1 30); do
  healthy=$(docker inspect --format '{{.State.Health.Status}}' devops-pgvector 2>/dev/null)
  [ "$healthy" = "healthy" ] && break
  sleep 2
done
[ "$healthy" = "healthy" ] \
  && echo "✓ pgvector healthy" \
  || die "pgvector 60s 内未 healthy" "docker logs devops-pgvector"

# ---------- 2. 后端 ----------
step "2/4" "起后端(8088, ${AI_MODE:-MOCK} 模式)"
if netstat -ano 2>/dev/null | grep ":$PORT" | grep -q LISTEN; then
  echo "ℹ $PORT 已被占用——假定后端已在跑,跳过启动"
else
  # Windows/MSYS 下 mvnw 后台起;日志落盘便于失败诊断
  ( JAVA_HOME="${JAVA_HOME:-}" AI_MODE="${AI_MODE:-MOCK}" SPRING_PROFILES_ACTIVE=dev \
    ./mvnw -B -ntp spring-boot:run >"$BACKEND_LOG" 2>&1 & )
  echo "  后端启动中(日志: $BACKEND_LOG)..."
fi
BACKEND_UP=0
for i in $(seq 1 40); do
  code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 2 "http://localhost:$PORT/ai/actuator/health" 2>/dev/null)
  [ "$code" = "200" ] && BACKEND_UP=1 && break
  sleep 3
done
[ "$BACKEND_UP" = "1" ] \
  && echo "✓ 后端 /actuator/health 200" \
  || die "后端 120s 内未就绪" "tail -50 $BACKEND_LOG;常见:端口占用/DB 未就绪/REAL 模式缺 ALIBABA_API_KEY"

# 健康明细(确认非 503)
curl -s "http://localhost:$PORT/ai/actuator/health" | grep -o '"status":"[A-Z]*"' | head -1

# ---------- 3. 登录探活(应用层真实断言,不只 TCP) ----------
step "3/4" "登录探活(admin 默认凭据)"
LOGIN_CODE=$(curl -s -X POST "http://localhost:$PORT/ai/api/v1/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}' \
  | node -pe "try{JSON.parse(require('fs').readFileSync(0)).code}catch(e){'parse-fail'}" 2>/dev/null)
[ "$LOGIN_CODE" = "0" ] \
  && echo "✓ 登录成功(鉴权链/DB/Redis 三层全通)" \
  || die "登录失败(code=$LOGIN_CODE)" "检查后端日志;admin 种子由 AuthDataInitializer 自动补"

# ---------- 4. 前端 ----------
step "4/4" "起前端(Vite dev, $FRONT_PORT)"
if netstat -ano 2>/dev/null | grep ":$FRONT_PORT" | grep -q LISTEN; then
  echo "ℹ $FRONT_PORT 已被占用——假定前端已在跑,跳过"
else
  ( cd devops-platform-frontend && npm run dev >"$FRONTEND_LOG" 2>&1 & )
  echo "  前端启动中(日志: $FRONTEND_LOG)..."
fi
FRONT_UP=0
for i in $(seq 1 20); do
  code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 2 "http://localhost:$FRONT_PORT/" 2>/dev/null)
  [ "$code" = "200" ] && FRONT_UP=1 && break
  sleep 3
done
# 代理链验证(前端→后端)
PROXY=$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 -X POST \
  "http://localhost:$FRONT_PORT/ai/api/v1/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}' 2>/dev/null)
[ "$FRONT_UP" = "1" ] && [ "$PROXY" = "200" ] \
  && echo "✓ 前端 ready + 代理链通(vite → $PORT)" \
  || die "前端未就绪或代理断" "tail -30 $FRONTEND_LOG;确认 5173 与后端 $PORT"

echo ""
echo "✅ 全栈就绪: http://localhost:$FRONT_PORT  (admin / admin123)"
echo "   中间件: PG 25432 / Redis 26379 / MinIO 19000 / Prometheus 29090"
echo "   停止: 后端 kill 端口进程;前端 Ctrl-C;栈 docker compose -f docker-compose.dev.yml down"
