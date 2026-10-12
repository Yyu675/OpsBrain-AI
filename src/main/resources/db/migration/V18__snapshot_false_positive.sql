-- V18：效能快照补「误报」列（批91，A1 Feedback 回流闭环进周报）
-- 背景：V17 落了 sys_alert.false_positive 标注，但周报的口径纪律是
-- 「sys_effectiveness_snapshot 是唯一事实」——周报直接从 sys_alert 现算
-- 会造出第二套口径（时间窗/过滤条件漂移无人发现）。所以误报计数走快照：
-- 快照器每天记累计值，周报只用快照差。
-- 口径说明：误报率 = alerts_false_positive / alerts_total（累计对累计）。
-- 「本周期新增误报」= 两期快照差；不做「本周到达里的误报占比」——那条
-- 分母要靠 alerts_total 差分、分子却是标记时刻任意，同窗语义不成立。

ALTER TABLE sys_effectiveness_snapshot
    ADD COLUMN IF NOT EXISTS alerts_false_positive BIGINT NOT NULL DEFAULT 0;

COMMENT ON COLUMN sys_effectiveness_snapshot.alerts_false_positive IS
    '截至快照日累计被标误报的告警数（累计口径；周报误报率=本列/alerts_total）';
