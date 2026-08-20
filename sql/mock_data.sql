-- =====================================================================
-- OpsBrain AI Mock Data Script
-- Description: 测试与演示数据，用于开发环境快速验证功能
-- Usage: psql -U postgres -d devops_platform < mock_data.sql
-- =====================================================================

-- 清空现有数据（保留表结构）
TRUNCATE TABLE sys_ticket_tag CASCADE;
TRUNCATE TABLE sys_ticket_attachment CASCADE;
TRUNCATE TABLE sys_ticket_activity CASCADE;
TRUNCATE TABLE sys_ticket_reply CASCADE;
TRUNCATE TABLE sys_devops_ticket CASCADE;
TRUNCATE TABLE sys_knowledge_doc_tag CASCADE;
TRUNCATE TABLE sys_knowledge_doc_history CASCADE;
TRUNCATE TABLE sys_knowledge_doc CASCADE;
TRUNCATE TABLE sys_knowledge_chunk CASCADE;
TRUNCATE TABLE sys_agent_tool_execution CASCADE;
TRUNCATE TABLE sys_agent_session_summary CASCADE;
TRUNCATE TABLE sys_agent_call_log CASCADE;

-- ---------------------------------------------------------------------
-- 1. 知识文档 (sys_knowledge_doc)
-- ---------------------------------------------------------------------
INSERT INTO sys_knowledge_doc (id, title, category, author, content, summary, version, content_hash, simhash, status, index_status, chunk_count, knowledge_source) VALUES
(1, 'K8s故障排查手册.md', '容器编排', 'DevOps团队', E'## Pod CrashLoopBackOff 问题排查\n\n### 现象\nPod 持续重启，状态显示 CrashLoopBackOff。\n\n### 排查步骤\n1. 查看日志：`kubectl logs <pod-name> -n <namespace> --previous`\n2. 检查配置：`kubectl describe pod <pod-name> -n <namespace>`\n3. 检查资源限制：确认 CPU/内存配额是否合理\n\n### 常见原因\n- 镜像拉取失败\n- 容器启动命令错误\n- 资源不足（OOMKilled）\n- 存活探针（liveness probe）配置过严\n\n### 解决方案\n根据日志定位具体错误，调整配置后重新部署。', 'Pod 故障排查指南', 1, 'a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6q7r8s9t0u1v2w3x4y5z6a7b8c9d0e1f2', 123456789, 'PUBLISHED', 'INDEXED', 5, 'SOP'),
(2, '数据库慢查询优化.md', '数据库', '性能团队', E'## MySQL 慢查询优化实践\n\n### 识别慢查询\n1. 开启慢查询日志：`SET GLOBAL slow_query_log = ON;`\n2. 设置阈值：`SET GLOBAL long_query_time = 2;`\n3. 查看慢查询：`SHOW FULL PROCESSLIST;`\n\n### 优化手段\n- **索引优化**：为 WHERE/JOIN 字段添加索引\n- **查询重写**：避免 SELECT *，减少回表\n- **分页优化**：使用 LIMIT offset 改为主键范围查询\n- **连接池调优**：合理设置连接数与超时时间\n\n### 案例\n优化前：`SELECT * FROM orders WHERE user_id=123 LIMIT 10000,10;` (2.3s)\n优化后：`SELECT * FROM orders WHERE id > 10000 AND user_id=123 LIMIT 10;` (0.05s)', '慢查询识别与优化', 1, 'b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6q7r8s9t0u1v2w3x4y5z6a7b8c9d0e1f2g3', 987654321, 'PUBLISHED', 'INDEXED', 3, 'SOP'),
(3, '线上发布流程规范.md', '流程制度', 'SRE团队', E'## 线上发布标准流程\n\n### 发布前检查\n- [ ] 代码已合并到 release 分支\n- [ ] CI 流水线全部通过\n- [ ] 预发环境验证完成\n- [ ] 发布公告已发送\n\n### 发布步骤\n1. 锁定发布窗口（晚上 22:00-01:00）\n2. 数据库迁移脚本先行\n3. 灰度发布：10% → 50% → 100%\n4. 监控关键指标：错误率、RT、QPS\n\n### 回滚预案\n发现异常立即回滚，保留两个版本镜像，回滚时间 < 5 分钟。', '线上发布 SOP', 1, 'c3d4e5f6g7h8i9j0k1l2m3n4o5p6q7r8s9t0u1v2w3x4y5z6a7b8c9d0e1f2g3h4', 456789123, 'PUBLISHED', 'INDEXED', 2, 'SOP');

-- 重置序列（确保后续插入 ID 从 4 开始）
SELECT setval('sys_knowledge_doc_id_seq', 3, true);

-- ---------------------------------------------------------------------
-- 2. 知识文档标签 (sys_knowledge_doc_tag)
-- ---------------------------------------------------------------------
INSERT INTO sys_knowledge_doc_tag (doc_id, tag) VALUES
(1, 'K8s'), (1, '故障排查'), (1, '容器'),
(2, 'MySQL'), (2, '性能优化'), (2, '慢查询'),
(3, '发布流程'), (3, 'SRE'), (3, '灰度发布');

