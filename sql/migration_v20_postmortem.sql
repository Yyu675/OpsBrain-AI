-- =====================================================================
-- v20: 复盘归档表（B4：闭环阶段 7）
-- =====================================================================
-- 背景：PRD §2.1 阶段 7 指出"最容易被忽视……相同故障在不同团队反复发生"。
-- 改进项若只是复盘文档里的一段文字，就无法查询「所有逾期未完成的改进项」
-- ——不可查询 = 不会被跟踪 = 等于没写。故改进项独立成表。
--
-- 依赖：v16~v19（闭环前置字段）。
-- 幂等性：CREATE TABLE IF NOT EXISTS。
-- =====================================================================

-- ---------------------------------------------------------------------
-- 复盘正文表
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sys_ticket_postmortem (
    id              BIGSERIAL PRIMARY KEY,
    ticket_id       VARCHAR(64)  NOT NULL UNIQUE,   -- 一张工单一份复盘
    timeline        TEXT,                            -- 时间线（可由 action+reply 自动生成草稿）
    impact_scope    VARCHAR(255),                    -- 影响范围
    impact_duration INT,                             -- 影响时长（分钟）
    lessons         TEXT,                            -- 经验教训
    doc_id          BIGINT,                          -- 已转知识库文档 ID（来源回链）
    author          VARCHAR(64),
    create_time     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ---------------------------------------------------------------------
-- 改进项独立成表：一次复盘可有多条改进，各有责任人与截止日，需独立跟踪
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sys_postmortem_action_item (
    id             BIGSERIAL PRIMARY KEY,
    postmortem_id  BIGINT       NOT NULL,
    ticket_id      VARCHAR(64)  NOT NULL,            -- 冗余便于按工单直查
    content        VARCHAR(500) NOT NULL,
    owner          VARCHAR(64),
    due_date       DATE,
    status         VARCHAR(16) NOT NULL DEFAULT 'OPEN',  -- OPEN/DOING/DONE/DROPPED
    create_time    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_pm_action_postmortem
    ON sys_postmortem_action_item (postmortem_id);
CREATE INDEX IF NOT EXISTS idx_pm_action_ticket
    ON sys_postmortem_action_item (ticket_id);
-- 逾期改进项查询：WHERE status IN ('OPEN','DOING') AND due_date < CURRENT_DATE
CREATE INDEX IF NOT EXISTS idx_pm_action_status_due
    ON sys_postmortem_action_item (status, due_date) WHERE status IN ('OPEN', 'DOING');
