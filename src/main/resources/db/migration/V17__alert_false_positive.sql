-- V17：告警误报标记（A1 Feedback 回流第一步，2026-10-12）
-- 背景：告警质量此前无度量——阈值调宽调窄全凭感觉。误报标记是「反馈层」
-- 的最小可用件：值班人一键标误报，周报按周出误报率反推规则阈值。
-- 设计约束：
--   1) 独立布尔列，不复用 status——状态机（FIRING/ACKNOWLEDGED/RESOLVED）
--      被守卫、部分唯一索引与契约测试钉死，「误报」是质量标注不是生命周期状态，
--      一条误报的告警同样需要被确认（表示"我看过了，它是误报"）。
--   2) 带标记时刻：周报按 marked_at 取窗口，与告警到达时间解耦
--      （上周到的告警这周被复盘标误报，应计入这周的反馈）。
--   3) upsert 冲突更新不含此列——重复推送不得抹掉人工已做的误报判定。

ALTER TABLE sys_alert ADD COLUMN IF NOT EXISTS false_positive BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE sys_alert ADD COLUMN IF NOT EXISTS false_positive_marked_at TIMESTAMP NULL;

COMMENT ON COLUMN sys_alert.false_positive IS '人工误报标记（质量标注，非生命周期状态；默认 FALSE）';
COMMENT ON COLUMN sys_alert.false_positive_marked_at IS '最近一次标为误报的时刻（取消标记不清空，保留审计痕迹）';

-- 周报按标记时刻取窗口，部分索引只覆盖已标记行（绝大多数行为 FALSE，全表索引浪费）
CREATE INDEX IF NOT EXISTS idx_alert_false_positive
    ON sys_alert (false_positive_marked_at) WHERE false_positive;
