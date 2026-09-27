-- V9: sys_alert 增加 system 列（PRD FR-1.2，2026-09-27）
--
-- 背景：接 MES/ERP/WMS/QMS 多套系统后，「这条告警是哪个系统的」不能依赖
-- payload 自带的 label（可伪造）。新增的 /api/v1/alerts/webhook/{system}
-- 端点按接入路径注入 system——来源由部署侧的 webhook URL 保证。
--
-- 存量行回填为 'default'（单一 Prometheus 接入期的历史数据）。
-- system 参与去重键（经 labels 注入，AlertService.computeDedupKey 包含 labels），
-- 同名告警从不同系统接入不再互相计次——这正是区分来源的意义。

ALTER TABLE sys_alert ADD COLUMN IF NOT EXISTS system VARCHAR(64) NOT NULL DEFAULT 'default';

COMMENT ON COLUMN sys_alert.system IS '来源系统标识（mes/erp/wms/qms…）。由 /webhook/{system} 路径注入（不可伪造），旧端点回落 payload 的 system label，均无则 default';

-- 将来按系统筛选/ACL 过滤是大概率事件（FR-6.6），先把索引放上：
-- 列表按时间倒序扫时带系统过滤是值班 owner 的核心视角
CREATE INDEX IF NOT EXISTS idx_alert_system ON sys_alert (system, last_occurred_at DESC);
