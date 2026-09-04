#!/bin/bash
# =============================================================================
# PostgreSQL 容器首次启动时的建库脚本（挂载到 /docker-entrypoint-initdb.d/）
#
# 为什么不直接把 sql/ 整个目录挂进去：
#   mock_data.sql 也在里面，会被一起执行 —— 生产库灌入演示数据是事故。
#   本脚本只执行 init.sql，不碰 mock 数据。
#
# 需要演示数据时手动执行：
#   docker compose exec -T postgres psql -U devops -d devops_knowledge_db < sql/mock_data.sql
#
# ⚠️ 本脚本只在**数据目录为空**时被官方镜像调用。复用已有数据卷升级时
#    它不会执行，此时应手动跑一次 init.sql（幂等，可安全重复执行）：
#      docker compose exec -T postgres psql -U devops -d devops_knowledge_db < sql/init.sql
#    启动期的 SchemaGuard 会检出这种「新 JAR + 旧结构」的情况并给出同样的提示。
# =============================================================================
set -euo pipefail

DB="${POSTGRES_DB:-devops_knowledge_db}"
USER="${POSTGRES_USER:-devops}"
SQL_DIR="/opt/opsbrain-sql"

echo "===== OpsBrain AI 数据库初始化开始 ====="

# ON_ERROR_STOP=1：任何一条语句出错就中断并让容器启动失败。
# 否则会得到一个"启动成功但表结构不完整"的库，故障要到运行期才暴露。
psql -v ON_ERROR_STOP=1 --username "$USER" --dbname "$DB" -f "$SQL_DIR/init.sql"

echo "===== 数据库初始化完成（未加载 mock 数据）====="
