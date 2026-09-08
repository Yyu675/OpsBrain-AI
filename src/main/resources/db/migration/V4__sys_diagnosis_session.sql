-- =====================================================================
-- S2-1 诊断会话：sys_diagnosis_session（路线图 §6.1 2-1.3）
-- =====================================================================
-- 设计依据（防漂移）：
-- * 一条告警 <=> 一条诊断会话（唯一索引 alert_id）：去重语义与告警链路
--   一致——同一活跃告警的重复触发不应产生第二条诊断（§6.1 验收第 4 条）。
-- * trace_id 是证据回放键：本会话触发的全部 sys_diagnosis_evidence 行
--   都带同一 trace_id（V3 已有索引）。
-- * status 与 sufficiency 分离：status 是流程态（RUNNING/COMPLETED/
--   REJECTED/ERROR），sufficiency 是证据判据（SUFFICIENT/WEAK/INSUFFICIENT）。
--   「诊断跑完了但证据不足」≠「诊断失败了」——混用会把排障人带偏。
-- * 不设结果正文列：推理摘要落 summary；完整叙事在图前端回放证据+状态机。
-- =====================================================================
CREATE TABLE IF NOT EXISTS sys_diagnosis_session (
    id              BIGSERIAL PRIMARY KEY,
    trace_id        VARCHAR(64) NOT NULL,
    alert_id        BIGINT NOT NULL,                -- 关联 sys_alert.id（去重唯一约束在此列）
    ticket_id       VARCHAR(64),                    -- 建单成功后回填（诊断先于建单异步/失败时允许空）
    service         VARCHAR(128) NOT NULL,
    status          VARCHAR(16) NOT NULL,           -- RUNNING / COMPLETED / REJECTED / ERROR
    sufficiency     VARCHAR(16),                    -- SUFFICIENT / WEAK / INSUFFICIENT（RUNNING 时为 NULL）
    summary         TEXT,
    error_message   VARCHAR(1024),
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
-- 同告警仅一条「进行中」诊断。部分唯一索引（仅 RUNNING）：
-- 全列唯一会让一条 REJECTED 行永久锁死该告警的诊断（历史教训级 bug 形态）。
CREATE UNIQUE INDEX IF NOT EXISTS idx_dsession_alert_running
    ON sys_diagnosis_session (alert_id) WHERE status = 'RUNNING';
CREATE INDEX IF NOT EXISTS idx_dsession_trace ON sys_diagnosis_session (trace_id);
CREATE INDEX IF NOT EXISTS idx_dsession_status_time ON sys_diagnosis_session (status, created_at);
