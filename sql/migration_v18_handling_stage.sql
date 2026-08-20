-- =====================================================================
-- v18: 现场处置字段 + 处置动作记录表（B2：闭环阶段 4）
-- =====================================================================
-- 背景：PRD §2.1 阶段 4 是最核心最耗时的阶段，此前只有回复和单一 PROCESSING
-- 状态，无法区分排查中/已止损/修复中/验证中，也无处置动作留痕。
--
-- 关键设计：effective 允许为 false——失败尝试同样有价值（PRD §2.1 排查占
-- 40% 且严重依赖经验。「我试过重启，没用」避免后人重走弯路。
-- 只记成功动作等于丢弃大部分知识。
--
-- 依赖：v16(deadline) + v17(first_response)。
-- 幂等性：全部 IF NOT EXISTS。
-- =====================================================================

-- ---------------------------------------------------------------------
-- Step 1: 工单表加处置阶段字段
-- ---------------------------------------------------------------------
-- handling_stage 仅在 status=PROCESSING 时有意义；
-- 转出 PROCESSING 时由 Service 清空（否则状态与阶段自相矛盾）
ALTER TABLE sys_devops_ticket
    ADD COLUMN IF NOT EXISTS handling_stage VARCHAR(24),   -- TRIAGE/MITIGATED/FIXING/VERIFYING
    ADD COLUMN IF NOT EXISTS mitigated_at   TIMESTAMP;      -- 止损完成时刻（业务恢复）

-- ---------------------------------------------------------------------
-- Step 2: 处置动作记录表
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sys_ticket_action (
    id           BIGSERIAL PRIMARY KEY,
    ticket_id    VARCHAR(64)  NOT NULL,
    -- MITIGATE止损/INVESTIGATE排查/FIX修复/ROLLBACK回滚/VERIFY验证
    action_type  VARCHAR(24)  NOT NULL,
    summary      VARCHAR(255) NOT NULL,   -- 一句话：做了什么
    detail       TEXT,                   -- 命令/配置/日志片段
    operator     VARCHAR(64)  NOT NULL,
    effective    BOOLEAN,                -- 是否有效（NULL=未判定）——失败尝试同样记录
    started_at   TIMESTAMP,
    finished_at  TIMESTAMP,
    create_time  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_ticket_action_ticket
    ON sys_ticket_action (ticket_id, create_time);
