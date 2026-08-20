-- =====================================================================
-- v15: sys_ticket_ai_analysis - 工单 AI 分析独立表（策略 B）
-- =====================================================================
-- 背景：策略 A（6.39）把 AI 分析存进 sys_ticket_reply（role='ai'），
-- 结构化字段（原因/命令/置信度/引用）被压成纯文本，且无法：
--   ① 保留多次分析的版本（重新分析会追加一条纯文本，无法对比历史结论）
--   ② 结构化统计（置信度分布、命令可复制性）
--   ③ 记录 AI 准确率（用户是否认可这次分析）
-- 策略 B 用独立表保留结构化字段 + 多版本 + 用户反馈。
--
-- 兼容：策略 A 已写入的 role='ai' 回复不迁移（历史数据保留可查）；
--       新分析走本表。前端优先读本表，读不到再回退 role='ai' 回复。
--
-- 幂等性：CREATE TABLE IF NOT EXISTS，可重复执行。
-- =====================================================================

CREATE TABLE IF NOT EXISTS sys_ticket_ai_analysis (
    id           BIGSERIAL PRIMARY KEY,
    ticket_id    VARCHAR(64)  NOT NULL,                 -- 所属工单（与 sys_devops_ticket.id 对应）
    version      INT          NOT NULL DEFAULT 1,       -- 第几次分析（同工单递增，最新为当前结论）
    content      TEXT         NOT NULL,                 -- 原始 markdown 全文（渲染与二次解析的真相源）
    -- 结构化字段（从 content 解析后落列，供统计与结构化渲染，避免每次重解析）
    reasons      JSONB,                                 -- 可能原因数组 ["大事务未提交", "从库IO瓶颈"]
    commands     JSONB,                                 -- 排查命令数组 ["SHOW SLAVE STATUS\\G"]
    citations    JSONB,                                 -- 引用来源数组 ["MySQL主从延迟排查 - 常见原因"]
    confidence   INT,                                   -- 置信度 0-100，NULL=模型未给出
    cost_rmb     NUMERIC(10,4) DEFAULT 0,               -- 本次分析成本（策略 A 丢失，本表保留）
    -- 用户反馈（AI 准确率统计的数据来源）
    feedback     VARCHAR(16),                           -- NULL=未评价 / HELPFUL=有用 / UNHELPFUL=没用
    feedback_at  TIMESTAMP,                             -- 评价时间
    create_time  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 按工单查最新分析（version DESC 取当前结论）
CREATE INDEX IF NOT EXISTS idx_ai_analysis_ticket
    ON sys_ticket_ai_analysis (ticket_id, version DESC);

-- 准确率统计（按 feedback 聚合）
CREATE INDEX IF NOT EXISTS idx_ai_analysis_feedback
    ON sys_ticket_ai_analysis (feedback) WHERE feedback IS NOT NULL;
