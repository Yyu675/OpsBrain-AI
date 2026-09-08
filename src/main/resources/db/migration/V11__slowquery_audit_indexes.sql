-- ============================================================================
-- V11: 慢查询静态审计首批索引(Batch 42 / 报告 145)
--
-- 审计方法:EXPLAIN 环境缺位下改做静态对账——全量解析 V1..V10 索引面 ×
-- JdbcTemplate/MyBatis 热点查询的 WHERE/ORDER BY 列集,交集缺口逐项定级。
--
-- 本枚两枚候选,均为「每次对应请求必跑且当前无对口索引」的确证级:
--
--   1) idx_alert_group_dedup —— 风暴聚合查询(AlertRepository.findActiveGroupTicket)
--      dedup 未命中时的退路查询,风暴期间每条告警都跑;过滤集
--      (service, module) + status 活跃 + ticket_id 非空 + last_occurred_at 窗口,
--      现有 idx_alert_service 是 (service, first_occurred_at) 不对口。
--      用部分索引把「活跃且已建单」的子集钉住——风暴下该子集小且稳定。
--
--   2) idx_ticket_create_time —— 看板 KPI 今日新增 + N 天趋势
--      (DevOpsTicketRepository.countCreatedToday/countCreatedByDay),
--      每次首页加载必扫;现有索引无 create_time 对口列。
--
-- 观察档(本次不动,理由见报告 145 §三):
--   · 工单 keyword 三列 LOWER LIKE '%kw%' —— 前导通配无索引可救,正解是
--     pg_trgm GIN;属运行时扩展决策,真窗演练(S5-4.2 演练项)里评。
--   · countUrgentPending (priority,status) 双列 —— BitmapAnd 已可服务。
--
-- 不用 CONCURRENTLY 的原因:Flyway 在事务内跑迁移,CONCURRENTLY 禁事务;
-- 现阶段表规模小,B-tree 建索引锁窗可忽略——锁窗随规模增长,演练项含
-- 「V11 后续索引一律走 CONCURRENTLY 双段迁移」的纪律。
-- ============================================================================

CREATE INDEX IF NOT EXISTS idx_alert_group_dedup
    ON sys_alert (service, module, last_occurred_at DESC)
    WHERE status IN ('FIRING', 'ACKNOWLEDGED') AND ticket_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_ticket_create_time
    ON sys_devops_ticket (create_time DESC);
