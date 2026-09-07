-- =====================================================================
-- S2-3 反馈回流：假设反馈列 + 知识加权表（路线图 §6.3 2-3.5/2-3.6）
-- =====================================================================
-- 设计依据（防漂移）：
-- * 假设反馈直接落在假设行（单值：最后一次反馈为准）——反馈的语义是
--   「这条假设靠不靠谱」，数据量小，不值得独立表。
-- * 知识加权用独立 boost 表（不污染 sys_knowledge_chunk 本体）：
--   反馈回流必须可挂零权重容错（helpfulCount 归零黑洞）、可全沉默
--   避免跨表库固定期清洗时损害数据本体。
-- * boost 公式只在代码里（KnowledgeBoostRepository），Schema 只泛放量：
--   helpful_count / wrong_count 两个计数器——公式可口，数据免疫。
-- =====================================================================
ALTER TABLE sys_diagnosis_hypothesis
    ADD COLUMN IF NOT EXISTS feedback VARCHAR(16);      -- HELPFUL / PARTIAL / WRONG
ALTER TABLE sys_diagnosis_hypothesis
    ADD COLUMN IF NOT EXISTS feedback_at TIMESTAMP;     -- 最近的码反馈时间底座

CREATE TABLE IF NOT EXISTS sys_knowledge_boost (
    chunk_id       BIGINT PRIMARY KEY,                  -- sys_knowledge_chunk.id 关联键
    helpful_count  INTEGER NOT NULL DEFAULT 0,
    wrong_count    INTEGER NOT NULL DEFAULT 0,
    updated_at     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
