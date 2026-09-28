-- V10: 效能快照表补「派生事件数」列（建议4 / Incident 方案 C 趋势面的数据源）
--
-- 背景（2026-09-28）：告警压缩比三档口径（告警 : 事件 : 工单）已落地
-- （AlertRepository.countDerivedIncidents 的派生归并），但效能大盘的
-- 趋势图只有「告警累计」没有「事件累计」——想看清压缩漏斗的水位变化，
-- 快照必须按天把派生事件数也存下来。
--
-- 事件数口径：同 system + service + 10 分钟窗归并为同一「事件」，
-- 与 countDerivedIncidents 完全一致，累计口径（截至当天）。
ALTER TABLE sys_effectiveness_snapshot
    ADD COLUMN events_total BIGINT NOT NULL DEFAULT 0;

COMMENT ON COLUMN sys_effectiveness_snapshot.events_total IS
    '派生事件数（同 system+service+10分钟窗归并，累计口径，2026-09-28）';