-- ---------------------------------------------------------------------
-- 3. 工单 (sys_devops_ticket)
-- ---------------------------------------------------------------------
-- 优先级用四档 P0~P3（B0 起），SLA 展示串与 TicketEnums.Sla 派生结果一致
INSERT INTO sys_devops_ticket (id, title, priority, module, description, stack_trace, status, assignee, creator, category, sla, version) VALUES
('TKT-20260812-0001', '支付服务 Pod 频繁重启', 'P0', 'K8S', 'payment-service Pod 在生产环境持续 CrashLoopBackOff，已影响 30% 用户支付', 'OOMKilled: Container exceeded memory limit (512Mi → 1.2Gi)', 'PROCESSING', '张明', 'AI-Agent', 'K8s 故障', '15m 响应 / 4h 解决', 1),
('TKT-20260812-0002', '订单查询接口响应慢', 'P2', 'MYSQL', '订单列表查询接口 P99 延迟达 3.5s，用户投诉页面卡顿', 'Slow query: SELECT * FROM orders WHERE user_id=? LIMIT 10000,10', 'PENDING', '李华', 'devops-admin', '性能优化', '4h 响应 / 24h 解决', 0),
('TKT-20260811-0003', '灰度发布回滚', 'P3', 'OTHER', '新版本灰度至 50% 时错误率上升至 0.5%，已按预案回滚', NULL, 'RESOLVED', '王强', 'devops-admin', '发布', '24h 响应 / 72h 解决', 2);

-- ---------------------------------------------------------------------
-- 4. 工单标签 (sys_ticket_tag)
-- ---------------------------------------------------------------------
INSERT INTO sys_ticket_tag (ticket_id, tag) VALUES
('TKT-20260812-0001', 'K8s'), ('TKT-20260812-0001', '紧急'), ('TKT-20260812-0001', 'OOM'),
('TKT-20260812-0002', 'MySQL'), ('TKT-20260812-0002', '慢查询'), ('TKT-20260812-0002', '性能'),
('TKT-20260811-0003', '灰度发布'), ('TKT-20260811-0003', '回滚'), ('TKT-20260811-0003', '已解决');

-- ---------------------------------------------------------------------
-- 5. 工单回复 (sys_ticket_reply)
-- ---------------------------------------------------------------------
INSERT INTO sys_ticket_reply (ticket_id, role, author, author_color, content) VALUES
('TKT-20260812-0001', 'AI', 'OpsBrain AI', '#2563eb', '已检索知识库，建议按以下步骤排查：1) kubectl logs payment-service --previous 查看崩溃日志；2) describe pod 检查资源限制；3) 若 OOMKilled，调整内存配额至 1.5Gi。'),
('TKT-20260812-0001', 'USER', '张明', '#10b981', '日志确认是 OOMKilled，已将 memory.limit 从 512Mi 调整为 2Gi，正在观察。'),
('TKT-20260812-0002', 'USER', '李华', '#f59e0b', '慢查询已定位，正在添加 user_id 索引并改写分页逻辑。');

-- ---------------------------------------------------------------------
-- 6. 工单活动流 (sys_ticket_activity)
-- ---------------------------------------------------------------------
INSERT INTO sys_ticket_activity (ticket_id, color, text, detail, user_name, highlight) VALUES
('TKT-20260812-0001', 'blue', '工单已创建', '由 AI Agent 自动创建', 'AI-Agent', false),
('TKT-20260812-0001', 'orange', '负责人已分配', '分配给 张明', 'AI-Agent', true),
('TKT-20260812-0001', 'green', '状态变更', '待处理 → 处理中', '张明', false),
('TKT-20260812-0002', 'blue', '工单已创建', '由运维手动创建', 'devops-admin', false),
('TKT-20260812-0002', 'orange', '负责人已分配', '分配给 李华', 'devops-admin', true),
('TKT-20260811-0003', 'blue', '工单已创建', NULL, 'devops-admin', false),
('TKT-20260811-0003', 'green', '状态变更', '处理中 → 已解决', '王强', false),
('TKT-20260811-0003', 'purple', '工单已关闭', '回滚成功，服务恢复正常', '王强', true);

