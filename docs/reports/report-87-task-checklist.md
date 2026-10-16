# 批 87 后续任务清单

**日期**: 2026-09-14  
**当前阶段**: L1.5 闭环完成 + 前端深度优化  
**下一阶段**: 性能验证 + 体验优化

---

## 📋 任务概览

| 优先级 | 任务 | 预计时间 | 状态 | 收益 |
|--------|------|----------|------|------|
| **P0** | 验证数据库索引 | 10 分钟 | ⏳ 待执行 | 避免生产性能问题 |
| **P1** | 前端性能监控 | 1 小时 | 📝 待规划 | 建立性能基准 |
| **P1** | 骨架屏加载 | 2 小时 | 📝 待规划 | 提升感知速度 30-50% |
| **P1** | 接口性能压测 | 2 小时 | 📝 待规划 | 验证 P99 延迟目标 |
| **P2** | L2 告警完善 | 3 天 | 📅 待排期 | 告警聚合降噪 |
| **P2** | L3 审批完善 | 2 天 | 📅 待排期 | 批量审批 + 历史追溯 |
| **P2** | 性能优化收官 | 2 天 | 📅 待排期 | 全链路性能验证 |

---

## ⚡ P0：立即执行（10 分钟）

### 任务 1：验证数据库索引

**目的**：确认 15 个新索引是否创建成功

**命令**：
```bash
# Windows
.\scripts\quick-verify.ps1

# Linux/Mac
bash scripts/quick-verify.sh
```

**或手动执行**：
```bash
# 1. 重建数据库（清空旧数据）
docker-compose down -v
docker-compose up -d

# 2. 等待数据库初始化完成
sleep 30

# 3. 检查索引是否创建成功
docker exec -it devops-platform-backend-postgres-1 \
  psql -U opsbrain -d opsbrain_db -c "\d+ sys_devops_ticket"

# 4. 执行索引验证脚本
docker exec -it devops-platform-backend-postgres-1 \
  psql -U opsbrain -d opsbrain_db -f /app/scripts/verify_indexes.sql
```

**预期结果**：
- ✅ 工单表索引数量 >= 10
- ✅ 告警表索引数量 >= 5
- ✅ 审批表索引数量 >= 4
- ✅ 每个查询使用索引扫描（不是全表扫描）

**如果失败**：
1. 检查 Docker 是否运行
2. 检查 PostgreSQL 版本是否 >= 12
3. 查看 Flyway 迁移日志：`docker logs devops-platform-backend-postgres-1`

**完成标志**：
- [ ] 索引验证脚本全部通过
- [ ] 查询计划显示 `Index Scan`
- [ ] 验证报告生成：`docs/reports/report-87-verification-result.md`

---

## 🎯 P1：今日完成（5 小时）

### 任务 2：前端性能监控（1 小时）

**目的**：建立性能基准，为后续优化提供数据支撑

**步骤**：

#### 2.1 安装性能监控工具（10 分钟）

```bash
cd devops-platform-frontend

# 安装 web-vitals 和 vite-plugin-monitor
pnpm add -D web-vitals vite-plugin-monitor
```

#### 2.2 创建性能监控组件（20 分钟）

创建 `src/utils/performance-monitor.ts`：
```typescript
import { onLCP, onFID, onCLS, onFCP, onTTFB } from 'web-vitals'

export function initPerformanceMonitor() {
  if (import.meta.env.DEV) {
    onLCP(console.log)
    onFID(console.log)
    onCLS(console.log)
    onFCP(console.log)
    onTTFB(console.log)
  }
}
```

在 `src/main.ts` 中引入：
```typescript
import { initPerformanceMonitor } from './utils/performance-monitor'

initPerformanceMonitor()
```

#### 2.3 录制关键场景（30 分钟）

使用 Chrome DevTools Performance 面板录制：

1. **场景 1：首屏加载**
   - 打开 `http://localhost:5173`
   - 录制 LCP、FID、CLS
   - 目标：LCP < 2.5s

2. **场景 2：工单列表筛选**
   - 打开工单列表
   - 切换状态筛选
   - 录制接口请求次数、渲染时间
   - 目标：筛选响应 < 200ms

3. **场景 3：AI 对话**
   - 打开 AI 助手
   - 发起一次对话
   - 录制首字延迟、SSE 流式响应
   - 目标：首字延迟 < 1s

**完成标志**：
- [ ] 性能监控代码部署
- [ ] 3 个关键场景录制完成
- [ ] 性能基准文档：`docs/reports/report-87-performance-baseline.md`

---

### 任务 3：骨架屏加载（2 小时）

