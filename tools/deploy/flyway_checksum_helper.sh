#!/bin/bash
# Flyway Checksum 计算助手
# 用途：计算 V1__baseline.sql 的 Flyway checksum，用于生产环境 checksum 修复
# 版本：v1.0 | 日期：2026-09-13

set -euo pipefail

# ========== 配置区 ==========
MIGRATION_FILE="${1:-src/main/resources/db/migration/V1__baseline.sql}"
DB_CONTAINER="${DB_CONTAINER:-devops-platform-backend-postgres-1}"
DB_USER="${DB_USER:-devops}"
DB_NAME="${DB_NAME:-devops_platform}"

# ========== 颜色定义 ==========
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# ========== 函数定义 ==========
log_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

log_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# 检查文件存在
check_file_exists() {
    if [ ! -f "$MIGRATION_FILE" ]; then
        log_error "迁移文件不存在: $MIGRATION_FILE"
        echo ""
        echo "用法: $0 [path/to/migration/file.sql]"
        exit 1
    fi
}

# 计算本地文件 checksum（模拟 Flyway CRC32 算法）
calculate_local_checksum() {
    log_info "计算本地文件 checksum: $MIGRATION_FILE"

    # Flyway 使用 CRC32 算法
    # 这里使用 cksum 命令（POSIX 标准，输出第一列即为 CRC32）
    local checksum=$(cksum "$MIGRATION_FILE" | awk '{print $1}')

    echo "$checksum"
}

# 从数据库查询当前记录的 checksum
query_database_checksum() {
    log_info "查询数据库中记录的 checksum..."

    if ! docker ps | grep -q "$DB_CONTAINER"; then
        log_error "数据库容器未运行: $DB_CONTAINER"
        exit 1
    fi

    local db_checksum=$(docker exec "$DB_CONTAINER" \
        psql -U "$DB_USER" -d "$DB_NAME" -tA \
        -c "SELECT checksum FROM flyway_schema_history WHERE version = '1';" 2>/dev/null || echo "")

    if [ -z "$db_checksum" ]; then
        log_warn "数据库中未找到版本 1 的迁移记录（可能是空库）"
        echo "N/A"
    else
        echo "$db_checksum"
    fi
}

# 生成 SQL 更新语句
generate_update_sql() {
    local new_checksum=$1

    cat <<EOF

-- ========== Flyway Checksum 修复 SQL ==========
-- 生成时间: $(date '+%Y-%m-%d %H:%M:%S')
-- 新 checksum: $new_checksum
--
-- 执行前请确保：
--   1. 已备份数据库
--   2. 已手动执行增量 SQL（如有）
--   3. 理解本操作的影响

-- 备份当前记录
CREATE TABLE IF NOT EXISTS flyway_schema_history_backup_$(date +%Y%m%d_%H%M%S) AS
  SELECT * FROM flyway_schema_history WHERE version = '1';

-- 更新 checksum
UPDATE flyway_schema_history
SET checksum = $new_checksum,
    description = description || ' (checksum updated on $(date +%Y-%m-%d))'
WHERE version = '1';

-- 验证更新
SELECT version, checksum, description, installed_on
FROM flyway_schema_history
WHERE version = '1';
EOF
}

# 交互式执行更新（可选）
interactive_update() {
    local new_checksum=$1

    echo ""
    log_warn "检测到 checksum 不一致，是否立即更新数据库？"
    echo ""
    echo "  当前数据库 checksum: $db_checksum"
    echo "  新计算 checksum:     $new_checksum"
    echo ""
    read -p "继续执行更新？(yes/no): " confirm

    if [ "$confirm" != "yes" ]; then
        log_info "已取消更新"
        exit 0
    fi

    log_info "执行 checksum 更新..."

    docker exec "$DB_CONTAINER" \
        psql -U "$DB_USER" -d "$DB_NAME" <<EOF
-- 备份当前记录
CREATE TABLE IF NOT EXISTS flyway_schema_history_backup_$(date +%Y%m%d_%H%M%S) AS
  SELECT * FROM flyway_schema_history WHERE version = '1';

-- 更新 checksum
UPDATE flyway_schema_history
SET checksum = $new_checksum,
    description = description || ' (checksum updated on $(date +%Y-%m-%d))'
WHERE version = '1';

-- 验证更新
SELECT version, checksum, description, installed_on
FROM flyway_schema_history
WHERE version = '1';
EOF

    log_success "checksum 更新完成"
}

# ========== 主流程 ==========
main() {
    echo ""
    log_info "========================================"
    log_info "  Flyway Checksum 计算助手"
    log_info "========================================"
    echo ""

    # 1. 检查文件
    check_file_exists

    # 2. 计算本地 checksum
    local_checksum=$(calculate_local_checksum)
    log_success "本地文件 checksum: $local_checksum"
    echo ""

    # 3. 查询数据库 checksum
    db_checksum=$(query_database_checksum)
    if [ "$db_checksum" != "N/A" ]; then
        log_success "数据库记录 checksum: $db_checksum"
        echo ""

        # 4. 对比
        if [ "$local_checksum" = "$db_checksum" ]; then
            log_success "✓ checksum 一致，无需修复"
            echo ""
        else
            log_warn "✗ checksum 不一致，需要修复"
            echo ""

            # 5. 生成修复 SQL
            log_info "生成修复 SQL 语句..."
            generate_update_sql "$local_checksum"
            echo ""

            # 6. 交互式更新（可选）
            if [ "${INTERACTIVE:-true}" = "true" ]; then
                interactive_update "$local_checksum"
            fi
        fi
    else
        log_info "数据库为空或未执行过迁移，首次启动将使用 checksum: $local_checksum"
        echo ""
    fi

    log_info "========================================"
    log_info "  完成"
    log_info "========================================"
    echo ""
}

# 执行主流程
main