-- ---------------------------------------------------------------------
-- 7. Agent 调用日志 (sys_agent_call_log)
-- ---------------------------------------------------------------------
INSERT INTO sys_agent_call_log (trace_id, user_query, agent_answer, model_name, is_cached, latency_ms, cost_rmb, operation_type, affected_resources, operator_id) VALUES
('trace-20260812-001', '支付服务一直重启怎么办', '根据知识库【K8s故障排查手册.md】，Pod CrashLoopBackOff 的排查步骤如下...', 'deepseek-chat', false, 1850, 0.0023, 'CHAT', '["TKT-20260812-0001"]', 'user-123'),
('trace-20260812-002', '订单查询很慢', '已检索到【数据库慢查询优化.md】，建议添加索引并优化分页查询...', 'deepseek-chat', false, 1320, 0.0018, 'CHAT', '["TKT-20260812-0002"]', 'user-456'),
('trace-20260812-003', '支付服务一直重启怎么办', '根据知识库【K8s故障排查手册.md】，Pod CrashLoopBackOff 的排查步骤如下...', 'deepseek-chat', true, 120, 0.0000, 'CACHE_HIT', NULL, 'user-789');

-- ---------------------------------------------------------------------
-- 8. Agent 会话摘要 (sys_agent_session_summary)
-- ---------------------------------------------------------------------
INSERT INTO sys_agent_session_summary (session_id, trace_id, summary, key_facts, turn_count, total_tokens, total_cost_rmb, final_state, related_tickets) VALUES
('session-20260812-001', 'trace-20260812-001', '用户咨询支付服务 Pod 重启问题，AI 基于 K8s 故障排查手册给出排查步骤并自动创建工单 TKT-20260812-0001',
 '{"intent":"Pod 故障排查","confirmed_facts":["错误码:OOMKilled","资源配额:512Mi","版本:1.28","资源名:payment-service"],"tools_used":["searchDevOpsKnowledge","createDevOpsTicket"],"conclusion":"已创建工单并分配给张明","citations":["K8s故障排查手册.md"]}',
 2, 1850, 0.0023, 'COMPLETED', '["TKT-20260812-0001"]'),
('session-20260812-002', 'trace-20260812-002', '用户反馈订单查询慢，AI 检索慢查询优化文档并创建工单 TKT-20260812-0002',
 '{"intent":"性能优化","confirmed_facts":["慢查询:SELECT * FROM orders","延迟:P99 3.5s"],"tools_used":["searchDevOpsKnowledge","createDevOpsTicket"],"conclusion":"已创建工单并分配给李华","citations":["数据库慢查询优化.md"]}',
 1, 1320, 0.0018, 'COMPLETED', '["TKT-20260812-0002"]');

-- ---------------------------------------------------------------------
-- 9. 工具执行记录 (sys_agent_tool_execution)
-- ---------------------------------------------------------------------
INSERT INTO sys_agent_tool_execution (trace_id, session_id, saga_id, step_seq, tool_name, risk_level, tool_args, tool_result, state, compensable, compensation_action, business_key, duration_ms) VALUES
('trace-20260812-001', 'session-20260812-001', 'trace-20260812-001', 1, 'searchDevOpsKnowledge', 'READ_ONLY', '{"query":"Pod CrashLoopBackOff 排查"}', '检索到 3 个匹配段落，来源：K8s故障排查手册.md', 'SUCCESS', false, NULL, NULL, 850),
('trace-20260812-001', 'session-20260812-001', 'trace-20260812-001', 2, 'createDevOpsTicket', 'WRITE', '{"title":"支付服务 Pod 频繁重启","priority":"HIGH","module":"K8S"}', '工单创建成功', 'SUCCESS', true, 'voidDevOpsTicket', 'TKT-20260812-0001', 420),
('trace-20260812-002', 'session-20260812-002', 'trace-20260812-002', 1, 'searchDevOpsKnowledge', 'READ_ONLY', '{"query":"MySQL 慢查询优化"}', '检索到 2 个匹配段落，来源：数据库慢查询优化.md', 'SUCCESS', false, NULL, NULL, 680),
('trace-20260812-002', 'session-20260812-002', 'trace-20260812-002', 2, 'createDevOpsTicket', 'WRITE', '{"title":"订单查询接口响应慢","priority":"MEDIUM","module":"MYSQL"}', '工单创建成功', 'SUCCESS', true, 'voidDevOpsTicket', 'TKT-20260812-0002', 390);

-- =====================================================================
-- 数据完整性验证
-- =====================================================================
DO $$
DECLARE
    doc_count INTEGER;
    ticket_count INTEGER;
    log_count INTEGER;
BEGIN
    SELECT COUNT(*) INTO doc_count FROM sys_knowledge_doc;
    SELECT COUNT(*) INTO ticket_count FROM sys_devops_ticket;
    SELECT COUNT(*) INTO log_count FROM sys_agent_call_log;

    RAISE NOTICE '=== Mock 数据导入完成 ===';
    RAISE NOTICE '知识文档: % 篇', doc_count;
    RAISE NOTICE '工单: % 个', ticket_count;
    RAISE NOTICE 'Agent 调用日志: % 条', log_count;

    IF doc_count >= 3 AND ticket_count >= 3 AND log_count >= 3 THEN
        RAISE NOTICE '✓ 数据完整性验证通过';
    ELSE
        RAISE WARNING '⚠ 部分数据导入失败，请检查日志';
    END IF;
END $$;
