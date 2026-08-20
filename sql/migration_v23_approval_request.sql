-- =====================================================================
-- v23: 审批单表（方向 D：L3 人机协同审批）
-- =====================================================================
-- 背景：审批拦截此前只有「标记位」——writeTicketFromDraft 检测 needsApproval
-- 后阻止写入、置 WAITING_APPROVAL、Saga 登记 SKIPPED，然后**动作就被丢弃了**。
-- 审批通过后没有任何机制重放执行，「人机协同」名不副实。
--
-- 本表承载可重放的动作上下文（payload），使「AI 提议 → 人工审批 → 受控执行」闭环。
--
-- 对齐蓝图 §二：P0/P1 高危故障「必须由人工专家审查确认点击后，AI 才可执行敏感操作」。
--
-- 关键设计：
-- 1) payload 存完整动作上下文（JSONB），批准时据此重放——不存则批准后无从执行
-- 2) APPROVED 与 EXECUTED 分开：批准后执行可能失败，须区分「已批准未执行」与「已执行」
--    （遵循「既成事实必须固化」契约，同 B1 首响超时）
-- 3) risk_level 复用 ToolRiskLevel 枚举值（READ_ONLY/DRAFT/CONTROLLED_WRITE/
--    HIGH_RISK_EXECUTION），不新建 ActionPermissionLevel——蓝图三级语义已被其覆盖，
--    新建会造成同一事实两处定义必然漂移（6.20 契约）
--
-- 幂等性：CREATE TABLE / CREATE INDEX 全部 IF NOT EXISTS。
-- =====================================================================

CREATE TABLE IF NOT EXISTS sys_approval_request (
    id              BIGSERIAL PRIMARY KEY,

    -- ---- 动作描述 ----
    action_type     VARCHAR(32)  NOT NULL,        -- CREATE_TICKET / EXECUTE_SCRIPT（预留）等
    tool_name       VARCHAR(64),                  -- 触发审批的工具名（对齐 @ToolMeta.name）
    risk_level      VARCHAR(32)  NOT NULL,        -- 复用 ToolRiskLevel：READ_ONLY/DRAFT/CONTROLLED_WRITE/HIGH_RISK_EXECUTION
    summary         VARCHAR(255) NOT NULL,        -- 人可读的「要做什么」（审批列表展示）
    -- 可重放的动作上下文。批准时据此执行——不存则批准后无从执行（本表存在的核心理由）
    payload         JSONB,

    -- ---- 申请上下文 ----
    requester       VARCHAR(64)  NOT NULL DEFAULT 'AI',  -- 申请方：AI / 用户名
    trace_id        VARCHAR(64),                  -- 关联发起请求（可回放整条 Agent 链路）
    session_id      VARCHAR(64),

    -- ---- 审批状态机 ----
    -- PENDING → APPROVED → EXECUTED / EXECUTE_FAILED
    -- PENDING → REJECTED
    -- PENDING → EXPIRED（超时未审批）
    status          VARCHAR(24)  NOT NULL DEFAULT 'PENDING',
    approver        VARCHAR(64),                  -- 审批人（来自 Sa-Token 真实身份）
    decided_at      TIMESTAMP,                    -- 批准/驳回时刻
    decision_reason VARCHAR(500),                 -- 决策理由（驳回必填，批准可选）
    expires_at      TIMESTAMP,                    -- 审批时限（超时由定时任务标 EXPIRED）

    -- ---- 执行结果（批准后）----
    executed_at     TIMESTAMP,
    execute_result  TEXT,                         -- 执行结果摘要 / 失败原因

    create_time     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 待审队列查询：按状态 + 创建时间正序（最早提交的先审）
CREATE INDEX IF NOT EXISTS idx_approval_status_time
    ON sys_approval_request (status, create_time);

-- 按发起请求回查（排查「这条 Agent 请求为何没建单」）
CREATE INDEX IF NOT EXISTS idx_approval_trace
    ON sys_approval_request (trace_id);

-- 超时扫描：仅扫待审的（部分索引，已决策的永不需再扫）
CREATE INDEX IF NOT EXISTS idx_approval_pending_expire
    ON sys_approval_request (expires_at) WHERE status = 'PENDING';
