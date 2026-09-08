#!/bin/bash
# =============================================================================
# PostgreSQL 容器首次启动时的建库脚本（挂载到 /docker-entrypoint-initdb.d/）
#
# S0-1 起，表结构唯一真相源是 Flyway：src/main/resources/db/migration/。
# 本脚本只对**首次空卷**预建基线（V1__baseline.sql，生产 compose 把它挂到
# $SQL_DIR/V1__baseline.sql）。之后的一切结构变更由应用启动时的 Flyway
# 自动托管（baseline-on-migrate 与纯手工建出的表兼容）。
#
# 为什么不直接把 src/.../db/migration/ 整个目录挂进 initdb.d：
#   让应用来跑 Flyway 才有版本表与 checksum 保护；initdb.d 是无版本的
#   裸执行，仅用于基线冷启动。
#
# 需要演示数据时手动执行（mock_data.sql 仍在 sql/ 目录）：
#   docker compose exec -T postgres psql -U devops -d devops_knowledge_db < sql/mock_data.sql
#
# ⚠️ 本脚本只在**数据目录为空**时被官方镜像调用。复用已有数据卷升级时
#    它不会执行——无需手工补建表，应用启动时 Flyway 会自动把缺口补齐；
#    启动期 SchemaGuard 会检出「新 JAR + 旧结构」并给出提示。
# =============================================================================
set -euo pipefail

DB="${POSTGRES_DB:-devops_knowledge_db}"
USER="${POSTGRES_USER:-devops}"
SQL_DIR="/opt/opsbrain-sql"

echo "===== OpsBrain AI 数据库初始化开始（Flyway V1 基线） ====="

# ON_ERROR_STOP=1：任何一条语句出错就中断并让容器启动失败。
# 否则会得到一个"启动成功但表结构不完整"的库，故障要到运行期才暴露。
psql -v ON_ERROR_STOP=1 --username "$USER" --dbname "$DB" -f "$SQL_DIR/V1__baseline.sql"

echo "===== 数据库初始化完成（未加载 mock 数据）====="
