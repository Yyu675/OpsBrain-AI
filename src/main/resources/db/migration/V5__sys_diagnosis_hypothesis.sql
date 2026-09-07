-- =====================================================================
-- S2-2 根因假设：sys_diagnosis_hypothesis（路线图 §6.2 2-2.2）
-- =====================================================================
-- 设计依据（防漂移）：
-- * session_trace_id 关联诊断会话（轨迹的唯一索引都在 sys_diagnosis_session）；
--   trace_id 链路: 告警 → 工单 → 会话 → 假设 → 证据——链路不可断。
-- * evidence_ids / contradict_ids 用 JSON 数组存（id 列表，点开假设看证据
--   的关联键）；不拆关系表：假设-证据关联不需要双向查询，且关系表成本大于收益。
-- * confidence 存 DOUBLE PRECISION（模型给出 + 置信度引擎钳制后的最终值）。
-- =====================================================================
CREATE TABLE IF NOT EXISTS sys_diagnosis_hypothesis (
    id              BIGSERIAL PRIMARY KEY,
    session_trace_id VARCHAR(64) NOT NULL,
    rank            INTEGER NOT NULL,
    statement       VARCHAR(512) NOT NULL,
    reasoning       TEXT,
    confidence      DOUBLE PRECISION NOT NULL,
    evidence_ids    TEXT,                        -- JSON 数组
    contradict_ids  TEXT,                        -- JSON 数组
    suggested_action VARCHAR(2000),
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_hypothesis_session ON sys_diagnosis_hypothesis (session_trace_id);