**目的**：提升感知加载速度 30-50%

**步骤**：

#### 3.1 创建骨架屏组件（30 分钟）

创建 `src/components/common/TableSkeleton.vue`：
```vue
<template>
  <div class="table-skeleton">
    <div v-for="i in rows" :key="i" class="skeleton-row">
      <div v-for="j in cols" :key="j" class="skeleton-cell" />
    </div>
  </div>
</template>

<script setup lang="ts">
defineProps<{
  rows?: number
  cols?: number
}>()
</script>

<style scoped>
.table-skeleton {
  width: 100%;
}
.skeleton-row {
  display: flex;
  gap: 16px;
  margin-bottom: 12px;
}
.skeleton-cell {
  flex: 1;
  height: 48px;
  background: linear-gradient(90deg, #f0f0f0 25%, #e0e0e0 50%, #f0f0f0 75%);
  background-size: 200% 100%;
  animation: loading 1.5s ease-in-out infinite;
  border-radius: 4px;
}
@keyframes loading {
  0% { background-position: 200% 0; }
  100% { background-position: -200% 0; }
}
</style>
```

#### 3.2 应用到列表页（1 小时）

**工单列表** (`src/views/TicketList.vue`)：
```vue
<template>
  <div>
    <TableSkeleton v-if="isLoading" :rows="10" :cols="6" />
    <el-table v-else :data="displayedTickets">
      <!-- ... -->
    </el-table>
  </div>
</template>
```

**告警列表** (`src/views/AlertList.vue`)：
```vue
<template>
  <div>
    <TableSkeleton v-if="isLoading" :rows="8" :cols="5" />
    <el-table v-else :data="alerts">
      <!-- ... -->
    </el-table>
  </div>
</template>
```

**审批列表** (`src/views/ApprovalList.vue`)：
```vue
<template>
  <div>
    <TableSkeleton v-if="isLoading" :rows="6" :cols="4" />
    <el-table v-else :data="approvals">
      <!-- ... -->
    </el-table>
  </div>
</template>
```

#### 3.3 测试验证（30 分钟）

1. 限速网络（Chrome DevTools → Network → Slow 3G）
2. 刷新列表页，观察骨架屏效果
3. 对比优化前后感知速度

**完成标志**：
- [ ] `TableSkeleton.vue` 组件创建
- [ ] 3 个列表页应用骨架屏
- [ ] 用户体验测试通过
- [ ] 感知速度提升 30-50%

---

### 任务 4：接口性能压测（2 小时）

**目的**：验证 P99 延迟 < 2s 目标

**步骤**：

#### 4.1 安装压测工具（10 分钟）

```bash
# 方案 A：Apache Bench（推荐，简单）
# Windows: 需安装 XAMPP 或 Apache
# Linux: sudo apt-get install apache2-utils
# Mac: brew install ab

# 方案 B：JMeter（功能强大）
# 下载：https://jmeter.apache.org/download_jmeter.cgi
```

#### 4.2 编写压测脚本（30 分钟）

创建 `scripts/load-test.sh`：
```bash
#!/bin/bash

BASE_URL="http://localhost:8080"

echo "========================================="
echo "OpsBrain AI 接口性能压测"
echo "========================================="
echo ""

# 测试 1：工单列表查询
echo "测试 1：工单列表查询（100 并发，1000 次请求）"
ab -n 1000 -c 100 -H "Authorization: Bearer mock-token" \
   "${BASE_URL}/api/v1/tickets?page=1&pageSize=20"

# 测试 2：告警列表查询
echo "测试 2：告警列表查询（50 并发，500 次请求）"
ab -n 500 -c 50 -H "Authorization: Bearer mock-token" \
   "${BASE_URL}/api/v1/alerts?page=1&pageSize=20"

# 测试 3：Dashboard 统计
echo "测试 3：Dashboard 统计（30 并发，300 次请求）"
ab -n 300 -c 30 -H "Authorization: Bearer mock-token" \
   "${BASE_URL}/api/v1/tickets/stats"

echo ""
echo "========================================="
echo "压测完成！"
echo "========================================="
```

#### 4.3 执行压测（1 小时）

```bash
# 1. 启动后端服务
cd devops-platform-backend
./mvnw spring-boot:run

# 2. 执行压测
bash scripts/load-test.sh > docs/reports/report-87-load-test-result.txt
```

#### 4.4 分析结果（20 分钟）

**关键指标**：
- **吞吐量（Requests per second）**: > 100 req/s
- **P50 延迟（50% of requests）**: < 500ms
- **P99 延迟（99% of requests）**: < 2s
- **错误率（Failed requests）**: < 0.1%

