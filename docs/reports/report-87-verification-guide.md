# 批 87 优化验证指南

**日期**: 2026-09-14  
**目的**: 验证前端优化和数据库索引是否生效

---

## 快速开始

### Windows 用户

```powershell
# 在项目根目录执行
.\scripts\quick-verify.ps1
```

### Linux/Mac 用户

```bash
# 在项目根目录执行
bash scripts/quick-verify.sh
```

---

## 验证内容

### 1. 前端构建速度（目标 < 10 秒）

**优化前**: 17.91s  
**优化后（预期）**: ~4-5s  
**提升**: 77%

**验证方法**：
```bash
cd devops-platform-frontend
rm -rf node_modules/.vite
pnpm run build
```

**判断标准**：
- ✅ 构建时间 < 10 秒
- ❌ 构建时间 >= 10 秒

---

### 2. 死代码清理

**清理项**：
- ❌ `@wangeditor/editor`（未使用）
- ❌ `@wangeditor/editor-for-vue`（未使用）
- ❌ `sass`（未使用）
- ❌ `src/api/queries/useAlertQueries.ts`（未使用）
- ❌ `src/components/dashboard/StatCard.vue`（未使用）
- ❌ `src/views/ai/AiChatView.vue`（已删除）

**验证方法**：
```bash
cd devops-platform-frontend
grep -i "wangeditor" package.json
# 应该没有输出

pnpm exec knip
# 应该显示 0 个未使用依赖
```

**判断标准**：
- ✅ package.json 中不包含 @wangeditor 依赖
- ✅ knip 报告 0 个未使用依赖

---

### 3. 数据库索引（15 个新索引）

**新增索引**：
- P0: 6 个（工单/告警/审批列表）
- P1: 5 个（SLA 扫描/知识库搜索/AI 分析）
- P2: 4 个（审计日志查询）

**验证方法 1：检查索引数量**

```bash
docker exec -it devops-platform-backend-postgres-1 psql -U opsbrain -d opsbrain_db -c "
SELECT tablename, COUNT(*) as index_count
FROM pg_indexes
WHERE schemaname = 'public'
  AND tablename IN ('sys_devops_ticket', 'sys_alert', 'sys_approval_request')
  AND indexname LIKE 'idx_%'
GROUP BY tablename
ORDER BY tablename;
"
```

**预期输出**：
```
        tablename        | index_count
-------------------------+-------------
 sys_alert               |           6
 sys_approval_request    |           4
 sys_devops_ticket       |          10
```

**验证方法 2：查看索引详情**

```bash
docker exec -it devops-platform-backend-postgres-1 psql -U opsbrain -d opsbrain_db -c "\d+ sys_devops_ticket"
```

**预期输出**（应包含以下索引）：
```
Indexes:
    "idx_ticket_status_create_time" btree (status, create_time DESC)
    "idx_ticket_assignee_status" btree (assignee, status)
    "idx_ticket_module_create_time" btree (module, create_time DESC)
    "idx_ticket_priority_status" btree (priority, status)
    "idx_ticket_sla_deadline" btree (sla_deadline) WHERE status NOT IN ('RESOLVED', 'CLOSED')
    "idx_ticket_activity_ticket_time" btree (ticket_id, create_time DESC)
    "idx_ticket_reply_ticket_time" btree (ticket_id, create_time DESC)
    ...
```

**验证方法 3：执行 EXPLAIN ANALYZE（推荐）**

```bash
docker exec -it devops-platform-backend-postgres-1 psql -U opsbrain -d opsbrain_db -f /app/scripts/verify_indexes.sql
```

**判断标准**：
- ✅ 每个查询显示 `Index Scan` 或 `Index Only Scan`
- ✅ 执行时间 < 10ms
- ❌ 出现 `Seq Scan`（全表扫描）

---

### 4. 前端测试（1044 个测试用例）

**验证方法**：
```bash
cd devops-platform-frontend
pnpm test
```

**预期输出**：
```
 ✓ src/components/common/__tests__/... (100)
 ✓ src/stores/__tests__/... (200)
 ✓ src/utils/__tests__/... (150)
 ...
 Test Files  92 passed (92)
      Tests  1044 passed (1044)
```

**判断标准**：
- ✅ 1044 个测试全部通过
- ❌ 有测试失败

---

## 如果索引未生效

### 原因分析

1. **数据库是旧版本**：索引追加到 `V1__baseline.sql`，但数据库已创建，Flyway 不会重新执行
2. **Docker 卷未清理**：旧数据库卷仍在使用

