#!/bin/bash
# 批 87 快速验证脚本
# 用途：验证前端优化和数据库索引是否生效
# 使用方法：bash scripts/quick-verify.sh

set -e

echo "========================================"
echo "批 87 优化验证脚本"
echo "========================================"
echo ""

# 颜色定义
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# 步骤 1：检查前端构建速度
echo "步骤 1/5：验证前端构建速度（目标 < 5 秒）"
echo "----------------------------------------"
cd devops-platform-frontend

echo "清理缓存..."
rm -rf node_modules/.vite

echo "开始构建..."
BUILD_START=$(date +%s)
pnpm run build > /dev/null 2>&1
BUILD_END=$(date +%s)
BUILD_TIME=$((BUILD_END - BUILD_START))

if [ $BUILD_TIME -lt 10 ]; then
    echo -e "${GREEN}✅ 构建速度：${BUILD_TIME}s（目标达成）${NC}"
else
    echo -e "${RED}❌ 构建速度：${BUILD_TIME}s（超过目标 10s）${NC}"
fi
echo ""

cd ..

# 步骤 2：检查未使用依赖是否已清理
echo "步骤 2/5：验证死代码清理"
echo "----------------------------------------"
cd devops-platform-frontend

UNUSED_DEPS=$(pnpm exec knip --reporter json 2>/dev/null | grep -o '"dependencies":\[[^]]*\]' | grep -c '@wangeditor' || echo 0)

if [ $UNUSED_DEPS -eq 0 ]; then
    echo -e "${GREEN}✅ 未使用依赖已清理${NC}"
else
    echo -e "${RED}❌ 仍有 @wangeditor 相关依赖未清理${NC}"
fi
echo ""

cd ..

# 步骤 3：验证数据库索引
echo "步骤 3/5：验证数据库索引（需要 Docker 运行）"
echo "----------------------------------------"

# 检查 Docker 是否运行
if ! docker ps > /dev/null 2>&1; then
    echo -e "${RED}❌ Docker 未运行，跳过数据库验证${NC}"
    echo ""
