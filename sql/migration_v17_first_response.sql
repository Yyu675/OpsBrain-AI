-- =====================================================================
-- v17: 首响/派单字段（B1：闭环阶段 3）
-- =====================================================================
-- 背景：PRD §2.3 要求分级首响 SLA（P0 15min / P1 30min / P2 4h / P3 24h）
-- 与超时自动升级，但工单侧此前**完全没有首响概念**——
-- 只有 assignee（派给谁），没有「谁在何时首次响应」。
--
-- 后果：MTTA（首响耗时）无法计算；首响超时无从发现；
-- 「已派单但无人理」与「已在处理」在数据上不可区分。
--
-- 注：告警侧已有 ACKNOWLEDGED 状态 + acknowledged_at 字段（语义即首响确认），
-- 本迁移让工单侧与之对齐，共用同一套「确认」语义而非各造一套。
--
-- 依赖：v16 已建 response_deadline（首响截止基线）。
-- 幂等性：全部 ADD COLUMN IF NOT EXISTS。
-- =====================================================================

ALTER TABLE sys_devops_ticket
    -- 首响
    ADD COLUMN IF NOT EXISTS first_response_at TIMESTAMP,          -- 首次响应时刻（NULL=尚未首响）
    ADD COLUMN IF NOT EXISTS first_responder   VARCHAR(64),        -- 首响人
    ADD COLUMN IF NOT EXISTS response_breached BOOLEAN NOT NULL DEFAULT FALSE,  -- 首响是否已超时
    -- 升级
    ADD COLUMN IF NOT EXISTS escalated_at      TIMESTAMP,          -- 升级时刻
    ADD COLUMN IF NOT EXISTS escalate_reason   VARCHAR(255);       -- 升级原因

-- ---------------------------------------------------------------------
-- 首响超时扫描索引
-- ---------------------------------------------------------------------
-- 定时任务查询：WHERE first_response_at IS NULL
--                 AND response_breached = FALSE
--                 AND response_deadline < NOW()
--                 AND status NOT IN ('RESOLVED','CLOSED','VOID')
-- 部分索引只覆盖「未首响」的行——已首响的工单永远不需要再扫，
-- 全表索引会随历史数据线性膨胀而扫描成本不降。
CREATE INDEX IF NOT EXISTS idx_ticket_pending_response
    ON sys_devops_ticket (response_deadline)
 WHERE first_response_at IS NULL;

-- 升级清单查询
CREATE INDEX IF NOT EXISTS idx_ticket_escalated
    ON sys_devops_ticket (escalated_at)
 WHERE escalated_at IS NOT NULL;

-- ---------------------------------------------------------------------
-- 存量数据处理：不回填 first_response_at
-- ---------------------------------------------------------------------
-- 为何不回填：历史工单没有可信的首响时刻。
--   ① 用 create_time 回填 → 所有历史工单显示「0 分钟首响」，MTTA 被虚假拉低；
--   ② 用 update_time 回填 → 把最后一次任意修改当作首响，同样是编造。
-- 留 NULL 更诚实：表示「首响数据缺失」，统计时可显式排除。
-- 若历史工单已处于 PROCESSING/RESOLVED 等非 PENDING 状态，
-- 说明确实被响应过，但具体时刻不可考——由 response_breached 保持 FALSE
-- 避免把历史工单全标成「首响超时」污染看板。
--
-- 对账查询：
--   SELECT status, COUNT(*) FILTER (WHERE first_response_at IS NULL) AS no_first_resp
--     FROM sys_devops_ticket GROUP BY status;
