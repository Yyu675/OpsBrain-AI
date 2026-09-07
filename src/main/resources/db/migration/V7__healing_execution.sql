-- =====================================================================
-- S3-1 批次 2：自愈执行台账（PRD §九 L4 受控自愈）
-- =====================================================================
-- 设计依据（防漂移）：
-- * 一次执行一行：演算、审批、执行、撤销（V8 起追加 UNDO 状态流转）
--   的全过程字段都在同一行——「事后审计」是 L4 的硬指标，审计查询
--   不接受跨表拼图。
-- * approval_id 关联 sys_approval_request（可空）：AUTO_EXECUTE 与
--   REJECTED 两类裁决不产生审批单，审计时靠 gate_decision 区分。
-- * pre_snapshot_json / undo_token 为回滚触发器（批次 3）预留载体，
--   现在就用、现在就落库——快照不在执行当下采集，事后无从补拍。
-- * executor_key 落行：审计必须能回答「当时是哪只手做的」，
--   不能只记「想做什么」。
-- =====================================================================
CREATE TABLE IF NOT EXISTS sys_healing_execution (
    id                BIGSERIAL PRIMARY KEY,
    action_key        VARCHAR(128) NOT NULL,              -- 与白名单 actionKey 严格对齐
    environment       VARCHAR(64)  NOT NULL,
    target            VARCHAR(512),
    params_json       TEXT,
    alert_id          BIGINT,                             -- 触发告警（可空：手工触发）
    requested_by      VARCHAR(64),
    gate_decision     VARCHAR(32)  NOT NULL,              -- AUTO_EXECUTE / REQUIRES_APPROVAL / DENIED / NO_EXECUTOR
    approval_id       BIGINT,                             -- sys_approval_request.id（可空）
    executor_key      VARCHAR(64),                        -- 承接执行器（拒绝类裁决为 NULL）
    status            VARCHAR(32)  NOT NULL,              -- PENDING_APPROVAL / SUCCEEDED / FAILED / REJECTED / UNDONE
    dry_run_plan      TEXT,                               -- 演算计划（审批单展示「将要发生什么」）
    output            TEXT,
    error             TEXT,
    pre_snapshot_json TEXT,                               -- 执行前快照（Snapshot_Before_Healing）
    undo_token        VARCHAR(128),                       -- 撤销凭据（NULL = 不可撤销）
    created_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at       TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_healing_execution_alert  ON sys_healing_execution(alert_id);
CREATE INDEX IF NOT EXISTS idx_healing_execution_status ON sys_healing_execution(status);