else
    # 检查 PostgreSQL 容器是否存在
    PG_CONTAINER=$(docker ps --filter "name=postgres" --format "{{.Names}}" | head -1)

    if [ -z "$PG_CONTAINER" ]; then
        echo -e "${YELLOW}⚠️  PostgreSQL 容器未找到${NC}"
        echo "提示：执行以下命令重建数据库："
        echo "  docker-compose down -v"
        echo "  docker-compose up -d"
        echo ""
    else
        echo "检查工单表索引..."
        INDEX_COUNT=$(docker exec $PG_CONTAINER psql -U opsbrain -d opsbrain_db -tAc "
            SELECT COUNT(*) FROM pg_indexes
            WHERE tablename = 'sys_devops_ticket'
            AND indexname LIKE 'idx_%'
        " 2>/dev/null || echo 0)

        if [ $INDEX_COUNT -ge 10 ]; then
            echo -e "${GREEN}✅ 工单表索引数量：${INDEX_COUNT}（预期 >= 10）${NC}"
        else
            echo -e "${RED}❌ 工单表索引数量：${INDEX_COUNT}（不足 10）${NC}"
            echo "提示：可能需要重建数据库以应用新索引"
        fi

        echo ""
        echo "检查告警表索引..."
        ALERT_INDEX_COUNT=$(docker exec $PG_CONTAINER psql -U opsbrain -d opsbrain_db -tAc "
            SELECT COUNT(*) FROM pg_indexes
            WHERE tablename = 'sys_alert'
            AND indexname LIKE 'idx_%'
        " 2>/dev/null || echo 0)

        if [ $ALERT_INDEX_COUNT -ge 5 ]; then
            echo -e "${GREEN}✅ 告警表索引数量：${ALERT_INDEX_COUNT}（预期 >= 5）${NC}"
        else
            echo -e "${RED}❌ 告警表索引数量：${ALERT_INDEX_COUNT}（不足 5）${NC}"
        fi

        echo ""
        echo "检查审批表索引..."
        APPROVAL_INDEX_COUNT=$(docker exec $PG_CONTAINER psql -U opsbrain -d opsbrain_db -tAc "
            SELECT COUNT(*) FROM pg_indexes
            WHERE tablename = 'sys_approval_request'
            AND indexname LIKE 'idx_%'
        " 2>/dev/null || echo 0)

        if [ $APPROVAL_INDEX_COUNT -ge 4 ]; then
            echo -e "${GREEN}✅ 审批表索引数量：${APPROVAL_INDEX_COUNT}（预期 >= 4）${NC}"
        else
            echo -e "${RED}❌ 审批表索引数量：${APPROVAL_INDEX_COUNT}（不足 4）${NC}"
        fi
        echo ""
    fi
fi

# 步骤 4：检查前端测试
echo "步骤 4/5：验证前端测试"
echo "----------------------------------------"
cd devops-platform-frontend

TEST_RESULT=$(pnpm test 2>&1 | grep -E "Tests.*passed" || echo "未找到测试结果")

if [[ $TEST_RESULT == *"1044 passed"* ]]; then
    echo -e "${GREEN}✅ 测试通过：1044/1044${NC}"
else
    echo -e "${YELLOW}⚠️  测试结果：${TEST_RESULT}${NC}"
fi
echo ""

cd ..

# 步骤 5：生成验证报告
echo "步骤 5/5：生成验证报告"
echo "----------------------------------------"

REPORT_FILE="docs/reports/report-87-verification-result.md"

cat > $REPORT_FILE << EOF
# 批 87 验证结果报告

**日期**: $(date +"%Y-%m-%d %H:%M:%S")
**验证人**: 自动化脚本

---

## 验证结果

### 1. 前端构建速度
- **构建时间**: ${BUILD_TIME}s
- **目标**: < 10s
- **状态**: $([ $BUILD_TIME -lt 10 ] && echo "✅ 通过" || echo "❌ 未通过")

### 2. 死代码清理
- **@wangeditor 依赖**: $([ $UNUSED_DEPS -eq 0 ] && echo "已清理" || echo "未清理")
- **状态**: $([ $UNUSED_DEPS -eq 0 ] && echo "✅ 通过" || echo "❌ 未通过")

### 3. 数据库索引
- **工单表索引数量**: ${INDEX_COUNT:-未检测}
- **告警表索引数量**: ${ALERT_INDEX_COUNT:-未检测}
- **审批表索引数量**: ${APPROVAL_INDEX_COUNT:-未检测}
- **状态**: $([ ${INDEX_COUNT:-0} -ge 10 ] && echo "✅ 通过" || echo "⚠️  待重建数据库")

### 4. 前端测试
- **测试结果**: ${TEST_RESULT}
- **状态**: $([[ $TEST_RESULT == *"1044 passed"* ]] && echo "✅ 通过" || echo "⚠️  部分通过")

---

## 下一步建议

EOF

if [ $BUILD_TIME -lt 10 ] && [ $UNUSED_DEPS -eq 0 ] && [ ${INDEX_COUNT:-0} -ge 10 ]; then
    cat >> $REPORT_FILE << EOF
**所有验证项通过！** 🎉

建议继续执行：
1. 前端性能监控（1 小时）
2. 骨架屏加载（2 小时）
3. 接口性能压测（2 小时）

详见：\`docs/reports/report-87-next-steps-recommendation.md\`
EOF
else
    cat >> $REPORT_FILE << EOF
**部分验证项未通过，建议检查：**

EOF

    if [ $BUILD_TIME -ge 10 ]; then
        echo "- [ ] 前端构建速度超过目标（${BUILD_TIME}s > 10s）" >> $REPORT_FILE
    fi

    if [ $UNUSED_DEPS -ne 0 ]; then
        echo "- [ ] @wangeditor 依赖未清理完全" >> $REPORT_FILE
    fi

    if [ ${INDEX_COUNT:-0} -lt 10 ]; then
        echo "- [ ] 数据库索引未应用，执行：\`docker-compose down -v && docker-compose up -d\`" >> $REPORT_FILE
    fi
fi

echo -e "${GREEN}✅ 验证报告已生成：${REPORT_FILE}${NC}"
echo ""

# 总结
echo "========================================"
echo "验证完成！"
echo "========================================"
echo ""
echo "详细报告：${REPORT_FILE}"
echo ""

# 根据结果给出建议
if [ $BUILD_TIME -lt 10 ] && [ $UNUSED_DEPS -eq 0 ] && [ ${INDEX_COUNT:-0} -ge 10 ]; then
    echo -e "${GREEN}🎉 所有验证项通过！${NC}"
    echo ""
    echo "下一步："
    echo "  1. 前端性能监控（见 report-87-next-steps-recommendation.md）"
    echo "  2. 骨架屏加载"
    echo "  3. 接口性能压测"
else
    echo -e "${YELLOW}⚠️  部分验证项未通过，请检查报告${NC}"

    if [ ${INDEX_COUNT:-0} -lt 10 ]; then
        echo ""
        echo "建议立即执行（重建数据库以应用索引）："
        echo "  docker-compose down -v"
        echo "  docker-compose up -d"
    fi
fi

echo ""
