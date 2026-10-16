#!/usr/bin/env bash
# ==============================================================================
# OpsBrain AI (智维大脑) —— SOP 开工前自检与验证脚本 (Pre-Flight Check)
# ==============================================================================

echo "=============================================================================="
echo " 🛡️ 正在为您启动 OpsBrain AI (智维大脑) SOP 全环境闭环检查，预计耗时 3 秒..."
echo "=============================================================================="

# 1. 检查 Java JDK 版本号是否达标 (必须 >= 17)
JAVA_VER=$(java -version 2>&1 | head -1 | cut -d'"' -f2 | sed '/^$/d' | cut -d'.' -f1)
if [ "$JAVA_VER" -ge 17 ] 2>/dev/null; then
    echo " [PASSED] ✅ JDK 环境检查通过：当前版本为 Java $JAVA_VER (满足 JDK >= 17 要求)"
else
    echo " [FAILED] ❌ JDK 版本过低或者环境变量缺失！当前版本为: $JAVA_VER，请先更新或配置环境变量 JAVA_HOME 为 Java 17！"
    # exit 1
fi

# 2. 检查 Docker 及 docker-compose 引擎是否就绪
if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
    echo " [PASSED] ✅ Docker 引擎检查通过：后台守护进程运行中且就绪"
else
    echo " [WARNING] ⚠️ Docker 未启动或未安装！请开启 Docker Desktop 或 systemctl start docker (若在纯云端沙盒调试，请确认数据库连接)"
fi

# 3. 检查并测试数据库端口冲突 (PgVector 5432 & Redis 6379)
if nc -z localhost 5432 2>/dev/null; then
    echo " [INFO] ℹ️ 端口 5432 当前处于处于打开状态，请确保是准备供给 PgVector 容器使用"
else
    echo " [PASSED] ✅ 端口 5432 就绪可用 (准备供给 PgVector 使用)"
fi

if nc -z localhost 6379 2>/dev/null; then
    echo " [INFO] ℹ️ 端口 6379 当前处于打开状态，请确保是准备供给 Redis 7 容器使用"
else
    echo " [PASSED] ✅ 端口 6379 就绪可用 (准备供给 Redis 7 使用)"
fi

# 4. 检查是否具备 API Key 环境变量或 Mock 开关准备
if [ -z "$DEEPSEEK_API_KEY" ]; then
    echo " [INFO] 💡 未检测到 DEEPSEEK_API_KEY 环境变量注入，系统推荐使用 application.yml 中的 devops.ai.mode=MOCK 极速模拟模式启动头 3 天调试！"
else
    echo " [PASSED] ✅ 真实 AI API KEY 已就绪：极速接入在线大模型分层调度！"
fi

echo "=============================================================================="
echo " 🎉 环境自检扫描完毕！您可以放心打开 docs/04-daily-implementation-plan 开启实战编码！"
echo "=============================================================================="
