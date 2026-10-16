-- 批 87 索引验证脚本
-- 用途：验证新增索引是否生效，查询计划是否使用索引扫描
-- 使用方法：
--   1. 连接到数据库：psql -h localhost -U opsbrain -d opsbrain_db
--   2. 执行本脚本：\i verify_indexes.sql
--   3. 检查输出中是否包含 "Index Scan" 或 "Index Only Scan"
--   4. 如果出现 "Seq Scan"，说明索引未生效或查询条件不匹配

\echo '========================================';
\echo '批 87 索引验证脚本';
\echo '========================================';
\echo '';

-- 验证 1：工单活动流查询
\echo '验证 1：工单活动流查询（应使用 idx_ticket_activity_ticket_time）';
EXPLAIN (ANALYZE, BUFFERS, VERBOSE)
SELECT * FROM sys_ticket_activity
WHERE ticket_id = 'TK-001'
ORDER BY create_time DESC
LIMIT 20;
\echo '';

-- 验证 2：工单回复查询
\echo '验证 2：工单回复查询（应使用 idx_ticket_reply_ticket_time）';
EXPLAIN (ANALYZE, BUFFERS, VERBOSE)
SELECT * FROM sys_ticket_reply
WHERE ticket_id = 'TK-001'
ORDER BY create_time DESC
LIMIT 20;
\echo '';

-- 验证 3：告警列表筛选（状态 + 级别）
\echo '验证 3：告警列表筛选（应使用 idx_alert_status_level_time）';
EXPLAIN (ANALYZE, BUFFERS, VERBOSE)
SELECT * FROM sys_alert
WHERE status = 'FIRING' AND level = 'P0'
ORDER BY first_occurred_at DESC
LIMIT 20;
\echo '';

-- 验证 4：告警按服务筛选
\echo '验证 4：告警按服务筛选（应使用 idx_alert_service_time）';
EXPLAIN (ANALYZE, BUFFERS, VERBOSE)
SELECT * FROM sys_alert
WHERE service = 'mysql'
ORDER BY first_occurred_at DESC
LIMIT 20;
\echo '';

-- 验证 5：审批列表查询
\echo '验证 5：审批列表查询（应使用 idx_approval_status_time_desc）';
EXPLAIN (ANALYZE, BUFFERS, VERBOSE)
SELECT * FROM sys_approval_request
WHERE status = 'PENDING'
ORDER BY create_time DESC
LIMIT 20;
\echo '';

-- 验证 6：审批按风险级别筛选
\echo '验证 6：审批按风险级别筛选（应使用 idx_approval_risk_time）';
EXPLAIN (ANALYZE, BUFFERS, VERBOSE)
SELECT * FROM sys_approval_request
WHERE risk_level = 'HIGH_RISK_EXECUTION'
ORDER BY create_time DESC
LIMIT 20;
\echo '';

-- 验证 7：工单 SLA 超时扫描
\echo '验证 7：工单 SLA 超时扫描（应使用 idx_ticket_sla_deadline）';
EXPLAIN (ANALYZE, BUFFERS, VERBOSE)
SELECT * FROM sys_devops_ticket
WHERE sla_deadline < NOW()
  AND status NOT IN ('RESOLVED', 'CLOSED')
LIMIT 20;
\echo '';

-- 验证 8：知识库标题模糊搜索
\echo '验证 8：知识库标题模糊搜索（应使用 idx_knowledge_doc_title_trgm）';
EXPLAIN (ANALYZE, BUFFERS, VERBOSE)
SELECT * FROM sys_knowledge_doc
WHERE title ILIKE '%MySQL%'
ORDER BY update_time DESC
LIMIT 20;
\echo '';

-- 验证 9：AI 分析查询
\echo '验证 9：AI 分析查询（应使用 idx_ai_analysis_ticket_version）';
EXPLAIN (ANALYZE, BUFFERS, VERBOSE)
SELECT * FROM sys_ticket_ai_analysis
WHERE ticket_id = 'TK-001'
ORDER BY analysis_version DESC
LIMIT 5;
\echo '';

-- 验证 10：会话摘要查询
\echo '验证 10：会话摘要查询（应使用 idx_session_summary_session_time）';
EXPLAIN (ANALYZE, BUFFERS, VERBOSE)
SELECT * FROM sys_agent_session_summary
WHERE session_id = 'session-123'
ORDER BY create_time DESC
LIMIT 10;
\echo '';

-- 验证 11：审计日志按操作者查询
\echo '验证 11：审计日志按操作者查询（应使用 idx_audit_actor_time）';
EXPLAIN (ANALYZE, BUFFERS, VERBOSE)
SELECT * FROM sys_operation_audit
WHERE actor_id = 'admin'
  AND create_time > NOW() - INTERVAL '7 days'
ORDER BY create_time DESC
LIMIT 50;
\echo '';

-- 验证 12：审计日志按动作类型查询
\echo '验证 12：审计日志按动作类型查询（应使用 idx_audit_action_time）';
EXPLAIN (ANALYZE, BUFFERS, VERBOSE)
SELECT * FROM sys_operation_audit
WHERE action LIKE 'ticket.delete%'
ORDER BY create_time DESC
LIMIT 50;
\echo '';

-- 验证完成
\echo '========================================';
\echo '验证完成！';
\echo '检查点：';
\echo '1. 每个查询应显示 "Index Scan" 或 "Index Only Scan"';
\echo '2. "Execution Time" 应在毫秒级（< 10ms）';
\echo '3. 如果出现 "Seq Scan"，说明索引未生效';
\echo '========================================';
