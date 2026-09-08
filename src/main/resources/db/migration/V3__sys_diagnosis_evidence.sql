-- =====================================================================
-- S1-5 证据落库：sys_diagnosis_evidence（路线图 §5.6 1-5.2）
-- =====================================================================
-- 设计依据（防漂移）：
-- * 「每条证据可回放」= 按 trace_id 拉全链——诊断一次诊断会话的全部取证
--   记录按 trace_id 聚簇；trace_id 由调用链（诊断流程/告警入口）下发，
--   纯评测/演示场景允许为空（NULL 互不冲突）。
-- * content 存工具层 Evidence 载荷原文（TEXT）：回放要的是「当时长什么样」，
--   不做的归一化=不做隐性信息有损转换。
-- * relevance_score 可空：metrics 方向天然无此字段，NULL 与 0 语义不同。
-- * 与前两张表的列级惯例一致（BIGSERIAL/VARCHAR/TIMESTAMP），不起 TZ 端。
-- =====================================================================
CREATE TABLE IF NOT EXISTS sys_diagnosis_evidence (
    id              BIGSERIAL PRIMARY KEY,
    trace_id        VARCHAR(64),                    -- 诊断链路 ID（回放键；可空=孤证）
    agent_name      VARCHAR(64) NOT NULL,           -- 取证发起方（diagnosis-engine / eval / manual …）
    evidence_type   VARCHAR(32) NOT NULL,           -- metrics / changes / logs / topology
    status          VARCHAR(16) NOT NULL,           -- SUCCESS / NO_DATA / FAILED / UNAVAILABLE
    title           VARCHAR(512) NOT NULL,
    content         TEXT,                           -- Evidence.toToolPayload() 原文
    source_ref      VARCHAR(2000),                  -- PromQL / LogQL / 表谓词（可下钻）
    relevance_score DOUBLE PRECISION,
    collected_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_evidence_trace ON sys_diagnosis_evidence (trace_id);
CREATE INDEX IF NOT EXISTS idx_evidence_type_time ON sys_diagnosis_evidence (evidence_type, collected_at);
CREATE INDEX IF NOT EXISTS idx_evidence_status ON sys_diagnosis_evidence (status);
