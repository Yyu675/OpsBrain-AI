-- =====================================================================
-- v21: 补建 sys_alert 告警表（L2 告警链路存量库修复）
-- =====================================================================
-- 背景：6.34/6.35/6.48 实现了 L2 告警全链路（Alertmanager Webhook → 去重 →
-- 持久化 → 自动建单 → WebSocket 推送 → 列表页 + 通知中心），后端代码齐全，
-- `init.sql` 也含本表 DDL（Table 13）。但**存量开发库由早期 init.sql 初始化，
-- 缺这张表**——任何告警接收（AlertWebhookController）或列表查询
-- （AlertQueryService.listAlerts）都会直接抛 relation does not exist。
--
-- 即：整条 L2 告警链路在存量库上从未真正可用，故障表现为 500 而非空列表。
--
-- 与 init.sql 的关系：init.sql 是全新部署的唯一真相源（6.26 契约），本文件
-- 只为已初始化的存量库补建，两处 DDL 必须语义一致。
--
-- 幂等性：CREATE TABLE / CREATE INDEX 全部 IF NOT EXISTS，可反复执行。
-- =====================================================================

CREATE TABLE IF NOT EXISTS sys_alert (
    id                BIGSERIAL PRIMARY KEY,
    source            VARCHAR(32)  NOT NULL DEFAULT 'prometheus',  -- 告警来源（预留多来源）
    alert_name        VARCHAR(128) NOT NULL,                       -- 告警规则名（如 PodCrashLoopBackOff）
    level             VARCHAR(8)   NOT NULL,                       -- P0/P1/P2/P3/P4（Prometheus 分级）
    title             VARCHAR(255) NOT NULL,                       -- 展示标题
    description       TEXT,                                        -- 告警详情/标签渲染文本
    status            VARCHAR(16)  NOT NULL DEFAULT 'FIRING',      -- FIRING/ACKNOWLEDGED/SUPPRESSED/RESOLVED
    dedup_key         VARCHAR(255) NOT NULL,                       -- alert_name+service+labels 哈希（去重窗口键）
    service           VARCHAR(128),                                -- 服务名（annotations.service）
    module            VARCHAR(32),                                 -- 映射后的业务模块枚举（HOST/POD/DB/CACHE/NETWORK 等）
    occurrence_count  INT          NOT NULL DEFAULT 1,             -- 窗口内重复触达次数
    first_occurred_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_occurred_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    acknowledged_at   TIMESTAMP,                                   -- 人工确认时间（P0/P1 必填）
    resolved_at       TIMESTAMP,                                   -- 恢复时间
    ticket_id         VARCHAR(64),                                 -- 自动建单后回填的工单号
    create_time       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 窗口内去重索引：同 dedup_key 的未解决告警只允许一条。
-- 用**部分唯一索引**而非全表唯一：RESOLVED 后同键告警可再次触发（同一故障会复发），
-- 全表唯一会让复发告警插入失败（同 6.21 知识文档 content_hash 的取舍）。
CREATE UNIQUE INDEX IF NOT EXISTS uk_alert_active_dedup
    ON sys_alert (dedup_key) WHERE status IN ('FIRING','ACKNOWLEDGED');

-- 常用查询索引（列表页按状态/级别/服务筛选，按 first_occurred_at 倒序）
CREATE INDEX IF NOT EXISTS idx_alert_status  ON sys_alert (status, first_occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_alert_level   ON sys_alert (level, first_occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_alert_service ON sys_alert (service, first_occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_alert_ticket  ON sys_alert (ticket_id);
