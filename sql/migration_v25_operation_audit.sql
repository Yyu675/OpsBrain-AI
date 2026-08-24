-- =====================================================================
-- v25: 通用操作审计（C5）
-- =====================================================================
-- 背景：
--   现有审计只覆盖两处，都是"点状"的：
--     - sys_agent_call_log  —— 只记 AI 对话链路
--     - sys_ticket_activity —— 只记工单域业务流水
--   知识库删除、分类调整、审批通过、告警确认、用户角色变更等写操作
--   全部没有统一留痕。
--
--   这在 L1 阶段是"缺陷"，到 L3/L4 就是"合规红线"：
--   AI 将开始自动执行自愈动作，「AI 在 03:17 自动重启了 order-service 的 pod-3」
--   这条记录必须可追溯，否则出事故时无法定责、无法复盘。
--
-- 设计要点
-- ---------------------------------------------------------------------
-- 1) action 用「语言无关的标识符」而非中文描述（如 knowledge.doc.delete）。
--    中文描述会随文案调整而变，无法用于统计与告警规则；
--    标识符稳定，展示文案由前端映射（未来接 i18n 也不用改数据）。
--
-- 2) 只记摘要不记全文：request_digest 存请求体摘要（已脱敏、截断）。
--    存全文会让审计表迅速膨胀，且可能把密码/密钥写进去——
--    审计表通常权限较宽（运维要查），是最不该存敏感信息的地方。
--
-- 3) 不设外键到 sys_user：审计记录必须比用户存活得久。
--    用户被删除后其历史操作记录仍需保留（否则删号即销毁证据）。
--
-- 幂等性：CREATE TABLE / CREATE INDEX 均 IF NOT EXISTS。
-- =====================================================================

CREATE TABLE IF NOT EXISTS sys_operation_audit (
    id              BIGSERIAL PRIMARY KEY,

    -- 链路关联：与日志、AI 调用记录用同一个 traceId 串起来
    trace_id        VARCHAR(64),

    -- 操作者。actor_id 为 SYSTEM 表示定时任务/AI 自动执行
    actor_id        VARCHAR(64),
    actor_name      VARCHAR(64),

    -- 语言无关的操作标识，如 knowledge.doc.delete / ticket.approve
    action          VARCHAR(64)  NOT NULL,

    -- 操作对象
    target_type     VARCHAR(32),
    target_id       VARCHAR(64),

    -- HTTP 层信息（便于回放与定位来源）
    http_method     VARCHAR(8),
    http_path       VARCHAR(255),
    status_code     INT,

    -- 业务是否成功（HTTP 200 但 code!=0 仍算失败）
    success         BOOLEAN      NOT NULL DEFAULT TRUE,
    biz_code        INT,

    -- 请求体摘要（脱敏 + 截断，绝不存全文，理由见文件头）
    request_digest  VARCHAR(512),

    -- 失败原因（成功时为 NULL）
    error_message   VARCHAR(512),

    client_ip       VARCHAR(45),          -- IPv6 最长 45 字符
    user_agent      VARCHAR(255),
    duration_ms     INT,

    create_time     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 查询模式一：按时间倒序翻页（审计页默认视图）
CREATE INDEX IF NOT EXISTS idx_audit_create_time
    ON sys_operation_audit (create_time DESC);

-- 查询模式二：查某人做过什么（离职审计、越权排查）
CREATE INDEX IF NOT EXISTS idx_audit_actor_time
    ON sys_operation_audit (actor_id, create_time DESC);

-- 查询模式三：查某个对象被谁动过（"这篇文档谁删的"）
CREATE INDEX IF NOT EXISTS idx_audit_target
    ON sys_operation_audit (target_type, target_id);

-- 查询模式四：按 traceId 串联全链路
CREATE INDEX IF NOT EXISTS idx_audit_trace
    ON sys_operation_audit (trace_id);

-- 查询模式五：只看失败操作（安全排查的高频入口）。
-- 部分索引：失败是少数，索引体积远小于全表索引
CREATE INDEX IF NOT EXISTS idx_audit_failures
    ON sys_operation_audit (create_time DESC)
    WHERE success = FALSE;

COMMENT ON TABLE sys_operation_audit IS
    '通用写操作审计。action 为语言无关标识符，展示文案由前端映射';
COMMENT ON COLUMN sys_operation_audit.request_digest IS
    '请求体摘要（脱敏+截断）。禁止存全文：审计表权限较宽，是最不该存敏感信息的地方';
