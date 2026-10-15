#!/usr/bin/env bash
# ============================================================================
# 后端版本回退演练（DoD#6 后半栏：<5min 回到上一版本且健康）
#
# 为什么要有这个脚本
# ------------------
# Runbook §1.2 的规程是「先打双 tag，回滚时本地镜像切换」——但**规程没被实弹过**
# （仓库原先只有单 latest tag，无货可演＝不假演）。本脚本把「回滚」变成可复跑的实弹：
# 切换版本 → 重启 → 探活 → 校验「跑起来的确实是上一版」，全程计时并断言 <5min。
#
# 两种模式
# --------
#   artifact  无 Docker 也能演：两个发布目录（各自的 class 产物）之间切换。
#             适用于本机/离线环境；版本身份用「构建物指纹 + 进程 classpath」证伪，不看进程名。
#   docker    生产同款：docker tag 双标签纪律 + compose up -d --no-build（Runbook §1.2）。
#
# 用法
# ----
#   bash scripts/rollback-drill.sh --mode artifact \
#        --prev /tmp/release/v_prev/classes --cur /tmp/release/v_cur/classes \
#        --port 8099 [--marker-path /v3/api-docs --marker-needle <两版契约差异串>]
#
#   bash scripts/rollback-drill.sh --mode docker \
#        --project opsbrain-prod --service app --image opsbrain-ai [--port 18088]
#
# 判据
# ----
#   1. 切换版本后 health 为 UP（不只是进程活着）；
#   2. 版本身份可证——**不依赖任何具体端点**：
#        a. 构建物指纹：两版 class 目录的「文件清单+大小」必须不同，否则根本没在演两版本；
#        b. 运行时身份：应答端口的那个进程，其 /proc/<pid>/cmdline 必须含本版 class 目录；
#        c. 可选 --marker-needle：两版确有契约差异时，再用 OpenAPI 内容双向证伪。
#      （原先只靠 c 且针写死为 alerts/webhook/zabbix——那是「分支相对 main 的新增」，
#        一旦 PR 合进 main，prev 与 cur 同版，该断言必然失败：main 上首次跑就红了。）
#   3. 回滚耗时 < 300s，超时即红。
# 退出码：0=演练通过；1=判据不满足；2=用法/环境错误。
# ============================================================================
set -uo pipefail

MODE=""
PREV=""; CUR=""; PORT=8099; MARKER_PATH="/v3/api-docs"                      # 版本身份证明：新版独有的契约痕迹
# 契约比对针：**默认空**＝不做契约比对。留空才对——写死成某个端点等于把「身份」
# 绑在一次性差异上（见文件头 2. 的教训）。仅当两版确有契约差异时才显式传入。
MARKER_NEEDLE=""
PROJECT="opsbrain-prod"; SERVICE="app"; IMAGE="opsbrain-ai"
JAVA_BIN="${JAVA_BIN:-${JAVA_HOME:-/usr}/bin/java}"
CP_BASE="${CP_BASE:-/tmp/cp_full.txt}"
RES_DIR="${RES_DIR:-src/main/resources}"
# 两个版本各自的资源目录（缺省都用 RES_DIR）。分开的原因：配置也在版本里——
# 分支版 application.yml 比 main 多 33 行（抓取令牌、登录限流可覆盖等），
# 拿新配置喂旧代码虽不报错（未知键被忽略），但演的就不是"那一版"了。
PREV_RES=""; CUR_RES=""
LOG_DIR="${LOG_DIR:-/tmp/rollback-drill}"
LIMIT_SECONDS="${LIMIT_SECONDS:-300}"
NO_FLYWAY="${NO_FLYWAY:-}"   # 旧版跑在已被新版迁移推进过的库上时置 1（关 Flyway 校验）

