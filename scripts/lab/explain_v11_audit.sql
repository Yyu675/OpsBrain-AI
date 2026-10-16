-- =============================================================================
-- 真窗批件:V11 索引前后 EXPLAIN 对照 + pg_trgm 探头(S5-4.2 演练挂账消号用具)
--
-- 用法(psql 进 opsbrain 库):
--   docker compose exec postgres psql -U opsbrain -d opsbrain -f /lab/explain_v11_audit.sql
-- 或本地 PATH 拷入。每个段界会打印区隔,整卷可重跑——DROP 段一律在事务内+ROLLBACK,
-- 索引本体任何时刻不动。产物建议:整段输出重定向为 explain-<date>.txt 回贴。
--
-- 行情前提:真窗数据在库(至少运行数日的告警/工单量,空表 EXPLAIN 无意义)。
-- =============================================================================

\echo '==================== A. 表情快照(行数/体量/索引面) ===================='
SELECT relname, n_live_tup AS rows,
       pg_size_pretty(pg_table_size(relid)) AS table_sz,
       pg_size_pretty(pg_indexes_size(relid)) AS idx_sz
  FROM pg_stat_user_tables
 WHERE relname IN ('sys_alert','sys_devops_ticket')
 ORDER BY pg_total_relation_size(relid) DESC;

\echo '==================== B. [现状 = V11 已生效] 风暴聚合退路查询 ===================='
-- 口供:AlertRepository.findActiveGroupTicket —— dedup 未命中时每条告警必跑
EXPLAIN (ANALYZE, BUFFERS, TIMING) 
SELECT * FROM sys_alert
 WHERE service = 'order-service' AND module = 'app'
   AND status IN ('FIRING','ACKNOWLEDGED')
   AND ticket_id IS NOT NULL
   AND last_occurred_at >= CURRENT_TIMESTAMP - 5 * INTERVAL '1 minute'
 ORDER BY last_occurred_at DESC
 LIMIT 1;

\echo '==================== B2. [现状] 今日建单 KPI ===================='
-- 口供:DevOpsTicketRepository.countCreatedToday —— 首页 KPI 每次加载必扫
EXPLAIN (ANALYZE, BUFFERS, TIMING)
SELECT COUNT(*) FROM sys_devops_ticket
 WHERE create_time >= CURRENT_DATE
   AND create_time < CURRENT_DATE + INTERVAL '1 day';

\echo '=========== C. [模拟 V11 前] 同查询比桩位(事务内 DROP + ROLLBACK,索引本体不动) ==========='
BEGIN;
DROP INDEX IF EXISTS idx_alert_group_dedup;
DROP INDEX IF EXISTS idx_ticket_create_time;
\echo '--- C1. 无 V11 的风暴聚合退路 ---'
EXPLAIN (ANALYZE, BUFFERS, TIMING)
SELECT * FROM sys_alert
 WHERE service = 'order-service' AND module = 'app'
   AND status IN ('FIRING','ACKNOWLEDGED')
   AND ticket_id IS NOT NULL
   AND last_occurred_at >= CURRENT_TIMESTAMP - 5 * INTERVAL '1 minute'
 ORDER BY last_occurred_at DESC
 LIMIT 1;
\echo '--- C2. 无 V11 的今日建单 KPI ---'
EXPLAIN (ANALYZE, BUFFERS, TIMING)
SELECT COUNT(*) FROM sys_devops_ticket
 WHERE create_time >= CURRENT_DATE
   AND create_time < CURRENT_DATE + INTERVAL '1 day';
ROLLBACK;

\echo '==================== D. pg_trgm 探头(工单 keyword LIKE,事务内+回滚) ===================='
-- 升级条款:慢查询静态审计观察档①(工单 LOWER(col) LIKE '%kw%' 全表扫)。
-- 探头目的只收「装了 GIN 后计划是否变与泄助量级」,EXT 本体在回滚后不在库。
BEGIN;
CREATE EXTENSION IF NOT EXISTS pg_trgm;
\echo '--- D1. 无 GIN 的 keyword 查询现状 ---'
EXPLAIN (ANALYZE, BUFFERS, TIMING)
SELECT id, title FROM sys_devops_ticket
 WHERE LOWER(title) LIKE LOWER('%cpu%') ESCAPE '\\'
 ORDER BY create_time DESC LIMIT 20;
\echo '--- D2. (模拟) GIN trgm 索引在时的同查询 ---'
CREATE INDEX idx_ticket_title_trgm ON sys_devops_ticket USING GIN (LOWER(title) gin_trgm_ops);
EXPLAIN (ANALYZE, BUFFERS, TIMING)
SELECT id, title FROM sys_devops_ticket
 WHERE LOWER(title) LIKE LOWER('%cpu%') ESCAPE '\\'
 ORDER BY create_time DESC LIMIT 20;
ROLLBACK;

\echo '==================== E. 索引灰生堂档案:面账+序化(周巡口径) ===================='
SELECT schemaname, relname AS table, indexrelname AS idx,
       idx_scan AS scans_since_reset,
       pg_size_pretty(pg_relation_size(indexrelid)) AS sz
  FROM pg_stat_user_indexes
 WHERE relname IN ('sys_alert','sys_devops_ticket')
 ORDER BY pg_relation_size(indexrelid) DESC;

\echo '==================== 交件判词(自填) ===================='
\echo 'B(v11在) vs C(模拟无v11):实际执行时间/行数差明显 ⇒ 索引铸型有功;'
\echo 'D1 vs D2:若 D2 明显优 ⇒ pg_trgm 决议升级;若 D2 无动 ⇒ pg_trgm 作罢(挂账销案)。'
