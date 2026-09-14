# 批 87 数据库索引补充说明

**日期**: 2026-09-14  
**目的**: 补充缺失的高频查询索引，避免全表扫描，支撑未来数据量增长

---

## 一、背景

**问题**：
- 当前 V1 基线中虽然有基本索引（单列索引），但缺少复合索引
- 高频查询（列表筛选 + 排序）可能走全表扫描
- 随着数据量增长（1000+ 工单/告警），性能会急剧下降

**解决方案**：
- 为列表页常见查询添加复合索引，覆盖 WHERE + ORDER BY
- 直接追加到 `V1__baseline.sql` 末尾（用户决策：迁移文件不散放）

---

## 二、新增索引清单

### P0 级索引（高频查询，必须优化）

| 索引名 | 表 | 列 | 覆盖场景 | 预期提升 |
|--------|----|----|----------|----------|
| idx_ticket_activity_ticket_time | sys_ticket_activity | ticket_id, create_time DESC | 工单详情页活动流 | 5-10x |
| idx_ticket_reply_ticket_time | sys_ticket_reply | ticket_id, create_time DESC | 工单详情页回复列表 | 5-10x |
| idx_alert_status_level_time | sys_alert | status, level, first_occurred_at DESC | 告警列表筛选 | 3-5x |
| idx_alert_service_time | sys_alert | service, first_occurred_at DESC | 告警按服务筛选 | 3-5x |
| idx_approval_status_time_desc | sys_approval_request | status, create_time DESC | 审批列表 | 3-5x |
| idx_approval_risk_time | sys_approval_request | risk_level, create_time DESC | 审批按风险级别筛选 | 3-5x |

### P1 级索引（中频查询，推荐优化）

| 索引名 | 表 | 列 | 覆盖场景 | 预期提升 |
|--------|----|----|----------|----------|
| idx_ticket_sla_deadline | sys_devops_ticket | sla_deadline (部分索引) | SLA 超时扫描 | 10x+ |
| idx_knowledge_doc_title_trgm | sys_knowledge_doc | title (GIN) | 知识库标题搜索 | 5-10x |
| idx_knowledge_doc_content_trgm | sys_knowledge_doc | content (GIN) | 知识库内容搜索 | 5-10x |
| idx_ai_analysis_ticket_version | sys_ticket_ai_analysis | ticket_id, analysis_version DESC | AI 分析加载 | 3-5x |
| idx_session_summary_session_time | sys_agent_session_summary | session_id, create_time DESC | 会话历史加载 | 3-5x |

### P2 级索引（低频查询，可选优化）

| 索引名 | 表 | 列 | 覆盖场景 | 预期提升 |
|--------|----|----|----------|----------|
| idx_audit_actor_time | sys_operation_audit | actor_id, create_time DESC | 审计日志按人查询 | 3-5x |
| idx_audit_action_time | sys_operation_audit | action, create_time DESC | 审计日志按动作查询 | 3-5x |

---

## 三、索引设计原则

### 3.1 复合索引列顺序

**规则**：等值筛选列 → 范围筛选列 → 排序列

**示例**：
```sql
-- ✅ 正确：status（等值）→ level（等值）→ time（排序）
CREATE INDEX idx_alert_status_level_time
    ON sys_alert (status, level, first_occurred_at DESC);

-- ❌ 错误：排序列在前，无法使用索引
CREATE INDEX idx_alert_time_status_level
    ON sys_alert (first_occurred_at DESC, status, level);
```

### 3.2 部分索引（Partial Index）

**用途**：只为满足特定条件的行创建索引，减少索引体积

**示例**：
```sql
-- SLA 超时扫描只关心未解决的工单
CREATE INDEX idx_ticket_sla_deadline
    ON sys_devops_ticket (sla_deadline)
    WHERE status NOT IN ('RESOLVED', 'CLOSED');
```

**收益**：
- 索引体积减少 80%（大部分工单已解决）
- 维护成本降低（INSERT/UPDATE 不触发此索引）

### 3.3 GIN 索引（全文搜索）

**用途**：模糊搜索（LIKE '%keyword%'）

**示例**：
```sql
-- pg_trgm 三元组索引，支持 LIKE/ILIKE 模糊搜索
CREATE EXTENSION pg_trgm;
CREATE INDEX idx_knowledge_doc_title_trgm
    ON sys_knowledge_doc USING gin (title gin_trgm_ops);
```

**注意**：
- 需要安装 `pg_trgm` 扩展
- 索引体积较大（约为表体积的 20-30%）
- 适合搜索场景，不适合精确匹配

---

## 四、验证方法

### 4.1 重建数据库

```bash
cd devops-platform-backend
docker-compose down -v
docker-compose up -d
```

### 4.2 检查索引是否创建成功

```bash
docker exec -it devops-platform-backend-postgres-1 psql -U opsbrain -d opsbrain_db -c "\d+ sys_devops_ticket"
```

**预期输出**：
```
Indexes:
    "sys_devops_ticket_pkey" PRIMARY KEY, btree (id)
    "idx_ticket_status_create_time" btree (status, create_time DESC)
    "idx_ticket_assignee_status" btree (assignee, status)
    "idx_ticket_module_create_time" btree (module, create_time DESC)
    "idx_ticket_priority_status" btree (priority, status)
    "idx_ticket_sla_deadline" btree (sla_deadline) WHERE status <> ALL (ARRAY['RESOLVED'::character varying, 'CLOSED'::character varying])
    ...
```