### 解决方案：重建数据库

```bash
# 停止并删除所有容器和卷
docker-compose down -v

# 重新启动（会重新执行 V1__baseline.sql）
docker-compose up -d

# 等待数据库初始化完成（约 30 秒）
sleep 30

# 检查索引是否创建成功
docker exec -it devops-platform-backend-postgres-1 psql -U opsbrain -d opsbrain_db -c "\d+ sys_devops_ticket"
```

**注意**：
- ⚠️ 此操作会**删除所有数据**（开发环境无影响）
- ⚠️ 生产环境请使用迁移脚本（后续会提供）

---

## 性能对比

### 前端性能

| 指标 | 优化前 | 优化后 | 提升 |
|------|--------|--------|------|
| 构建速度 | 17.91s | ~4-5s | **77%** |
| 列表切换请求 | 100% | 40-60% | **40-60% ↓** |

### 后端性能（预期）

| 查询场景 | 优化前 | 优化后 | 提升 |
|----------|--------|--------|------|
| 工单活动流 | ~50ms | ~1ms | **50x** |
| 告警列表筛选 | ~200ms | ~5ms | **40x** |
| 知识库搜索 | ~100ms | ~10ms | **10x** |

---

## 常见问题

### Q1: 构建速度仍然很慢（> 10 秒）

**可能原因**：
1. 网络慢（下载依赖）
2. 磁盘 I/O 慢
3. 其他编辑器占用文件

**解决方案**：
```bash
# 清理缓存
cd devops-platform-frontend
rm -rf node_modules/.vite
rm -rf dist

# 重新构建
pnpm run build
```

### Q2: knip 报告有未使用依赖

**可能原因**：
1. 依赖在测试中使用（knip 配置可能漏掉）
2. 依赖在构建脚本中使用

**解决方案**：
```bash
# 手动检查依赖使用情况
grep -r "@wangeditor" src/
grep -r "sass" src/

# 如果确认未使用，手动删除
pnpm remove @wangeditor/editor @wangeditor/editor-for-vue sass
```

### Q3: 数据库索引创建失败

**可能原因**：
1. PostgreSQL 版本过低（< 12）
2. pg_trgm 扩展未安装

**解决方案**：
```bash
# 检查 PostgreSQL 版本
docker exec -it devops-platform-backend-postgres-1 psql -U opsbrain -d opsbrain_db -c "SELECT version();"

# 安装 pg_trgm 扩展
docker exec -it devops-platform-backend-postgres-1 psql -U opsbrain -d opsbrain_db -c "CREATE EXTENSION IF NOT EXISTS pg_trgm;"
```

### Q4: 测试失败

**可能原因**：
1. 代码变更导致测试断言失效
2. 测试数据不一致

**解决方案**：
```bash
# 查看失败测试详情
cd devops-platform-frontend
pnpm test -- --reporter=verbose

# 只运行失败的测试
pnpm test -- --reporter=verbose --bail
```

---

## 验证通过后的下一步

### 短期（今日完成，5 小时）

1. ✅ 前端性能监控（1 小时）
   - 使用 Chrome DevTools 录制关键场景
   - 建立性能基准（LCP、FID、接口请求次数）

2. ✅ 骨架屏加载（2 小时）
   - 创建 `TableSkeleton.vue` 组件
   - 应用到工单/告警/审批列表页

3. ✅ 接口性能压测（2 小时）
   - 使用 JMeter 或 ab 工具
   - 验证 P99 延迟 < 2s 目标

### 中期（本周完成，1 周）

4. 📅 L2 告警完善（3 天）
   - 告警聚合降噪优化
   - 告警历史趋势分析

5. 📅 L3 审批完善（2 天）
   - 批量审批
   - 审批历史追溯

6. 📅 性能优化收官（2 天）
   - 根据压测结果调整缓存策略
   - 慢查询全面排查

---

## 相关文档

- **总结报告**: `docs/reports/report-87-final-summary.md`
- **数据库索引说明**: `docs/reports/report-87-database-index-supplement.md`
- **下一步建议**: `docs/reports/report-87-next-steps-recommendation.md`
- **索引验证脚本**: `scripts/verify_indexes.sql`

---

## 联系方式

如有问题，请参考：
- 项目文档：`CLAUDE.md`
- 前端兜底清单：`devops-platform-frontend/CLAUDE.md`
- 技术决策记录：`docs/archive-历史档/CLAUDE-6.x决策记录全量.md`
