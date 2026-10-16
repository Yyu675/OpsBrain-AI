-- V15：sys_ticket_activity.detail VARCHAR(512) → TEXT
--
-- 背景（2026-09-30 方案 B 业务实证发现）：诊断取证明细（逐方向证据状态 +
-- 日志 Top 模式 + 假设 + traceId 回放）写入工单活动流，SUFFICIENT/WEAK
-- 版本普遍超过 512 字符，INSERT 报 `value too long for type
-- varchar(512)` 后被 TicketActivityRepository 的护身 catch 吞成 WARN——
-- 工单时间线静默缺行，正是本方案要消灭的「AI 查了但看不见」形态。
--
-- detail 语义本就是「活动详情」长文本位（回复留痕 / 聚合关联 / 诊断明细），
-- 512 是建表时按旧的一行式文案估的；诊断明细进链后 TEXT 才是对的容量。
-- PG 的 varchar→text 展宽是元数据级操作，不重写表、瞬时完成。
--
-- 历史行不回填：失败的那几条 INSERT 已被吞掉（WARN 留痕在后端日志），
-- 属一次性信息损失，重建需要重放诊断——不值得（诊断会话/证据/分析区
-- 都有完整副本，活动流只是第四展示面）。

ALTER TABLE sys_ticket_activity ALTER COLUMN detail TYPE TEXT;
