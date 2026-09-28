-- V11: sys_alert 保存告警原始 labels / annotations（2026-09-28）
--
-- 背景：此前落库只存「蒸馏」字段（alert_name/title/description），webhook
-- 负载里的完整 labels（instance/pod/namespace/job…）与 annotations
-- （runbook_url/当前值/阈值…）在 processSignal 后即被丢弃。诊断链取证
-- 只拿到告警名+描述，丢失了最有诊断价值的下钻维度（哪个实例、哪个 Pod、
-- 超了多少阈值），日志取证的 keyword 也无从谈起。
--
-- 存量行为空 JSON（'{}'），不回填——历史行只影响旧告警详情展示，
-- 不影响任何既有查询/去重/聚合路径（新列不参与任何键）。

ALTER TABLE sys_alert ADD COLUMN IF NOT EXISTS labels_json JSONB NOT NULL DEFAULT '{}'::jsonb;
ALTER TABLE sys_alert ADD COLUMN IF NOT EXISTS annotations_json JSONB NOT NULL DEFAULT '{}'::jsonb;

COMMENT ON COLUMN sys_alert.labels_json IS 'webhook 原始 labels（instance/pod/namespace/job 等），诊断下钻与日志取证 keyword 的数据源';
COMMENT ON COLUMN sys_alert.annotations_json IS 'webhook 原始 annotations（runbook_url/当前值/阈值等），告警详情完整展示';