**示例输出**：
```
Requests per second:    120.50 [#/sec] (mean)
Time per request:       8.30 [ms] (mean)
Time per request:       0.08 [ms] (mean, across all concurrent requests)

Percentage of requests served within a certain time (ms)
  50%    450
  66%    600
  75%    750
  80%    850
  90%   1200
  95%   1500
  98%   1800
  99%   1950  ← 目标 < 2000ms
 100%   2500 (longest request)
```

**完成标志**：
- [ ] 压测脚本创建
- [ ] 3 个关键接口压测完成
- [ ] P99 延迟 < 2s（达标）
- [ ] 压测报告：`docs/reports/report-87-load-test-result.txt`

---

## 📅 P2：本周完成（1 周）

### 任务 5：L2 告警完善（3 天）

**5.1 告警聚合降噪**（1 天）
- 相同告警 5 分钟内只推送一次
- 跨键风暴抑制（同服务多告警合并）

**5.2 告警历史趋势分析**（2 天）
- ECharts 折线图接入
- 按日期/服务/级别下钻

**预期收益**：
- 告警噪音降低 70%
- 运维人员效率提升 50%

---

### 任务 6：L3 审批完善（2 天）

**6.1 批量审批**（1 天）
- 一次批准多个待审批项
- 批量拒绝功能

**6.2 审批历史追溯**（1 天）
- 审批记录时间线
- 审批决策理由展示

**预期收益**：
- 审批效率提升 3 倍
- 可追溯性提升

---

### 任务 7：性能优化收官（2 天）

**7.1 慢查询排查**（1 天）
- 开启 PostgreSQL 慢查询日志
- 分析慢查询并优化

**7.2 连接池调优**（0.5 天）
- 根据压测结果调整 HikariCP 配置
- 配置连接超时和空闲超时

**7.3 缓存策略精细化**（0.5 天）
- 根据业务场景调整 TanStack Query 缓存时间
- Redis 缓存命中率监控

**预期收益**：
- 全链路性能提升 20-30%
- 支撑 100+ 并发用户

---

## 🚀 长期规划（1-3 个月）

### L4 半自动自愈（4 周）
- AI 自动执行脚本（需人审批）
- 闭环验证 + 回滚机制

### L5 预测性运维（8 周）
- 容量规划（预测资源不足）
- 故障预警（提前 3 天）

### 商业化拓展（12 周）
- 多租户隔离
- SaaS 部署
- 性能优化（支撑 1000+ 租户）

---

## 📊 进度追踪

### 已完成（批 87）

| 任务 | 状态 | 完成时间 | 收益 |
|------|------|----------|------|
| 前端代码审计 | ✅ | 2026-09-14 | 发现 4 类问题 |
| 富文本优化 | ✅ | 2026-09-14 | 构建速度 77% ↑ |
| 缓存策略优化 | ✅ | 2026-09-14 | 重复请求 40-60% ↓ |
| 死代码清理 | ✅ | 2026-09-14 | 维护负担 ↓ |
| 数据库索引设计 | ✅ | 2026-09-14 | 查询速度 5-50x ↑ |
| 文档编写 | ✅ | 2026-09-14 | 可追溯性 ↑ |

### 进行中

| 任务 | 状态 | 开始时间 | 预计完成 |
|------|------|----------|----------|
| 索引验证 | ⏳ | 待执行 | 10 分钟后 |

### 待启动

| 任务 | 优先级 | 预计开始 | 预计时长 |
|------|--------|----------|----------|
| 前端性能监控 | P1 | 今日 | 1 小时 |
| 骨架屏加载 | P1 | 今日 | 2 小时 |
| 接口性能压测 | P1 | 今日 | 2 小时 |
| L2 告警完善 | P2 | 本周 | 3 天 |
| L3 审批完善 | P2 | 本周 | 2 天 |
| 性能优化收官 | P2 | 本周 | 2 天 |

---

## 📝 相关文档

- **总结报告**: `docs/reports/report-87-final-summary.md`
- **验证指南**: `docs/reports/report-87-verification-guide.md`
- **数据库索引说明**: `docs/reports/report-87-database-index-supplement.md`
- **下一步建议**: `docs/reports/report-87-next-steps-recommendation.md`

---

## ✅ 检查清单

在开始下一个任务前，请确保：

- [ ] 所有文档已阅读
- [ ] 验证脚本已执行
- [ ] 验证报告已生成
- [ ] 索引创建成功
- [ ] 性能目标已明确
- [ ] 时间预算已确认

---

**最后更新**: 2026-09-14  
**下次更新**: 短期任务完成后（预计 2026-09-15）
