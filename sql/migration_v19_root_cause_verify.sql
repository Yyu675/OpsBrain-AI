-- =====================================================================
-- v19: 根因分析 + 修复验证字段（B3：闭环阶段 5+6）
-- =====================================================================
-- 背景：PRD §2.1 阶段 5 要求回答"为什么会发生"而非"怎么恢复的"，
-- 阶段 6 要求验证确认而非人工直接点"已解决"。
-- 此前工单"已解决"是人工点的，无验证人/验证方式/验证结论/观察期，
-- MTTR 无法准确计算（D3 决策：必填但允许带理由跳过）。
--
-- 依赖：v16(deadline) + v17(first_response) + v18(handling_stage)。
-- 幂等性：全部 ADD COLUMN IF NOT EXISTS。
-- =====================================================================

ALTER TABLE sys_devops_ticket
    -- 阶段 5 根因分析
    ADD COLUMN IF NOT EXISTS root_cause           TEXT,          -- 人工确认的根因（≠AI 建议）
    ADD COLUMN IF NOT EXISTS root_cause_category  VARCHAR(32),   -- 根因分类（CONFIG/CAPACITY/CODE/DEPENDENCY/NETWORK/DATA/HUMAN/EXTERNAL/UNKNOWN）
    ADD COLUMN IF NOT EXISTS root_cause_by        VARCHAR(64),
    ADD COLUMN IF NOT EXISTS root_cause_at        TIMESTAMP,
    -- 阶段 6 修复验证（D3：必填但允许带理由跳过）
    ADD COLUMN IF NOT EXISTS verified_at          TIMESTAMP,     -- 验证通过时刻（MTTR 终点）
    ADD COLUMN IF NOT EXISTS verifier             VARCHAR(64),
    ADD COLUMN IF NOT EXISTS verify_method        VARCHAR(32),   -- MONITOR/LOG/BUSINESS/MANUAL
    ADD COLUMN IF NOT EXISTS verify_conclusion    TEXT,
    ADD COLUMN IF NOT EXISTS verify_skipped       BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS verify_skip_reason   VARCHAR(255);  -- 跳过时强制填写
