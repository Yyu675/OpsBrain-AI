#!/bin/bash
# =============================================================================
# PostgreSQL 容器首次启动时的建库脚本（挂载到 /docker-entrypoint-initdb.d/）
#
# 为什么不直接把 sql/ 整个目录挂进去：
#   1. mock_data.sql 也在里面，会被一起执行 —— 生产库灌入演示数据是事故；
#   2. initdb 按**文件名字母序**执行，而 init.sql 与 migration_* 的正确顺序
#      靠 'i' < 'm' 这个巧合成立。一旦将来出现 a*.sql 或把 init 改名，
#      迁移就会在建表前执行并失败。顺序这种事不该依赖运气，必须显式写死。
#
# 本脚本按「先建表、再按版本号升序迁移」的确定顺序执行，且不碰 mock 数据。
# 需要演示数据时手动执行：
#   docker compose exec -T postgres psql -U devops -d devops_knowledge_db < sql/mock_data.sql
# =============================================================================
set -euo pipefail

DB="${POSTGRES_DB:-devops_knowledge_db}"
USER="${POSTGRES_USER:-devops}"
SQL_DIR="/opt/opsbrain-sql"

run() {
  echo "==> 执行 $(basename "$1")"
  # ON_ERROR_STOP=1：任何一条语句出错就中断并让容器启动失败。
  # 否则会得到一个"启动成功但表结构不完整"的库，故障要到运行期才暴露。
  psql -v ON_ERROR_STOP=1 --username "$USER" --dbname "$DB" -f "$1"
}

echo "===== OpsBrain AI 数据库初始化开始 ====="
run "$SQL_DIR/init.sql"

# sort -V 按版本号排序：v2 < v10（普通 sort 会把 v10 排在 v2 前面）。
# 当前编号 v11~v23 恰好等宽看不出差别，但迁移到 v100 时就会出问题。
for f in $(ls "$SQL_DIR"/migration_v*.sql | sort -V); do
  run "$f"
done

echo "===== 数据库初始化完成（未加载 mock 数据）====="
