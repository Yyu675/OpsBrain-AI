#!/usr/bin/env bash
# =====================================================================
# OpsBrain AI 本地开发启动脚本（Git Bash / WSL / Linux / macOS）
#
# 前置条件：
#   1. 已执行 docker compose -f docker-compose.dev.yml up -d
#   2. .env 文件已按 README 填写（ALIBABA_API_KEY 等）
#
# 用法：
#   ./run-dev.sh
# =====================================================================

# 出错即退出
set -e

# 切换到脚本所在目录（项目根目录）
cd "$(dirname "$0")"

# 加载 .env 中的环境变量
if [ -f .env ]; then
  echo "✅ 加载 .env 环境变量"
  set -a
  # shellcheck source=/dev/null
  source .env
  set +a
else
  echo "️  未找到 .env 文件，请复制 .env.example 并填写真实密钥"
  exit 1
fi

# 使用 dev profile 启动 Spring Boot
echo "🚀 启动 OpsBrain AI 后端（dev 模式）..."
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev "$@"
