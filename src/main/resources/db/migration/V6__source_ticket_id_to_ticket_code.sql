-- V6: source_ticket_id BIGINT → VARCHAR(64)
--
-- 背景（2026-09-24）：工单主键是字符串流水号（TKT-yyyyMMdd-序号，见
-- TicketService.generateTicketId），而本列自建立起是 BIGINT——
-- 前端沉淀抽屉 Number('TKT-20260924-0001') 得到 NaN，JSON 序列化为 null，
-- 「已沉淀为知识」来源回链在真实工单号下从未写入过一行（测试夹具用纯数字
-- 工单号掩盖了这一点）。本迁移把列改成字符串工单号，全链路
-- （实体/DTO/仓储/前端类型）同步 Long → String。
--
-- USING source_ticket_id::text：存量行全部为 NULL（NaN→null 的历史），
-- 转换零风险；BIGINT→VARCHAR 的索引重建由 PG 自动完成
-- （idx_doc_source_ticket 照常存在）。
ALTER TABLE sys_knowledge_doc
    ALTER COLUMN source_ticket_id TYPE VARCHAR(64) USING source_ticket_id::text;

COMMENT ON COLUMN sys_knowledge_doc.source_ticket_id IS
    '源工单字符串流水号（TKT-yyyyMMdd-序号），非工单沉淀时为 NULL；2026-09-24 自 BIGINT 迁移（原类型存不进 TKT 号，回链恒空）';