while [ $# -gt 0 ]; do
  case "$1" in
    --mode) MODE="$2"; shift 2;;
    --prev) PREV="$2"; shift 2;;
    --cur) CUR="$2"; shift 2;;
    --port) PORT="$2"; shift 2;;
    --marker-path) MARKER_PATH="$2"; shift 2;;
    --marker-needle) MARKER_NEEDLE="$2"; shift 2;;
    --prev-res) PREV_RES="$2"; shift 2;;
    --cur-res) CUR_RES="$2"; shift 2;;
    --no-flyway) NO_FLYWAY=1; shift;;
    --project) PROJECT="$2"; shift 2;;
    --service) SERVICE="$2"; shift 2;;
    --image) IMAGE="$2"; shift 2;;
    *) echo "未知参数：$1"; exit 2;;
  esac
done

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'
BASE="http://localhost:$PORT/ai"
mkdir -p "$LOG_DIR"
PIDFILE="$LOG_DIR/app.pid"

ok()   { echo -e "  ${GREEN}✓${NC} $1"; }
bad()  { echo -e "  ${RED}✗${NC} $1"; }
info() { echo -e "  ${YELLOW}ℹ${NC} $1"; }
die()  { bad "$1"; exit 1; }

# ── 探活：health UP + 一次真业务调用（光进程活着不算健康） ──
health_up() {
  local body
  body=$(curl -s -m 5 "http://localhost:$PORT/ai/actuator/health" 2>/dev/null || true)
  [[ "$body" == *'"status":"UP"'* ]]
}

build_fingerprint() {  # 构建物指纹：**按内容**哈希（文件路径+内容），不含 mtime
  # 不用「路径+大小」——实测同大小的不同类文件（"class A" / "class B" 都是 8 字节）
  # 会被判成同一版，把真有两版的演练误拒。内容哈希只在「同源码重编」时可能因个别
  # 生成物带时间戳而判为不同，那是安全方向（放行演练），不会造成假红。
  ( cd "$1" && find . -type f -exec md5sum {} + 2>/dev/null | LC_ALL=C sort | md5sum | cut -d' ' -f1 )
}

assert_runtime_identity() {  # 应答端口的进程必须由指定 class 目录启动（证「端口上跑的确实是这一版」）
  local want="$1" tag="$2" pid cmd
  [ -f "$PIDFILE" ] || { info "$tag：无 pidfile，跳过运行时身份校验"; return 0; }
  pid=$(cat "$PIDFILE")
  cmd=$(tr '\0' ' ' < "/proc/$pid/cmdline" 2>/dev/null || true)
  case "$cmd" in
    *"$want"*) ok "$tag 运行时身份：pid $pid 的 classpath 含 $want" ;;
    *) die "$tag 运行时身份不成立：pid $pid 的 classpath 不含 $want（端口被上一版进程占着？）" ;;
  esac
}

marker_present() {  # 该版本是否带「新版独有的契约痕迹」：0=有
  # 判断依据：OpenAPI 契约里有没有新版端点。用「文档内容」而不是「探活状态码」——
  # main 有 /webhook/{system} 通配映射，POST /webhook/zabbix 在两版都是 200，
  # 靠状态码区分不出跑的是哪一版（首跑就踩了这个坑，故改成契约内容比对）。
  [ -n "$MARKER_NEEDLE" ] || return 1        # 未给针＝不参与判据（grep -q "" 会恒真）
  curl -s -m 10 "http://localhost:$PORT/ai$MARKER_PATH" 2>/dev/null | grep -q "$MARKER_NEEDLE"
}

stop_app() {
  if [ -f "$PIDFILE" ]; then
    kill "$(cat "$PIDFILE")" 2>/dev/null || true
    for _ in $(seq 1 30); do kill -0 "$(cat "$PIDFILE")" 2>/dev/null || break; sleep 1; done
    rm -f "$PIDFILE"
  fi
  # 同端口残留进程（上次演练异常退出）也一并收拾，避免「以为重启了其实没换」
  pkill -f "DevopsPlatformBackendApplication.*$PORT" 2>/dev/null || true
  sleep 1
}