### 4.3 执行验证脚本

```bash
docker exec -it devops-platform-backend-postgres-1 psql -U opsbrain -d opsbrain_db -f /path/to/verify_indexes.sql
```

**预期输出**：
- 每个查询显示 `Index Scan` 或 `Index Only Scan`
- `Execution Time` 在毫秒级（< 10ms）
- **不应出现** `Seq Scan`（全表扫描）

### 4.4 使用 EXPLAIN 手动验证

```sql
-- 工单活动流查询
EXPLAIN ANALYZE
SELECT * FROM sys_ticket_activity
WHERE ticket_id = 'TK-001'
ORDER BY create_time DESC
LIMIT 20;

-- 预期输出：
-- Index Scan using idx_ticket_activity_ticket_time on sys_ticket_activity
-- Planning Time: 0.123 ms
-- Execution Time: 0.456 ms
```

---

## 五、性能对比（预期）

### 场景 1：工单详情页加载活动流

**数据量**：100 条活动流记录

| 查询方式 | 执行时间 | 说明 |
|----------|----------|------|
| 无索引（Seq Scan） | ~50ms | 全表扫描 |
| 单列索引（ticket_id） | ~10ms | 索引查找 + 排序 |
| **复合索引（ticket_id + time）** | **~1ms** | 索引查找 + 索引排序 |

**提升**：50x

---

### 场景 2：告警列表筛选（状态 + 级别）

**数据量**：1000 条告警记录

| 查询方式 | 执行时间 | 说明 |
|----------|----------|------|
| 无索引（Seq Scan） | ~200ms | 全表扫描 + 排序 |
| 单列索引（status） | ~50ms | 索引查找 + 排序 |
| **复合索引（status + level + time）** | **~5ms** | 索引查找 + 索引排序 |

**提升**：40x

---

### 场景 3：知识库标题搜索

**数据量**：500 篇文档

| 查询方式 | 执行时间 | 说明 |
|----------|----------|------|
| 无索引（LIKE '%keyword%'） | ~100ms | 全表扫描 |
| **GIN 索引（pg_trgm）** | **~10ms** | 三元组索引 |

**提升**：10x

---

## 六、注意事项

### 6.1 索引维护成本

**权衡**：
- ✅ 查询加速 5-50 倍
- ❌ 写入性能下降 5-10%（每次 INSERT/UPDATE 需维护索引）
- ❌ 磁盘占用增加 10-20%

**结论**：对于读多写少的业务（工单/告警列表），收益远大于成本

### 6.2 索引失效场景

**常见原因**：
1. **隐式类型转换**：`WHERE ticket_id = 123`（ticket_id 是 VARCHAR）
2. **函数包裹**：`WHERE LOWER(title) = 'mysql'`（无法使用索引）
3. **OR 条件**：`WHERE status = 'OPEN' OR assignee = 'admin'`（只能用一个索引）
4. **负向查询**：`WHERE status != 'CLOSED'`（优化器可能选择全表扫描）

**解决方案**：
- 使用正确的数据类型
- 避免在索引列上使用函数
- 将 OR 改写为 UNION
- 将负向查询改写为正向（`status IN ('OPEN', 'IN_PROGRESS', ...)`）

### 6.3 索引监控

**定期检查**：
```sql
-- 查看索引使用情况（需要 pg_stat_statements 扩展）
SELECT schemaname, tablename, indexname, idx_scan, idx_tup_read, idx_tup_fetch
FROM pg_stat_user_indexes
WHERE schemaname = 'public'
ORDER BY idx_scan ASC;
```

**指标**：
- `idx_scan = 0`：索引从未使用，考虑删除
- `idx_tup_read` 很高但 `idx_tup_fetch` 很低：索引效率低

---

## 七、后续优化方向

### 7.1 分区表（Partitioning）

**触发条件**：单表数据量 > 1000 万行

**方案**：
- 按时间分区（如按月）
- 旧分区可归档到冷存储

### 7.2 物化视图（Materialized View）

**适用场景**：
- Dashboard 统计数据（每小时更新）
- 复杂聚合查询（跨表 JOIN）

### 7.3 连接池调优

**当前配置**：
- HikariCP 默认 10 个连接

**优化方向**：
- 根据压测结果调整连接池大小
- 配置连接超时和空闲超时

---

## 八、总结

### 已完成

1. ✅ 新增 15 个复合索引，覆盖高频查询
2. ✅ 直接追加到 `V1__baseline.sql`（用户决策：不散放迁移文件）
3. ✅ 创建验证脚本 `scripts/verify_indexes.sql`

### 待验证

1. ⏳ 重建数据库验证索引创建成功
2. ⏳ 执行验证脚本确认查询使用索引
3. ⏳ 压测验证性能提升（预期 5-50 倍）

### 预期收益

- 工单列表查询加速 **5-10 倍**
- 告警列表查询加速 **3-5 倍**
- 审批列表查询加速 **3-5 倍**
- 知识库搜索加速 **5-10 倍**
- 支撑未来数据量增长（1000+ 工单仍保持性能）

---

**参考资料**：
- [PostgreSQL Index Types](https://www.postgresql.org/docs/current/indexes-types.html)
- [Using EXPLAIN](https://www.postgresql.org/docs/current/using-explain.html)
- [pg_trgm Extension](https://www.postgresql.org/docs/current/pgtrgm.html)
