# MySQL 故障排查手册

> 适用范围：慢查询定位与优化、执行计划分析、深分页、主从复制延迟、在线慢查询查看、大事务影响。
> 排查原则：**先量化再优化**——没有执行计划与慢日志证据的 SQL 优化都是猜。

---

## 1. 慢查询定位与优化

### 问题描述
业务反馈接口变慢，怀疑 MySQL 慢查询，需要定位具体 SQL 并给出优化方案。

### 常见原因
1. **未命中索引**：全表扫描或索引选择错误（force index 才走对）
2. **索引失效**：函数包裹列（`WHERE DATE(c)=…`）、隐式类型转换、前导通配 `LIKE '%x'`
3. **深分页**:`LIMIT 100000,10` 扫 10 万行扔 99990 行
4. **锁等待**：大事务、DDL 元数据锁、长事务未提交
5. **统计信息过期**:`ANALYZE TABLE` 后优化器才能选对索引
6. **写放大**:`innodb_flush_log_at_trx_commit=1` + 高频小事务

### 排查步骤

#### 1. 打开并收集慢日志
```sql
-- 会话级观测（改动需 validated，不改全局默认值）
SET GLOBAL slow_query_log = ON;
SET GLOBAL long_query_time = 1;        -- 生产建议 0.5~1s
SET GLOBAL log_queries_not_using_indexes = ON;
SHOW VARIABLES LIKE 'slow_query_log_file';   -- 找到日志路径
```
```bash
# 汇总排序：按总耗时 top 20
mysqldumpslow -s t -t 20 /var/lib/mysql/slow.log
# 或 pt-query-digest 出直方图与指纹归并
pt-query-digest /var/lib/mysql/slow.log | less
```

#### 2. 看当前正在跑的慢查询
```sql
SHOW FULL PROCESSLIST;
-- 关注：Time 大、State 卡在 Sending data / Sorting result / Waiting for lock
SELECT * FROM information_schema.PROCESSLIST WHERE TIME > 5 ORDER BY TIME DESC;
```

