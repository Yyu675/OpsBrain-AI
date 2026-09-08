-- V10 S3-5 可观测/可回放：执行步骤时间线（§3-5.1）
-- steps_json：编排器在 GATE_EVALUATE / IDEMPOTENCY_CHECK / DRY_RUN / EXECUTE /
-- SUBMIT_APPROVAL / VERIFY / AUTO_UNDO / ESCALATE_TICKET / MANUAL_UNDO 各节点
-- 产生的步骤序列（[{name,status,detail,at}]），详情 API 据此回放全过程。
-- 用 TEXT 与既有 *_json 列同基调（当前无 JSONB 特型强需求）。
ALTER TABLE sys_healing_execution
    ADD COLUMN IF NOT EXISTS steps_json TEXT;

COMMENT ON COLUMN sys_healing_execution.steps_json IS
    'S3-5 步骤时间线 JSON 数组：节点名/状态/摘要/时间戳，供详情页回放（§3-5.1）';