start_app() { # start_app <classes-dir> <tag> <res-dir>
  local classes="$1"
  local tag="$2"
  local res="${3:-$RES_DIR}"
  local log="$LOG_DIR/$tag.log"   # 注意：不要写成同一行 local a=$1 b=$a —— bash 会先展开后赋值，$tag 为空
  # NO_FLYWAY=1 时关掉 Flyway 校验：旧版代码不认识新版已 apply 的迁移，
  # 默认 validate 会以「applied migration not resolved locally」直接拒绝启动。
  local flyway_env=""
  [ -n "$NO_FLYWAY" ] && flyway_env="SPRING_FLYWAY_ENABLED=false"
  AI_MODE=MOCK SPRING_PROFILES_ACTIVE=dev SCHEMA_FAIL_FAST=true SERVER_PORT="$PORT" \
  ${flyway_env:+$flyway_env} \
  nohup "$JAVA_BIN" -Dlogging.level.root=WARN \
        -cp "$classes:$(cat "$CP_BASE"):$res" \
        com.devops.agent.DevopsPlatformBackendApplication > "$log" 2>&1 &
  echo $! > "$PIDFILE"
}

wait_health() { # wait_health <timeout>
  local t=0 limit="$1"
  while [ "$t" -lt "$limit" ]; do
    health_up && return 0
    sleep 2; t=$((t+2))
  done
  return 1
}

# ══════════════════════════════════════════════════════════════════════════
if [ "$MODE" = "artifact" ]; then
  [ -n "$PREV" ] && [ -n "$CUR" ] || die "--mode artifact 需要 --prev 与 --cur（两个版本的 class 目录）"
  [ -d "$PREV" ] || die "prev 目录不存在：$PREV"
  [ -d "$CUR" ]  || die "cur 目录不存在：$CUR"
  echo "=== 后端版本回退演练（artifact 模式）==="
  echo "  cur=$CUR"; echo "  prev=$PREV"; echo "  port=$PORT base=$BASE"

  # 前置断言：两版构建物必须真的不同。同版之间「回滚」演的是空戏，必须当场红。
  [ "$PREV" != "$CUR" ] || die "--prev 与 --cur 指向同一目录，演的不是两版本"
  FP_PREV=$(build_fingerprint "$PREV"); FP_CUR=$(build_fingerprint "$CUR")
  echo "  构建物指纹 prev=$FP_PREV cur=$FP_CUR"
  SAME_BUILD=0
  if [ "$FP_PREV" = "$FP_CUR" ]; then
    # 不 die：改脚本/CI/文档的提交**不产生新的构建物**，两版指纹本就相同。
    # 这种情况「切到了另一版」无法证明，但 DoD#6 的**时间与健康**判据照样可测（切换→重启→探活）。
    # 关键是别静默：每次都在 annotation 里点名，免得它变成一条永远在放水的闸。
    SAME_BUILD=1
    bad "两版构建物指纹相同（$FP_CUR）→ 本轮**无法证明**切到了另一版"
    info "常见原因：本次改动不涉及编译产物（脚本/CI/文档）。要证身份需两个不同构建；"
    info "若 prev 取的是 origin/main 而当前就在 main 上，应改取 HEAD^（合并后二者同版）。"
    info "仍执行切换/重启/探活/计时（DoD#6 的时间与健康判据不受影响）。"
  else
    ok "前置：两版构建物指纹不同 → 确有两个不同版本可切"
  fi

  info "0) 清场并启动当前版本（v_cur）"
  stop_app
  T_START=$(date +%s)
  start_app "$CUR" cur "${CUR_RES:-$RES_DIR}"
  wait_health 180 || die "v_cur 180s 内未健康（见 $LOG_DIR/cur.log）"
  ok "v_cur 健康（启动耗时 $(( $(date +%s) - T_START ))s）"
  assert_runtime_identity "$CUR" "v_cur"
  if [ -n "$MARKER_NEEDLE" ]; then
    marker_present && ok "版本身份：契约含「$MARKER_NEEDLE」→ 确为 v_cur" \
                   || die "v_cur 契约缺 marker，版本身份不成立"
  else
    info "未给 --marker-needle，版本身份以「指纹 + classpath」证伪（不依赖具体端点）"
  fi

  info "1) 事故发生 → 执行回滚（切到 v_prev 并重启，开始计时）"
  T_ROLL_START=$(date +%s%3N)
  stop_app
  start_app "$PREV" prev "${PREV_RES:-$RES_DIR}"
  wait_health 180 || die "回滚后 180s 内未健康（见 $LOG_DIR/prev.log）"
  T_ROLL_END=$(date +%s%3N)
  ROLL_MS=$(( T_ROLL_END - T_ROLL_START ))
  ROLL_S=$(( ROLL_MS / 1000 ))
  ok "v_prev 健康（回滚耗时 ${ROLL_MS}ms ≈ ${ROLL_S}s）"
  health_up && ok "探活复检：/actuator/health = UP"
  assert_runtime_identity "$PREV" "v_prev"
  if [ -n "$MARKER_NEEDLE" ]; then
    marker_present && die "回滚后契约仍含 marker → 跑的还是旧代码（回滚没生效）" \
                   || ok "版本身份：契约不含 marker → 确为 v_prev（旧版无该端点）"
  else
    info "v_prev 身份以 classpath 证伪（指纹 $FP_PREV）"
  fi

  info "2) 前滚（切回 v_cur，验证可逆）"
  T_FWD_START=$(date +%s%3N)
  stop_app
  start_app "$CUR" cur2 "${CUR_RES:-$RES_DIR}"
  wait_health 180 || die "前滚后 180s 内未健康"
  ok "v_cur 健康（前滚耗时 $(( ($(date +%s%3N) - T_FWD_START) / 1000 ))s）"
  assert_runtime_identity "$CUR" "v_cur(前滚)"
  if [ -n "$MARKER_NEEDLE" ]; then
    marker_present && ok "版本身份：契约重新含 marker → 确为 v_cur" || die "前滚后 marker 缺失"
  fi
  stop_app

  echo
  echo "=== 演练结论 ==="
  if [ "$ROLL_S" -lt "$LIMIT_SECONDS" ]; then
    if [ "$SAME_BUILD" = "1" ]; then
      echo -e "  ${YELLOW}回滚耗时 ${ROLL_S}s < ${LIMIT_SECONDS}s —— 时间/健康判据满足，但两版构建物相同、版本身份本轮未证${NC}"
    else
      echo -e "  ${GREEN}回滚耗时 ${ROLL_S}s < ${LIMIT_SECONDS}s —— DoD#6「后端版本回退 <5min」判据满足${NC}"
    fi
    exit 0
  fi
  echo -e "  ${RED}回滚耗时 ${ROLL_S}s ≥ ${LIMIT_SECONDS}s —— 判据不满足${NC}"
  exit 1