#### 3. 逐条 ANALYZE（别只 EXPLAIN)
```sql
EXPLAIN ANALYZE SELECT ...;   -- MySQL 8.0.18+：真实执行数与估行并排
```
重点字段：`type`(system/const/ref/range/**ALL 全表扫**)、`key`（实际用上的索引）、
`rows`（估行数）、`filtered`、`Extra`(Using filesort / Using temporary 双红旗）。

### 解决方案

#### 方案 1：补索引（最优先）
```sql
-- 复合索引按「等值列在前、范围列在后、排序列收尾」
ALTER TABLE orders ADD INDEX idx_status_created (status, created_at);
-- 补完跑统计，否则优化器还拿旧地图
ANALYZE TABLE orders;
```

#### 方案 2：改写 SQL
- 函数从列上移到值上：`WHERE DATE(created_at)='2026-09-08'` → `WHERE created_at >= '2026-09-08' AND created_at < '2026-09-09'`
- 覆盖索引消回表：`SELECT` 只取索引内列；大宽表分页走延迟关联（见 §3)

#### 方案 3：参数与事务纪律
- 事务短小：单事务 < 5s，批量改分批提交；
- `innodb_flush_log_at_trx_commit` 非核心库可评 2（折损≈1 秒数据）。

### 验证方法
优化后同 SQL `EXPLAIN ANALYZE` 对账：执行耗时、扫描行数两个数必须都降；
慢日志开 `long_query_time=0` 采样 10 分钟对比指纹总量。**两个数字不都在账上，不算优化完。**

---

## 2. 执行计划分析（SELECT 慢）

`EXPLAIN ANALYZE` 是唯一一锤定音的工具：

| 字段 | 红旗 | 处置 |
| --- | --- | --- |
| `type=ALL` | 全表扫 | 补索引/改写 |
| `rows` ≫ 实际返回 | 估行偏差大 | `ANALYZE TABLE` 刷统计；直方图（8.0 `ANALYZE TABLE ... UPDATE HISTOGRAM`) |
| `Extra: Using filesort` | 无索引排序 | 排序列入索引尾（顺序同 ORDER BY 方向） |
| `Extra: Using temporary` | 临时表 | GROUP BY 列入索引；派生表合流下推 |
| `key=NULL` 但 `key_len` 候选存在 | 选了不走 | 查隐式转换（列 utf8mb4 vs 字面 utf8)——join 双端字符集必须一致 |

JOIN 顺序看 `EXPLAIN` 行序：驱动表在上——被驱动表的每次探测都必须走索引，
否则 N×M 放大成灾难。拿不准时 `STRAIGHT_JOIN` 临时钉住顺序对照计时。

---

## 3. 深分页（LIMIT 10000,10 很慢)

### 原因
`LIMIT N,10` 需要扫并排序前 N+10 行再丢弃前 N 行——N 越大越接近全表排序。

### 方案 A：主键/索引续传（推荐）
```sql
-- 记住上一页最后的主键，下一页不再 OFFSET
SELECT * FROM orders
 WHERE id > :last_id          -- 上次看到的最大 id
  AND status = 'PAID'
 ORDER BY id LIMIT 10;
```
前提：排序键稳定唯一（id 或 (status,id) 复合续传，多列续传用行比较
`(status,id) > (:s,:id)`)。

### 方案 B：延迟关联（先拿 id 再回表）
```sql
SELECT o.* FROM orders o
 JOIN (SELECT id FROM orders WHERE status='PAID' ORDER BY id LIMIT 10000,10) t
   ON t.id = o.id;
```
子查询只扫索引列，回表只 10 行——从「10 万行回表」变「10 行回表」。

### 禁区
业务上深页码本质少用：搜索走搜索引擎，翻页给游标不给页码。

---

## 4. 主从复制延迟排查

```sql
SHOW REPLICA STATUS\G
-- 关键三行:
-- Seconds_Behind_Source      延迟秒数(网络断连时是 NULL,别当 0)
-- Replica_IO_Running         拉 binlog 线程
-- Replica_SQL_Running        回放线程
```

| 场景 | 特征 | 处置 |
| --- | --- | --- |
| IO 断 | `Replica_IO_Running=No` | 网络/账号/binlog 清除——`MASTER_AUTO_POSITION` 重建 |
| SQL 追不上 | IO Yes、延迟单调涨 | 大事务串行回放：拆事务;8.0 开 `replica_parallel_type=LOGICAL_CLOCK` + `replica_parallel_workers=8` |
| 无索引灾难 | 从库回放卡 Rows_log_event | 从库补索引后追平，再回主规范（主从 schema 必须一致） |
| 主库突发 | binlog 洪峰（批量任务） | 批处理挪低峰/分批;延迟期间读写分离口径降级主读 |

口径纪律：`Seconds_Behind_Source` 是「估计值」，对账以 binlog 位点差
（`SHOW MASTER STATUS` 与 relay 位点）为准；监控告警用位点差不误报。

---

## 5. 在线慢查询查看（不改配置的前提下）

```sql
-- 谁在跑:Time/State/Info 三列锁定
SHOW FULL PROCESSLIST;

-- 8.0 观测库
SELECT * FROM sys.statement_analysis ORDER BY exec_count * avg_latency DESC LIMIT 10;
SELECT digest_text, count_star, avg_timer_wait
  FROM performance_schema.events_statements_summary_by_digest
 ORDER BY sum_timer_wait DESC LIMIT 10;
```
应急杀会话：`KILL <Id>;`（只杀 SELECT 长会话，写会话杀了回滚更久——
先看是 DML 还是 DQL)。

---

## 6. 大事务未提交的影响

1. **行锁不释放**：并发 UPDATE 同行全堵 `Waiting for row lock`,thread_running 飙升；
2. **undo 滚动暴涨**:history list length 增长 → purge 追不上 → 全库读视图膨胀、查询变慢；
3. **复制延迟**：从库串行回放一个大事务，延迟秒级→分钟级;
4. **闪断放大**:kill 后回滚时间 ≈ 事务运行时间（甚至更久）——别在高峰 kill 大事务。

定位与处置：
```sql
SELECT * FROM information_schema.innodb_trx
 WHERE TIME_TO_SEC(TIMEDIFF(NOW(), trx_started)) > 60 ORDER BY trx_started;
-- 连到执行线程:sys.innodb_lock_waits / performance_schema.data_locks
```
纪律：批处理分批提交（每批 ≤1000 行）;DDL 非 require 长事务；生产禁交互式
`BEGIN;` 后离开工位。
