#!/usr/bin/env bash
# =============================================================================
# OpsBrain 备份（S5-2 步骤 5-2.3 / 批 37）
#
# 内容：
#   1. PostgreSQL 全量（pg_dump 自定义格式 -Fc，含 pgvector 数据）
#   2. MinIO 对象仓全量(docker cp 整桶打包 tar.gz)
#   3. manifest.json(版本/时间/两物大小与 SHA256——恢复对账的第一可证据)
#
# 用法:
#   scripts/backup.sh                 # 备份到 ./backups/YYYYMMDD-HHMMSS/
#   scripts/backup.sh /path/to/dir    # 指定输出根目录(其下仍以时间戳分子目录)
#
# 环境: 生产 compose 栈运行中(docker compose ps 三个 healthy)。
# 纪律:
#   - 不打断服务:pg_dump 在线热备(MVCC 一致快照),MinIO 打包为读取拷贝;
#     写入高峰期间 MinIO yaml 可能有轻微不一致——演练口径以演练记录说明,见
#     docs/09-operations/部署运维手册.md §恢复演练。
#   - 保留策略默认 14 天(RETENTION_DAYS 可覆写)——备份不修剪就是慢性磁盘炸弹。
#   - 脚本报错即退出(set -Eeuo pipefail);所有失败先在 manifest 留 failed 标记。
# =============================================================================
set -Eeuo pipefail

COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.yml}"
RETENTION_DAYS="${RETENTION_DAYS:-14}"
ROOT="${1:-backups}"
TS="$(date +%Y%m%d-%H%M%S)"
DEST="${ROOT%/}/${TS}"
DB_NAME="${POSTGRES_DB:-devops_knowledge_db}"
DB_USER="${POSTGRES_USER:-devops}"
MINIO_CONTAINER="${MINIO_CONTAINER:-opsbrain-minio}"

log() { echo "[$(date +%H:%M:%S)] $*"; }
die() { echo "❌ $*" >&2; exit 1; }

command -v docker >/dev/null || die "docker 不可用"
docker compose -f "$COMPOSE_FILE" ps postgres >/dev/null 2>&1 || die "compose 栈未运行——先 docker compose up -d"

mkdir -p "$DEST"
# 不设 ERR trap 壳:set -e 即停;manifest 最后一步才写——目录里没有 manifest.json
# 本身就是「失败/半截备份」标记,restore.sh 第一步就会拒收它。

log "==> 1/3 PostgreSQL 全量(pg_dump -Fc)"
docker compose -f "$COMPOSE_FILE" exec -T postgres \
  pg_dump -U "$DB_USER" -d "$DB_NAME" -Fc -Z 6 > "$DEST/postgres.dump"
log "    落盘 $DEST/postgres.dump($(du -h "$DEST/postgres.dump" | cut -f1))"

log "==> 2/3 MinIO 整桶打包(docker cp + tar.gz)"
TMP_MINIO="$(mktemp -d)"
docker cp "$MINIO_CONTAINER":/data "$TMP_MINIO/minio-data" >/dev/null 2>&1 \
  || docker compose -f "$COMPOSE_FILE" cp minio:/data "$TMP_MINIO/minio-data"
tar -C "$TMP_MINIO" -czf "$DEST/minio.tar.gz" minio-data
rm -rf "$TMP_MINIO"
log "    落盘 $DEST/minio.tar.gz($(du -h "$DEST/minio.tar.gz" | cut -f1))"

log "==> 3/3 manifest 对账单"
sha() { sha256sum "$1" | awk '{print $1}'; }
cat > "$DEST/manifest.json" <<JSON
{
  "app": "opsbrain-ai",
  "created_at": "$(date -Iseconds)",
  "git_sha": "$(git -C "$(dirname "$0")/.." rev-parse --short HEAD 2>/dev/null || echo unknown)",
  "db": {"name": "$DB_NAME", "format": "pgcustom-Z6", "file": "postgres.dump", "sha256": "$(sha "$DEST/postgres.dump")"},
  "minio": {"file": "minio.tar.gz", "sha256": "$(sha "$DEST/minio.tar.gz")"},
  "retention_days": $RETENTION_DAYS
}
JSON

log "==> 保留修剪(>${RETENTION_DAYS}d 者删)"
find "$ROOT" -mindepth 1 -maxdepth 1 -type d -name '????????-??????' -mtime +"$RETENTION_DAYS" -print -exec rm -rf {} \; 2>/dev/null || true

log "✅ 备份完成:$DEST"
log "   校验建议:scripts/backup.sh 后对文件执行 pg_restore --list $DEST/postgres.dump | head"