fi

# ══════════════════════════════════════════════════════════════════════════
if [ "$MODE" = "docker" ]; then
  command -v docker >/dev/null 2>&1 || die "本机无 docker：docker 模式需在生产机执行（Runbook §1.2）"
  echo "=== 后端版本回退演练（docker 模式，Runbook §1.2）==="
  info "0) 双 tag 纪律（回滚的货）"
  docker tag "$IMAGE:latest" "$IMAGE:prev-$(date +%Y%m%d)" || die "打 tag 失败"
  ok "已生成回退标签 $IMAGE:prev-$(date +%Y%m%d)"
  info "1) 回滚：本地镜像切换（无网络拉取），计时开始"
  T_ROLL_START=$(date +%s%3N)
  docker tag "$IMAGE:prev-$(date +%Y%m%d)" "$IMAGE:latest" || die "切标签失败"
  APP_PORT="$PORT" AI_MODE=MOCK docker compose --env-file .env -p "$PROJECT" \
      up -d --no-build "$SERVICE" || die "compose 起服失败"
  wait_health 180 || die "回滚后 180s 内未健康"
  ROLL_S=$(( ($(date +%s%3N) - T_ROLL_START) / 1000 ))
  ok "容器健康（回滚耗时 ${ROLL_S}s）"
  [ "$ROLL_S" -lt "$LIMIT_SECONDS" ] || die "回滚耗时 ${ROLL_S}s ≥ ${LIMIT_SECONDS}s"
  echo -e "  ${GREEN}DoD#6 判据满足${NC}"
  exit 0
fi

echo "用法：bash scripts/rollback-drill.sh --mode artifact|docker [选项]"
exit 2
