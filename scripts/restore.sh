#!/usr/bin/env bash
# =============================================================================
# OpsBrain 恢复（S5-2 步骤 5-2.3 / 批 37）：备份的真实反面，演练过才算数
#
# 用法(全部必选,拒绝默认路径误操作):
#   scripts/restore.sh --from backups/20260908-120000 --confirm
#
# 语义顺序(先停写、再还库、再还桶、后起栈——顺序错了恢复出来就是半新半旧):
#   1. 校验 manifest 与两份物料 SHA256(对不上即拒动);
#   2. 暂停 app 容器(唯一写方,先切断在半库上继续写的可能);
#   3. PG:drop+create 空库后 pg_restore 回灌(pg_dump -Fc 配对面);
#   4. MinIO:清 /data 后解包回灌,重启 minio 容器;
#   5. 启动 app,等待 /ai/api/v1/health/ping 通过;
#   6. 打印「恢复后三查」清单(工单数/知识文档数/最近告警时间),人工对账后收工。
#
# ⚠️ 本脚本假设备份时代码与目标环境代码同构(manifest.git_sha 对账)。
#    跨 flyway 版本恢复后由应用启动时的 flyway migrate 自洽前滚;回滚须人工核。
# =============================================================================
set -Eeuo pipefail

COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.yml}"
DB_NAME="${POSTGRES_DB:-devops_knowledge_db}"
DB_USER="${POSTGRES_USER:-devops}"
MINIO_CONTAINER="${MINIO_CONTAINER:-opsbrain-minio}"

FROM=""
CONFIRM=""
while [ $# -gt 0 ]; do
  case "$1" in
    --from) FROM="$2"; shift 2;;
    --confirm) CONFIRM="yes"; shift;;
    *) echo "未知参数:$1(只收 --from DIR 与 --confirm)">&2; exit 2;;
  esac
done
die() { echo "❌ $*" >&2; exit 1; }
log() { echo "[$(date +%H:%M:%S)] $*"; }

[ -n "$FROM" ] || die "缺 --from backups/<时间戳目录>"
[ "$CONFIRM" = "yes" ] || die "恢复是破坏性操作——请显式加 --confirm(读手册 docs/09-operations/部署运维手册.md)"
[ -d "$FROM" ] || die "备份目录不存在:$FROM"
[ -f "$FROM/manifest.json" ] || die "缺 manifest.json —— 不完整备份不可恢复"
[ -f "$FROM/postgres.dump" ] || die "缺 postgres.dump"
[ -f "$FROM/minio.tar.gz" ] || die "缺 minio.tar.gz"

log "==> 1/6 物料 SHA256 对账"
sha() { sha256sum "$1" | awk '{print $1}'; }
grep -q "$(sha "$FROM/postgres.dump")" "$FROM/manifest.json" || die "postgres.dump SHA256 与 manifest 不符——备份受损或串件"
grep -q "$(sha "$FROM/minio.tar.gz")" "$FROM/manifest.json" || die "minio.tar.gz SHA256 与 manifest 不符"
cat "$FROM/manifest.json"

log "==> 2/6 暂停 app(切断写方)"
docker compose -f "$COMPOSE_FILE" stop app

log "==> 3/6 PG:重建空库 + pg_restore 回灌"
docker compose -f "$COMPOSE_FILE" exec -T postgres psql -U "$DB_USER" -d postgres -v ON_ERROR_STOP=1 \
  -c "DROP DATABASE IF EXISTS $DB_NAME WITH (FORCE); CREATE DATABASE $DB_NAME OWNER $DB_USER;"
docker compose -f "$COMPOSE_FILE" exec -T postgres sh -c \
  "pg_restore -U $DB_USER -d $DB_NAME --clean --if-exists --no-owner /dev/stdin" < "$FROM/postgres.dump" \
  || die "pg_restore 失败(可重试;失败原因最常见是扩展未建——空库先 CREATE EXTENSION vector)"

log "==> 4/6 MinIO:清桶回灌"
docker compose -f "$COMPOSE_FILE" stop minio
TMP_MINIO="$(mktemp -d)"
tar -C "$TMP_MINIO" -xzf "$FROM/minio.tar.gz"
docker cp "$TMP_MINIO/minio-data/." "$MINIO_CONTAINER":/data 2>/dev/null \
  || { docker compose -f "$COMPOSE_FILE" start minio && sleep 3 \
       && docker compose -f "$COMPOSE_FILE" cp "$TMP_MINIO/minio-data/." minio:/data; }
rm -rf "$TMP_MINIO"
docker compose -f "$COMPOSE_FILE" start minio

log "==> 5/6 拉起 app 并等健康"
docker compose -f "$COMPOSE_FILE" start app
for i in $(seq 1 30); do
  if curl -fsS --max-time 3 http://localhost:"${APP_PORT:-8088}"/ai/api/v1/health/ping >/dev/null 2>&1; then
    log "    健康检查通过"; break
  fi
  [ "$i" = "30" ] && die "app 健康检查 90s 未过——看 docker compose logs app"
  sleep 3
done

log "==> 6/6 恢复后三查(人工对账)"
docker compose -f "$COMPOSE_FILE" exec -T postgres psql -U "$DB_USER" -d "$DB_NAME" -c \
  "SELECT (SELECT COUNT(*) FROM sys_ticket) AS tickets,
          (SELECT COUNT(*) FROM sys_knowledge_doc) AS knowledge_docs,
          (SELECT MAX(received_at) FROM sys_alert) AS latest_alert;"
log "✅ 恢复完成。对账无误后收工;有差异先查 manifest.git_sha 与当前代码版。"
