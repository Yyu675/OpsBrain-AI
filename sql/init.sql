-- =====================================================================
-- OpsBrain AI Database Initialization Script
-- Description: Auto-executed by pgvector container on first startup
-- Vector Dimension: 1536 (Cloud API Embedding)
-- =====================================================================

-- Enable pgvector extension
CREATE EXTENSION IF NOT EXISTS vector;

-- ---------------------------------------------------------------------
-- Table 1: sys_knowledge_chunk - Knowledge base chunks with vectors
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sys_knowledge_chunk (
    id             BIGSERIAL PRIMARY KEY,
    -- 所属文档 ID（关联 sys_knowledge_doc）。
    -- 全量重建时按此删除旧切片；仅靠 doc_title 字符串关联时改标题即断链
    doc_id         BIGINT,
    doc_title      VARCHAR(255) NOT NULL,
    section_header VARCHAR(512),
    content        TEXT NOT NULL,
    -- 切片内容 SHA-256：同文档内切片级去重
    content_hash   CHAR(64),
    parent_id      VARCHAR(128),
    parent_text    TEXT,
    chunk_meta     JSONB,
    content_tsv    TSVECTOR,
    embedding      VECTOR(1536) NOT NULL,
    -- MVP-5 知识治理字段
    version          INT         DEFAULT 1         NOT NULL,  -- 版本号，用于版本对比与乐观锁
    effective_at     TIMESTAMP,                               -- 生效时间，NULL=立即生效
    expired_at       TIMESTAMP,                               -- 过期时间，NULL=永不过期
    status           VARCHAR(16) DEFAULT 'ACTIVE'  NOT NULL,  -- ACTIVE/DEPRECATED/ARCHIVED
    knowledge_source VARCHAR(32) DEFAULT 'UNKNOWN' NOT NULL,  -- OFFICIAL/SOP/TICKET/BLOG/UNKNOWN
    create_time    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time    TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_chunk_doc_id       ON sys_knowledge_chunk (doc_id);
CREATE INDEX IF NOT EXISTS idx_chunk_content_hash ON sys_knowledge_chunk (content_hash);

-- Vector index: HNSW for cosine similarity
CREATE INDEX IF NOT EXISTS idx_chunk_embedding_hnsw
    ON sys_knowledge_chunk USING hnsw (embedding vector_cosine_ops);

-- Full-text search: GIN index
CREATE INDEX IF NOT EXISTS idx_chunk_tsv_gin
    ON sys_knowledge_chunk USING gin (content_tsv);

-- Parent aggregation helper
CREATE INDEX IF NOT EXISTS idx_chunk_parent
    ON sys_knowledge_chunk (parent_id);

-- MVP-5 知识治理检索索引
CREATE INDEX IF NOT EXISTS idx_chunk_version_status
    ON sys_knowledge_chunk (version, status);
CREATE INDEX IF NOT EXISTS idx_chunk_effective_expired
    ON sys_knowledge_chunk (effective_at, expired_at);
CREATE INDEX IF NOT EXISTS idx_chunk_source
    ON sys_knowledge_chunk (knowledge_source);

-- P2-21: content_tsv 全文检索触发器
-- 此前 content_tsv 列存在但无触发器填充，始终为 NULL，
-- 混合检索（hybridEnabled=true）的 ts_rank 部分退化为 0，
-- 静默退化为纯向量检索，且无人察觉。
DROP TRIGGER IF EXISTS trg_chunk_tsv_update ON sys_knowledge_chunk;
CREATE TRIGGER trg_chunk_tsv_update
    BEFORE INSERT OR UPDATE ON sys_knowledge_chunk
    FOR EACH ROW EXECUTE FUNCTION
    tsvector_update_trigger(content_tsv, 'pg_catalog.simple', content);

-- ---------------------------------------------------------------------
-- Table 2: sys_devops_ticket - DevOps ticket management
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sys_devops_ticket (
    id              VARCHAR(64) PRIMARY KEY,
    title           VARCHAR(255) NOT NULL,
    -- 优先级四档 P0/P1/P2/P3（B0 起，原 HIGH/MEDIUM/LOW 三档）
    -- P0=生产宕机 / P1=影响业务有临时方案 / P2=需处理不紧急 / P3=优化建议
    priority        VARCHAR(32) NOT NULL,
    module          VARCHAR(64) NOT NULL,
    description     TEXT,
    stack_trace     TEXT,
    status          VARCHAR(32) DEFAULT 'PENDING',
    source_trace_id VARCHAR(64),
    assignee        VARCHAR(64),
    creator         VARCHAR(64) DEFAULT 'devops-admin',
    category        VARCHAR(64),
    sla             VARCHAR(128),
    -- B0 SLA 计时基线：建单时按优先级派生并冻结，不随时限策略调整而追溯改写
    response_deadline TIMESTAMP,
    resolve_deadline  TIMESTAMP,
    -- B1 首响：NULL=尚未首响。AI 自动回复不计入首响（否则建单即「已首响」，SLA 形同虚设）
    first_response_at TIMESTAMP,
    first_responder   VARCHAR(64),
    response_breached BOOLEAN NOT NULL DEFAULT FALSE,
    -- B1 升级：L1 阶段只记录留痕，不自动改优先级/换负责人（属 L3 审批范畴）
    escalated_at      TIMESTAMP,
    escalate_reason   VARCHAR(255),
    -- B2 现场处置阶段（仅 PROCESSING 期间有效；转出时清空）
    handling_stage    VARCHAR(24),    -- TRIAGE/MITIGATED/FIXING/VERIFYING
    mitigated_at      TIMESTAMP,       -- 止损完成时刻（业务恢复）
    -- B3 根因分析（人工确认，≠AI 建议）
    root_cause        TEXT,
    root_cause_category VARCHAR(32),   -- CONFIG/CAPACITY/CODE/DEPENDENCY/NETWORK/DATA/HUMAN/EXTERNAL/UNKNOWN
    root_cause_by     VARCHAR(64),
    root_cause_at     TIMESTAMP,
    -- B3 修复验证（D3：必填但允许带理由跳过）
    verified_at       TIMESTAMP,
    verifier           VARCHAR(64),
    verify_method      VARCHAR(32),    -- MONITOR/LOG/BUSINESS/MANUAL
    verify_conclusion  TEXT,
    verify_skipped     BOOLEAN NOT NULL DEFAULT FALSE,
    verify_skip_reason VARCHAR(255),
    -- P1-4 乐观锁：每次更新 +1，UPDATE 带 WHERE version=? 防并发覆盖
    version         INT DEFAULT 0 NOT NULL,
    create_time     TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_ticket_priority ON sys_devops_ticket (priority);
CREATE INDEX IF NOT EXISTS idx_ticket_version  ON sys_devops_ticket (id, version);
CREATE INDEX IF NOT EXISTS idx_ticket_status   ON sys_devops_ticket (status);
CREATE INDEX IF NOT EXISTS idx_ticket_assignee ON sys_devops_ticket (assignee);
CREATE INDEX IF NOT EXISTS idx_ticket_creator  ON sys_devops_ticket (creator);
CREATE INDEX IF NOT EXISTS idx_ticket_module   ON sys_devops_ticket (module);
-- SLA 超时清单查询（B1 首响扫描依赖）
CREATE INDEX IF NOT EXISTS idx_ticket_response_deadline ON sys_devops_ticket (response_deadline);
CREATE INDEX IF NOT EXISTS idx_ticket_resolve_deadline  ON sys_devops_ticket (resolve_deadline);
-- 部分索引只覆盖「未首响」的行：已首响的工单永不需要再扫，
-- 全表索引会随历史数据线性膨胀而扫描成本不降
CREATE INDEX IF NOT EXISTS idx_ticket_pending_response
    ON sys_devops_ticket (response_deadline) WHERE first_response_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_ticket_escalated
    ON sys_devops_ticket (escalated_at) WHERE escalated_at IS NOT NULL;

-- ---------------------------------------------------------------------
-- Table 3: sys_agent_call_log - Agent call logs and cost tracking
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sys_agent_call_log (
    id                 BIGSERIAL PRIMARY KEY,
    trace_id           VARCHAR(64),
    user_query         TEXT,
    agent_answer       TEXT,
    model_name         VARCHAR(64),
    is_cached          BOOLEAN DEFAULT FALSE,
    latency_ms         INT,
    cost_rmb           DOUBLE PRECISION,
    citations          TEXT,
    -- MVP-4 审计增强字段
    operation_type     VARCHAR(64),   -- CHAT/CACHE_HIT/SEARCH/CREATE_TICKET/APPROVE/EXECUTE/COMPENSATE
    affected_resources TEXT,          -- 影响资源 JSON 数组，如 ["TKT-20260808-0001"]
    operator_id        VARCHAR(64),   -- SYSTEM / 用户ID / 定时器名
    create_time        TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_log_create_time ON sys_agent_call_log (create_time);
CREATE INDEX IF NOT EXISTS idx_log_model       ON sys_agent_call_log (model_name);
CREATE INDEX IF NOT EXISTS idx_log_operation   ON sys_agent_call_log (operation_type);
CREATE INDEX IF NOT EXISTS idx_log_operator    ON sys_agent_call_log (operator_id);
CREATE INDEX IF NOT EXISTS idx_log_trace       ON sys_agent_call_log (trace_id);

-- ---------------------------------------------------------------------
-- Table 4: sys_ticket_reply - Ticket replies and comments
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sys_ticket_reply (
    id              BIGSERIAL PRIMARY KEY,
    ticket_id       VARCHAR(64) NOT NULL,
    role            VARCHAR(32) NOT NULL,
    author          VARCHAR(64) NOT NULL,
    author_color    VARCHAR(16),
    content         TEXT NOT NULL,
    create_time     TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_reply_ticket ON sys_ticket_reply (ticket_id);
CREATE INDEX IF NOT EXISTS idx_reply_time   ON sys_ticket_reply (create_time);

-- ---------------------------------------------------------------------
-- Table 5: sys_ticket_activity - Ticket activity stream
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sys_ticket_activity (
    id              BIGSERIAL PRIMARY KEY,
    ticket_id       VARCHAR(64) NOT NULL,
    color           VARCHAR(32) NOT NULL,
    text            VARCHAR(255) NOT NULL,
    detail          VARCHAR(512),
    user_name       VARCHAR(64) NOT NULL,
    highlight       BOOLEAN DEFAULT FALSE,
    create_time     TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_activity_ticket ON sys_ticket_activity (ticket_id);
CREATE INDEX IF NOT EXISTS idx_activity_time   ON sys_ticket_activity (create_time);

-- ---------------------------------------------------------------------
-- Table 6: sys_agent_session_summary - 温记忆（P1-1 三层记忆架构）
-- ---------------------------------------------------------------------
-- 三层记忆分工：
--   热记忆 Hot  → Redis（最近 N 轮对话 + 会话状态，TTL 滑动续期）
--   温记忆 Warm → 本表（会话摘要 + 关键事实蒸馏，可审计可续聊）
--   冷记忆 Cold → 归档文件（历史全量，需要时再摘要召回）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sys_agent_session_summary (
    id              BIGSERIAL PRIMARY KEY,
    session_id      VARCHAR(64) NOT NULL,
    trace_id        VARCHAR(64),
    tenant_id       VARCHAR(64) DEFAULT 'default',   -- 多租户预留（P1-9）
    summary         TEXT,                            -- 会话摘要
    key_facts       JSONB,                           -- 关键事实：intent/confirmed_facts/tools_used/conclusion/pending_risks
    turn_count      INT DEFAULT 0,
    total_tokens    INT DEFAULT 0,
    total_cost_rmb  DOUBLE PRECISION DEFAULT 0,
    final_state     VARCHAR(32),                     -- AgentState 终态
    related_tickets TEXT,                            -- JSON 数组
    archived        BOOLEAN DEFAULT FALSE,           -- 是否已归档到冷存储
    archive_path    VARCHAR(512),
    create_time     TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_summary_session      ON sys_agent_session_summary (session_id);
CREATE INDEX IF NOT EXISTS idx_summary_trace              ON sys_agent_session_summary (trace_id);
CREATE INDEX IF NOT EXISTS idx_summary_tenant             ON sys_agent_session_summary (tenant_id);
CREATE INDEX IF NOT EXISTS idx_summary_create_time        ON sys_agent_session_summary (create_time);
CREATE INDEX IF NOT EXISTS idx_summary_final_state        ON sys_agent_session_summary (final_state);
CREATE INDEX IF NOT EXISTS idx_summary_archive_scan       ON sys_agent_session_summary (archived, create_time);
CREATE INDEX IF NOT EXISTS idx_summary_key_facts_gin      ON sys_agent_session_summary USING gin (key_facts);

-- ---------------------------------------------------------------------
-- Table 7: sys_agent_tool_execution - Saga 工具执行记录（P1-2）
-- ---------------------------------------------------------------------
-- 核心价值：进程重启后仍能恢复未完成的补偿。仅存 Redis 会在重启后
-- 丢失补偿上下文，导致脏数据永久残留。
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sys_agent_tool_execution (
    id                  BIGSERIAL PRIMARY KEY,
    trace_id            VARCHAR(64) NOT NULL,
    session_id          VARCHAR(64),
    saga_id             VARCHAR(64) NOT NULL,      -- 同一次 Agent 执行内的工具共享
    step_seq            INT NOT NULL,              -- 步骤序号，补偿时逆序执行
    tool_name           VARCHAR(64) NOT NULL,
    risk_level          VARCHAR(32),
    tool_args           TEXT,                      -- 入参快照（补偿与回放用）
    tool_result         TEXT,
    state               VARCHAR(32) NOT NULL,      -- 工具状态机，见 ToolExecutionState
    failure_type        VARCHAR(32),
    error_message       TEXT,
    compensable         BOOLEAN DEFAULT FALSE,
    compensation_action VARCHAR(64),
    business_key        VARCHAR(128),              -- 业务标识（工单号等），补偿入参
    compensated_at      TIMESTAMP,
    compensation_error  TEXT,
    attempt_count       INT DEFAULT 1,
    duration_ms         INT,
    create_time         TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time         TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
-- ---------------------------------------------------------------------
-- Table 9: sys_ticket_attachment - 工单附件元数据（文件本体在 MinIO）
-- ---------------------------------------------------------------------
-- object_key 由服务端生成（日期分区 + UUID），绝不使用用户提交的
-- 文件名——后者可含 ../ 路径穿越或特殊字符。
-- original_name 仅用于展示与 Content-Disposition。
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sys_ticket_attachment (
    id             BIGSERIAL PRIMARY KEY,
    ticket_id      VARCHAR(64)  NOT NULL,
    bucket         VARCHAR(64)  NOT NULL,
    object_key     VARCHAR(512) NOT NULL,
    original_name  VARCHAR(255) NOT NULL,
    content_type   VARCHAR(128),
    size_bytes     BIGINT       NOT NULL,
    sha256         VARCHAR(64),
    uploader       VARCHAR(64),
    create_time    TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_attachment_object     ON sys_ticket_attachment (object_key);
CREATE INDEX IF NOT EXISTS idx_attachment_ticket           ON sys_ticket_attachment (ticket_id);
CREATE INDEX IF NOT EXISTS idx_attachment_ticket_sha       ON sys_ticket_attachment (ticket_id, sha256);

-- ---------------------------------------------------------------------
-- Table 8: sys_ticket_tag - 工单标签关联表
-- ---------------------------------------------------------------------
-- 一行一个标签，支持按标签筛选与热度统计。
-- 此前标签由前端根据 module 编造（每张工单都贴「生产环境」），
-- 用户输入被丢弃，筛选等价于按 module 筛选。
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sys_ticket_tag (
    id          BIGSERIAL PRIMARY KEY,
    ticket_id   VARCHAR(64) NOT NULL,
    tag         VARCHAR(64) NOT NULL,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
-- 幂等保证：同工单同标签不重复（应用层依赖此约束做 ON CONFLICT DO NOTHING）
CREATE UNIQUE INDEX IF NOT EXISTS uk_ticket_tag ON sys_ticket_tag (ticket_id, tag);
CREATE INDEX IF NOT EXISTS idx_tag_ticket        ON sys_ticket_tag (ticket_id);
CREATE INDEX IF NOT EXISTS idx_tag_name          ON sys_ticket_tag (tag);

-- 唯一性约束：同 Saga 内 step_seq 唯一，与 nextStepSeq 的同步化共同保证
-- 并发登记不产生重复步骤（P2-37）
CREATE UNIQUE INDEX IF NOT EXISTS idx_tool_exec_saga       ON sys_agent_tool_execution (saga_id, step_seq);
CREATE INDEX IF NOT EXISTS idx_tool_exec_trace      ON sys_agent_tool_execution (trace_id);
CREATE INDEX IF NOT EXISTS idx_tool_exec_session    ON sys_agent_tool_execution (session_id);
CREATE INDEX IF NOT EXISTS idx_tool_exec_state      ON sys_agent_tool_execution (state);
CREATE INDEX IF NOT EXISTS idx_tool_exec_recovery   ON sys_agent_tool_execution (state, create_time);
CREATE INDEX IF NOT EXISTS idx_tool_exec_name_state ON sys_agent_tool_execution (tool_name, state);

-- ---------------------------------------------------------------------
-- Table 10: sys_knowledge_doc - 知识文档主表（只存当前版本）
-- ---------------------------------------------------------------------
-- 此前知识源硬编码为 classpath:knowledge/*.md，只能读 jar 内静态文件，
-- 用户在 UI 写的文章不落库、不向量化、AI 永远检索不到。
--
-- 设计决策：
--   1. 文档级全量重建（切片边界随正文变化整体漂移，无法做切片级 diff）
--   2. 当前版本带向量参与检索；历史版本只存原文（向量是可再生派生物，
--      1536 维约 6KB/切片，比正文大两个数量级）
--   3. content_hash 精确去重 + simhash 近似去重
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sys_knowledge_doc (
    id               BIGSERIAL PRIMARY KEY,
    title            VARCHAR(255) NOT NULL,
    category         VARCHAR(64),
    category_id      BIGINT,
    author           VARCHAR(64),
    content          TEXT         NOT NULL,       -- Markdown 原文
    summary          VARCHAR(512),
    version          INT          DEFAULT 1 NOT NULL,
    content_hash     CHAR(64)     NOT NULL,       -- SHA-256(content)，未变则跳过向量化
    simhash          BIGINT,                      -- 64 位指纹，汉明距离≤3 视为近似重复
    status           VARCHAR(16)  DEFAULT 'DRAFT' NOT NULL,      -- DRAFT/PUBLISHED/DEPRECATED/ARCHIVED
    -- index_status 与 status 分离：文档可 PUBLISHED 但向量化失败，
    -- 混用会让「已发布」错误暗示「可检索」
    index_status     VARCHAR(16)  DEFAULT 'PENDING' NOT NULL,    -- PENDING/INDEXED/FAILED/SKIPPED
    index_error      TEXT,
    indexed_at       TIMESTAMP,
    chunk_count      INT          DEFAULT 0,
    effective_at     TIMESTAMP,
    expired_at       TIMESTAMP,
    knowledge_source VARCHAR(32)  DEFAULT 'SOP' NOT NULL,
    -- B-2 来源回链：由工单沉淀的文档反查源工单（L1.5 复盘知识沉淀）
    source_ticket_id BIGINT,                      -- 源工单 ID，非工单沉淀时为 NULL
    source_type      VARCHAR(32),                 -- 来源类型：TICKET/MANUAL/IMPORT 等
    create_time      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time      TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 来源回链索引：按源工单反查「此工单已沉淀为哪些知识」
CREATE INDEX IF NOT EXISTS idx_doc_source_ticket ON sys_knowledge_doc (source_ticket_id) WHERE source_ticket_id IS NOT NULL;
-- v9: 部分唯一索引（只约束 DRAFT/PUBLISHED，废弃/归档退出约束，支持历史版本回滚）
CREATE UNIQUE INDEX IF NOT EXISTS uk_doc_hash
    ON sys_knowledge_doc (content_hash)
    WHERE status IN ('DRAFT', 'PUBLISHED');
CREATE INDEX IF NOT EXISTS idx_doc_status      ON sys_knowledge_doc (status);
CREATE INDEX IF NOT EXISTS idx_doc_category    ON sys_knowledge_doc (category);
CREATE INDEX IF NOT EXISTS idx_doc_category_id ON sys_knowledge_doc (category_id);
CREATE INDEX IF NOT EXISTS idx_doc_index_status ON sys_knowledge_doc (index_status);
CREATE INDEX IF NOT EXISTS idx_doc_simhash     ON sys_knowledge_doc (simhash);
CREATE INDEX IF NOT EXISTS idx_doc_update_time ON sys_knowledge_doc (update_time DESC);
CREATE INDEX IF NOT EXISTS idx_doc_title       ON sys_knowledge_doc (title);

-- ---------------------------------------------------------------------
-- Table 10.1: sys_knowledge_category - 可独立维护的知识库目录分类
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sys_knowledge_category (
    id          BIGSERIAL PRIMARY KEY,
    parent_id   BIGINT REFERENCES sys_knowledge_category(id),
    name        VARCHAR(64) NOT NULL,
    sort_order  INT NOT NULL DEFAULT 0,
    status      VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_knowledge_category_name
    ON sys_knowledge_category (LOWER(name));
CREATE INDEX IF NOT EXISTS idx_knowledge_category_parent
    ON sys_knowledge_category (parent_id, sort_order, id);

-- 兼容旧数据：把文档上的非空字符串分类回填为根目录。
INSERT INTO sys_knowledge_category (name, sort_order)
SELECT DISTINCT TRIM(d.category), 0
  FROM sys_knowledge_doc d
 WHERE d.category IS NOT NULL AND TRIM(d.category) <> ''
   AND NOT EXISTS (
       SELECT 1 FROM sys_knowledge_category c
        WHERE LOWER(c.name) = LOWER(TRIM(d.category))
   );

-- ---------------------------------------------------------------------
-- Table 11: sys_knowledge_doc_history - 历史版本（只存原文，不存向量）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sys_knowledge_doc_history (
    id            BIGSERIAL PRIMARY KEY,
    doc_id        BIGINT       NOT NULL,
    version       INT          NOT NULL,
    title         VARCHAR(255) NOT NULL,
    category      VARCHAR(64),
    author        VARCHAR(64),
    content       TEXT         NOT NULL,
    content_hash  CHAR(64)     NOT NULL,
    changed_by    VARCHAR(64),
    change_reason VARCHAR(255),
    change_type   VARCHAR(16)  NOT NULL,          -- CREATE/UPDATE/DEPRECATE/RESTORE
    create_time   TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_hist_doc_version ON sys_knowledge_doc_history (doc_id, version);
CREATE INDEX IF NOT EXISTS idx_hist_doc  ON sys_knowledge_doc_history (doc_id, version DESC);
CREATE INDEX IF NOT EXISTS idx_hist_time ON sys_knowledge_doc_history (create_time DESC);

-- ---------------------------------------------------------------------
-- Table 12: sys_knowledge_doc_tag - 文档标签
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sys_knowledge_doc_tag (
    id          BIGSERIAL PRIMARY KEY,
    doc_id      BIGINT      NOT NULL,
    tag         VARCHAR(64) NOT NULL,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_doc_tag ON sys_knowledge_doc_tag (doc_id, tag);
CREATE INDEX IF NOT EXISTS idx_doc_tag_doc  ON sys_knowledge_doc_tag (doc_id);
CREATE INDEX IF NOT EXISTS idx_doc_tag_name ON sys_knowledge_doc_tag (tag);

-- Table 12.1: 全局标签字典（文档关联表保留字符串以兼容历史数据）
CREATE TABLE IF NOT EXISTS sys_knowledge_tag (
    id              BIGSERIAL PRIMARY KEY,
    name            VARCHAR(64) NOT NULL,
    normalized_name VARCHAR(64) NOT NULL,
    description     VARCHAR(255),
    color           VARCHAR(16),
    status          VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    create_time     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_knowledge_tag_normalized
    ON sys_knowledge_tag (normalized_name);

-- ---------------------------------------------------------------------
-- Table 13: sys_alert - 告警实体（L2 实时监测）
-- ---------------------------------------------------------------------
-- 来源：Alertmanager Webhook Push（方案A 已拍板，见 CLAUDE.md §6.3）。
-- 状态机：FIRING（触发中）→ ACKNOWLEDGED（人工确认，P0/P1）→ RESOLVED（已恢复）
--         SUPPRESSED（海量去重合并后的静默跟随者）
-- 5 分钟去重窗口（Alert Storm Shield）：同 dedup_key 窗口内只保留一条
-- FIRING 主记录，后续同键事件累加 repeat_count 并 SUPPRESSED。
-- ---------------------------------------------------------------------
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
-- 窗口内去重索引：同 dedup_key 的未解决告警只允许一条
CREATE UNIQUE INDEX IF NOT EXISTS uk_alert_active_dedup ON sys_alert (dedup_key) WHERE status IN ('FIRING','ACKNOWLEDGED');
-- 常用查询索引
CREATE INDEX IF NOT EXISTS idx_alert_status      ON sys_alert (status, first_occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_alert_level       ON sys_alert (level, first_occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_alert_service     ON sys_alert (service, first_occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_alert_ticket      ON sys_alert (ticket_id);

-- ---------------------------------------------------------------------
-- Table 16: sys_team_member - 运维团队成员名录（工单负责人来源）
-- ---------------------------------------------------------------------
-- 前端此前硬编码 ASSIGNEE_OPTIONS 七人编造名单，工单会被指派给不存在的人。
-- 现由 GET /api/v1/users 下发本表，名单随真实数据变化。
CREATE TABLE IF NOT EXISTS sys_team_member (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(64)  NOT NULL,                      -- 显示名，与 sys_devops_ticket.assignee 对应
    email       VARCHAR(128),
    role        VARCHAR(32)  NOT NULL DEFAULT 'operator',   -- admin/operator/viewer（对齐前端 Role 词表）
    title       VARCHAR(64),                                -- 职位，如「高级运维工程师」
    status      VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',     -- ACTIVE/DISABLED（停用者不再出现在选人列表）
    sort_order  INT          NOT NULL DEFAULT 0,
    create_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
-- 同名成员只允许一条：assignee 按姓名匹配，重名会让筛选结果割裂
CREATE UNIQUE INDEX IF NOT EXISTS uk_team_member_name
    ON sys_team_member (LOWER(name));
CREATE INDEX IF NOT EXISTS idx_team_member_status
    ON sys_team_member (status, sort_order, id);

-- 种子 1：兜底管理员。全新部署库中无工单，名录为空会导致选人下拉框空白无法指派。
-- 「管理员」与前端 stores/app.ts DEFAULT_USER.name 一致，是平台真实默认账号。
INSERT INTO sys_team_member (name, email, role, title, sort_order)
SELECT '管理员', 'admin@devops.local', 'admin', '高级运维工程师', 0
 WHERE NOT EXISTS (
       SELECT 1 FROM sys_team_member m WHERE LOWER(m.name) = LOWER('管理员')
   );

-- 种子 2：回填存量工单中真实出现过的负责人（不编造姓名）。
-- 排除「待分配」——它是前端「未指派」哨兵值，不是人。
INSERT INTO sys_team_member (name, role, sort_order)
SELECT s.name, 'operator', 10
  FROM (
        SELECT MIN(TRIM(t.assignee)) AS name
          FROM sys_devops_ticket t
         WHERE t.assignee IS NOT NULL
           AND TRIM(t.assignee) <> ''
           AND TRIM(t.assignee) <> '待分配'
         GROUP BY LOWER(TRIM(t.assignee))
       ) s
 WHERE NOT EXISTS (
       SELECT 1 FROM sys_team_member m WHERE LOWER(m.name) = LOWER(s.name)
   );

-- ---------------------------------------------------------------------
-- Table 17: sys_ticket_ai_analysis - 工单 AI 分析（策略 B，结构化+多版本+反馈）
-- ---------------------------------------------------------------------
-- 策略 A（存进 sys_ticket_reply role='ai'）会丢结构化字段、无法多版本对比、
-- 无法记录 AI 准确率。本表保留结构化字段 + 版本 + 用户反馈。
CREATE TABLE IF NOT EXISTS sys_ticket_ai_analysis (
    id           BIGSERIAL PRIMARY KEY,
    ticket_id    VARCHAR(64)  NOT NULL,
    version      INT          NOT NULL DEFAULT 1,       -- 第几次分析（同工单递增，最新为当前结论）
    content      TEXT         NOT NULL,                 -- 原始 markdown 全文
    reasons      JSONB,                                 -- 可能原因数组
    commands     JSONB,                                 -- 排查命令数组
    citations    JSONB,                                 -- 引用来源数组
    confidence   INT,                                   -- 置信度 0-100，NULL=未给出
    cost_rmb     NUMERIC(10,4) DEFAULT 0,               -- 本次分析成本
    feedback     VARCHAR(16),                           -- NULL=未评价 / HELPFUL / UNHELPFUL
    feedback_at  TIMESTAMP,
    create_time  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_ai_analysis_ticket
    ON sys_ticket_ai_analysis (ticket_id, version DESC);
CREATE INDEX IF NOT EXISTS idx_ai_analysis_feedback
    ON sys_ticket_ai_analysis (feedback) WHERE feedback IS NOT NULL;

-- ---------------------------------------------------------------------
-- Table 18: sys_ticket_action - 处置动作记录（B2 现场处置留痕）
-- ---------------------------------------------------------------------
-- effective 允许为 false：失败尝试同样有价值（PRD §2.1 排查占 40% 且依赖经验）
CREATE TABLE IF NOT EXISTS sys_ticket_action (
    id           BIGSERIAL PRIMARY KEY,
    ticket_id    VARCHAR(64)  NOT NULL,
    action_type  VARCHAR(24)  NOT NULL,   -- MITIGATE止损/INVESTIGATE排查/FIX修复/ROLLBACK回滚/VERIFY验证
    summary      VARCHAR(255) NOT NULL,
    detail       TEXT,
    operator     VARCHAR(64)  NOT NULL,
    effective    BOOLEAN,                 -- NULL=未判定 / true=有效 / false=无效
    started_at   TIMESTAMP,
    finished_at  TIMESTAMP,
    create_time  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_ticket_action_ticket
    ON sys_ticket_action (ticket_id, create_time);

-- ---------------------------------------------------------------------
-- Table 19: sys_ticket_postmortem - 复盘归档（B4 闭环阶段 7）
-- ---------------------------------------------------------------------
-- PRD §2.1 阶段 7：最容易被忽视，相同故障在不同团队反复发生
CREATE TABLE IF NOT EXISTS sys_ticket_postmortem (
    id              BIGSERIAL PRIMARY KEY,
    ticket_id       VARCHAR(64)  NOT NULL UNIQUE,
    timeline        TEXT,
    impact_scope    VARCHAR(255),
    impact_duration INT,
    lessons         TEXT,
    doc_id          BIGINT,
    author          VARCHAR(64),
    create_time     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ---------------------------------------------------------------------
-- Table 20: sys_postmortem_action_item - 改进项（独立跟踪）
-- ---------------------------------------------------------------------
-- 改进项独立成表而非复盘文档里的一段文字：不可查询=不会被跟踪=等于没写
CREATE TABLE IF NOT EXISTS sys_postmortem_action_item (
    id             BIGSERIAL PRIMARY KEY,
    postmortem_id  BIGINT       NOT NULL,
    ticket_id      VARCHAR(64)  NOT NULL,
    content        VARCHAR(500) NOT NULL,
    owner          VARCHAR(64),
    due_date       DATE,
    status         VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    create_time    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_pm_action_postmortem
    ON sys_postmortem_action_item (postmortem_id);
CREATE INDEX IF NOT EXISTS idx_pm_action_ticket
    ON sys_postmortem_action_item (ticket_id);
CREATE INDEX IF NOT EXISTS idx_pm_action_status_due
    ON sys_postmortem_action_item (status, due_date) WHERE status IN ('OPEN', 'DOING');

-- ---------------------------------------------------------------------
-- Table 21: sys_user - 系统用户（方向三：真实鉴权 JWT + BCrypt）
-- ---------------------------------------------------------------------
-- 提供真实用户来源，替代前端硬编码假管理员。密码存 BCrypt 哈希绝不明文。
-- 种子管理员由 AuthDataInitializer 启动时用 BCryptPasswordEncoder 编码写入
-- （迁移不写死哈希——BCrypt 每次 salt 不同，运行时编码才能与登录校验一致）。
-- role 对齐前端 Role（ADMIN/OPS）；display_name 可对齐 sys_team_member.name。
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sys_user (
    id            BIGSERIAL PRIMARY KEY,
    username      VARCHAR(64)  NOT NULL,
    password      VARCHAR(100) NOT NULL,                        -- BCrypt 哈希
    display_name  VARCHAR(64),
    role          VARCHAR(32)  NOT NULL DEFAULT 'OPS',          -- ADMIN/OPS
    status        VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',       -- ACTIVE/DISABLED
    last_login_at TIMESTAMP,
    create_time   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_user_username ON sys_user (username);

-- ---------------------------------------------------------------------
-- Table 22: sys_approval_request - 审批单（方向 D：L3 人机协同审批）
-- ---------------------------------------------------------------------
-- 对齐蓝图 §二：P0/P1 高危故障必须人工确认后 AI 才可执行敏感操作。
-- payload 存可重放的动作上下文——批准时据此执行；不存则批准后无从执行。
-- APPROVED 与 EXECUTED 分开：批准后执行可能失败，须区分二者（既成事实固化）。
-- risk_level 复用 ToolRiskLevel 枚举，不新建 ActionPermissionLevel（避免同一事实两处定义）。
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sys_approval_request (
    id              BIGSERIAL PRIMARY KEY,
    action_type     VARCHAR(32)  NOT NULL,        -- CREATE_TICKET / EXECUTE_SCRIPT（预留）
    tool_name       VARCHAR(64),
    risk_level      VARCHAR(32)  NOT NULL,        -- READ_ONLY/DRAFT/CONTROLLED_WRITE/HIGH_RISK_EXECUTION
    summary         VARCHAR(255) NOT NULL,
    payload         JSONB,                        -- 可重放的动作上下文
    requester       VARCHAR(64)  NOT NULL DEFAULT 'AI',
    trace_id        VARCHAR(64),
    session_id      VARCHAR(64),
    status          VARCHAR(24)  NOT NULL DEFAULT 'PENDING',  -- PENDING/APPROVED/REJECTED/EXPIRED/EXECUTED/EXECUTE_FAILED
    approver        VARCHAR(64),
    decided_at      TIMESTAMP,
    decision_reason VARCHAR(500),
    expires_at      TIMESTAMP,
    executed_at     TIMESTAMP,
    execute_result  TEXT,
    create_time     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_approval_status_time ON sys_approval_request (status, create_time);
CREATE INDEX IF NOT EXISTS idx_approval_trace ON sys_approval_request (trace_id);
CREATE INDEX IF NOT EXISTS idx_approval_pending_expire
    ON sys_approval_request (expires_at) WHERE status = 'PENDING';

-- ---------------------------------------------------------------------
-- Table 23: sys_operation_audit - 通用写操作审计（v25）
-- ---------------------------------------------------------------------
-- 补齐漂移：v25 迁移脚本已提交，但当时漏了同步到 init.sql，
-- 导致「全新环境按 init.sql 建库」会缺这张表。
-- 后果不显眼但要紧：审计写入失败已被 catch，业务照常，只是**悄悄没有审计记录**——
-- 到 L3/L4 阶段这是合规问题，而且发现时往往已经需要查历史了。
--
-- action 用语言无关标识符（knowledge.doc.delete）而非中文描述：
-- 中文会随文案调整而变，无法用于统计与告警规则。
-- request_digest 只存摘要不存全文：审计表权限较宽，是最不该存敏感信息的地方。
-- 不设外键到 sys_user：审计必须比用户活得久，否则删号即销毁证据。
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sys_operation_audit (
    id              BIGSERIAL PRIMARY KEY,
    trace_id        VARCHAR(64),
    actor_id        VARCHAR(64),                  -- SYSTEM 表示定时任务/AI 自动执行
    actor_name      VARCHAR(64),
    action          VARCHAR(64)  NOT NULL,        -- 语言无关标识，如 ticket.approve
    target_type     VARCHAR(32),
    target_id       VARCHAR(64),
    http_method     VARCHAR(8),
    http_path       VARCHAR(255),
    status_code     INT,
    success         BOOLEAN      NOT NULL DEFAULT TRUE,  -- HTTP 200 但 code!=0 仍算失败
    biz_code        INT,
    request_digest  VARCHAR(512),                 -- 脱敏 + 截断，禁止存全文
    error_message   VARCHAR(512),
    client_ip       VARCHAR(45),                  -- IPv6 最长 45 字符
    user_agent      VARCHAR(255),
    duration_ms     INT,
    create_time     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_audit_create_time ON sys_operation_audit (create_time DESC);
CREATE INDEX IF NOT EXISTS idx_audit_actor_time  ON sys_operation_audit (actor_id, create_time DESC);
CREATE INDEX IF NOT EXISTS idx_audit_target      ON sys_operation_audit (target_type, target_id);
CREATE INDEX IF NOT EXISTS idx_audit_trace       ON sys_operation_audit (trace_id);
-- 部分索引：失败是少数，索引体积远小于全表索引
CREATE INDEX IF NOT EXISTS idx_audit_failures
    ON sys_operation_audit (create_time DESC) WHERE success = FALSE;

-- ---------------------------------------------------------------------
-- Table 24: sys_risk_policy - 风险等级策略（v26，L3）
-- ---------------------------------------------------------------------
-- 四行固定记录，主键对应 ToolRiskLevel 枚举名。**不可增删只可改**：
-- 等级由 Java 枚举定义，引擎只会产出这四个值之一；允许新建第五级，
-- 它永远不会被命中——页面上看着有、实际是死配置，比没有更糟。
--
-- 存在的理由：这些约束此前散落在 @ToolMeta 注解与若干 if 里，
-- 调整必须改代码 + 重新构建 + 重启。但安全边界的调整往往发生在故障当下
-- （「先把自动重启关掉」），那时没人能等一次发布。
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sys_risk_policy (
    risk_level          VARCHAR(32)  PRIMARY KEY,  -- 对应 ToolRiskLevel 枚举名
    display_name        VARCHAR(64)  NOT NULL,
    description         VARCHAR(255),
    approval_mode       VARCHAR(16)  NOT NULL DEFAULT 'SINGLE',  -- NONE/SINGLE/DUAL（四眼原则）
    approval_timeout_minutes INT     NOT NULL DEFAULT 30,
    auto_execute_allowed BOOLEAN     NOT NULL DEFAULT FALSE,
    -- 爆炸半径双上限，取较小值：只配百分比会让大集群一次挂太多，
    -- 只配绝对值又会让小集群过于保守
    max_blast_radius_percent INT     NOT NULL DEFAULT 5,
    max_blast_radius_count   INT     NOT NULL DEFAULT 1,
    cooldown_seconds    INT          NOT NULL DEFAULT 60,   -- 蓝图 §三 的「等 60 秒校验心跳」
    max_retries         INT          NOT NULL DEFAULT 0,
    escalate_after_minutes   INT     NOT NULL DEFAULT 15,
    escalate_target     VARCHAR(16)  NOT NULL DEFAULT 'TICKET',  -- NONE/TICKET/ONCALL
    allowed_environments VARCHAR(128) NOT NULL DEFAULT 'dev',    -- 逗号分隔；空串=不允许任何环境
    version             INT          NOT NULL DEFAULT 0,   -- 乐观锁，防安全策略被静默覆盖
    updated_by          VARCHAR(64),
    create_time         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 种子数据：默认刻意保守——新部署的系统应当「几乎什么都不自动做」，
-- 由运维团队按自己的风险偏好逐步放开，而不是反过来。
INSERT INTO sys_risk_policy (
    risk_level, display_name, description,
    approval_mode, approval_timeout_minutes,
    auto_execute_allowed, max_blast_radius_percent, max_blast_radius_count,
    cooldown_seconds, max_retries,
    escalate_after_minutes, escalate_target, allowed_environments
) VALUES
    ('READ_ONLY', '只读查询', '无副作用，可安全重试降级',
     'NONE', 30, TRUE, 100, 9999, 0, 3, 0, 'NONE', 'prod,staging,dev'),
    ('DRAFT', '草稿生成', '不直接改状态，输出供人工审核',
     'NONE', 30, TRUE, 100, 9999, 0, 2, 0, 'NONE', 'prod,staging,dev'),
    ('CONTROLLED_WRITE', '受控写操作', '有副作用但可控，需幂等补偿',
     'SINGLE', 30, FALSE, 20, 5, 60, 1, 15, 'TICKET', 'staging,dev'),
    ('HIGH_RISK_EXECUTION', '高风险执行', '不可逆或难逆，必须审批人工确认',
     'DUAL', 15, FALSE, 5, 1, 300, 0, 10, 'ONCALL', 'dev')
ON CONFLICT (risk_level) DO NOTHING;

-- ---------------------------------------------------------------------
-- Table 25: sys_action_allowlist - 动作白名单（v26，L3）
-- ---------------------------------------------------------------------
-- **允许清单，不是禁止清单**：表里没有记录 = 不允许自动执行。
-- 默认拒绝是安全配置的唯一正确默认值——漏配一条动作的后果应当是
-- 「这个动作没自动跑」，而不是「它不受任何约束地跑了」。
--
-- 不建外键到 sys_risk_policy：引用完整性由 Service 层对着 Java 枚举校验，
-- 比 DB 外键更严（DB 只能保证「策略表里有这行」，Service 能保证「枚举里有这个值」）。
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sys_action_allowlist (
    id                  BIGSERIAL    PRIMARY KEY,
    action_key          VARCHAR(64)  NOT NULL,     -- 如 k8s.pod.restart，全局唯一
    display_name        VARCHAR(64)  NOT NULL,
    description         VARCHAR(255),
    category            VARCHAR(32)  NOT NULL DEFAULT 'k8s',  -- k8s/host/cloud/database/script/notify
    risk_level          VARCHAR(32)  NOT NULL,     -- 逻辑关联 sys_risk_policy.risk_level
    target_pattern      VARCHAR(255),              -- 如 ns:prod/deploy:*；写操作必填
    environments        VARCHAR(128) NOT NULL DEFAULT 'dev',   -- 与风险策略取交集
    param_schema        JSONB,                     -- 执行前校验模型给出的参数
    requires_approval   BOOLEAN,                   -- NULL=跟随策略；只能收紧不能放宽
    max_blast_radius_count INT,                    -- NULL=跟随策略
    enabled             BOOLEAN      NOT NULL DEFAULT FALSE,   -- 停用而非删除（审计引用 action_key）
    version             INT          NOT NULL DEFAULT 0,
    created_by          VARCHAR(64),
    updated_by          VARCHAR(64),
    create_time         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
-- 唯一：同一动作两条配置时引擎该信哪条？让 DB 直接拒绝，
-- 而不是靠应用层「取第一条」这种隐式规则
CREATE UNIQUE INDEX IF NOT EXISTS uk_action_allowlist_key
    ON sys_action_allowlist (action_key);
CREATE INDEX IF NOT EXISTS idx_action_allowlist_category
    ON sys_action_allowlist (category, risk_level);
-- 引擎热路径的部分索引：从不查停用条目，索引体积可小一半
CREATE INDEX IF NOT EXISTS idx_action_allowlist_enabled
    ON sys_action_allowlist (action_key) WHERE enabled = TRUE;

-- 种子数据：蓝图 §二/§三 点名的典型动作。
-- 写操作一律 enabled=FALSE——装好就能自动重启生产 Pod 是不可接受的默认值。
INSERT INTO sys_action_allowlist (
    action_key, display_name, description, category, risk_level,
    target_pattern, environments, param_schema, enabled
) VALUES
    ('k8s.pod.describe', '查看 Pod 详情', '读取 Pod 配置、事件与状态，用于诊断上下文补充',
     'k8s', 'READ_ONLY', '*', 'prod,staging,dev',
     '{"namespace":{"type":"string","required":true}}'::jsonb, TRUE),
    ('k8s.logs.tail', '拉取容器日志', '读取最近 N 行容器日志，用于 RCA 归因',
     'k8s', 'READ_ONLY', '*', 'prod,staging,dev',
     '{"lines":{"type":"int","max":1000,"default":200}}'::jsonb, TRUE),
    ('k8s.pod.restart', '优雅重启 Pod', '对应蓝图 P2/P3 场景的 rollout restart，需受爆炸半径约束',
     'k8s', 'CONTROLLED_WRITE', 'ns:staging/*', 'staging,dev',
     '{"gracePeriodSeconds":{"type":"int","max":120,"default":30}}'::jsonb, FALSE),
    ('k8s.deploy.scale', '调整副本数', '扩缩容。缩容可能引发容量不足，受 max 参数约束',
     'k8s', 'CONTROLLED_WRITE', 'ns:staging/*', 'staging,dev',
     '{"replicas":{"type":"int","min":1,"max":10}}'::jsonb, FALSE),
    ('host.log.rotate', '日志空间回收', '蓝图 P4 场景：logrotate 释放磁盘，无业务影响',
     'host', 'CONTROLLED_WRITE', '*', 'staging,dev',
     '{"olderThanDays":{"type":"int","min":1,"default":7}}'::jsonb, FALSE),
    ('host.docker.prune', '清理废弃镜像', '蓝图 P4 场景：docker system prune 回收宿主机磁盘',
     'host', 'CONTROLLED_WRITE', '*', 'dev',
     '{"includeVolumes":{"type":"bool","default":false}}'::jsonb, FALSE),
    ('k8s.rollout.undo', '回滚发布', '蓝图 §三 的自愈回滚触发器，影响面覆盖整个 Deployment',
     'k8s', 'HIGH_RISK_EXECUTION', 'ns:staging/*', 'dev',
     '{"toRevision":{"type":"int"}}'::jsonb, FALSE),
    ('db.connection.kill', '终止数据库连接', '主库连接池打满时终止长事务，误杀会导致业务报错',
     'database', 'HIGH_RISK_EXECUTION', '*', 'dev',
     '{"minDurationSeconds":{"type":"int","min":60}}'::jsonb, FALSE),
    ('cloud.securitygroup.block', '封禁攻击源 IP', 'SecOps 场景：写入安全组黑名单，误封会切断正常访问',
     'cloud', 'HIGH_RISK_EXECUTION', '*', 'dev',
     '{"durationHours":{"type":"int","max":24,"default":24}}'::jsonb, FALSE)
ON CONFLICT (action_key) DO NOTHING;

-- =====================================================================
-- Data Migration: v10 孤儿切片清理
-- =====================================================================
-- 背景：/ingest 端点（已废弃）写入的切片 doc_id=NULL，无法被生命周期治理。
-- 策略：按 doc_title 关联 sys_knowledge_doc (status=PUBLISHED)，未匹配则删除。
-- 幂等性：WHERE doc_id IS NULL 确保可重复执行。
-- =====================================================================

DO $$
DECLARE
    orphan_count INTEGER;
    matched_count INTEGER;
    deleted_count INTEGER;
BEGIN
    -- 统计执行前孤儿数量
    SELECT COUNT(*) INTO orphan_count FROM sys_knowledge_chunk WHERE doc_id IS NULL;
    IF orphan_count > 0 THEN
        RAISE NOTICE '[v10] 执行前孤儿切片数量: %', orphan_count;

        -- Step 1: 按 doc_title 关联现有 PUBLISHED 文档
        UPDATE sys_knowledge_chunk c
        SET doc_id = d.id
        FROM sys_knowledge_doc d
        WHERE c.doc_id IS NULL
          AND c.doc_title = d.title
          AND d.status = 'PUBLISHED';
        GET DIAGNOSTICS matched_count = ROW_COUNT;
        RAISE NOTICE '[v10] 按 doc_title 关联成功 % 条切片', matched_count;

        -- Step 2: 删除仍未关联的孤儿（doc_title 在库中不存在或文档已废弃）
        DELETE FROM sys_knowledge_chunk WHERE doc_id IS NULL;
        GET DIAGNOSTICS deleted_count = ROW_COUNT;
        RAISE NOTICE '[v10] 删除未匹配孤儿切片 % 条', deleted_count;

        -- 最终验证
        SELECT COUNT(*) INTO orphan_count FROM sys_knowledge_chunk WHERE doc_id IS NULL;
        IF orphan_count = 0 THEN
            RAISE NOTICE '[v10] ✓ 孤儿切片已全部清理完成';
        ELSE
            RAISE WARNING '[v10] 迁移后仍有 % 条孤儿切片残留，请检查', orphan_count;
        END IF;
    ELSE
        RAISE NOTICE '[v10] 无孤儿切片需要清理';
    END IF;
END $$;

-- =====================================================================
-- 对账查询（定期运行，检查是否有新孤儿产生）
-- =====================================================================
-- 预期结果：v10 后应恒为 0 行；若再次出现则说明某处代码仍在产生孤儿。
--
-- 1. 统计孤儿切片数量：
--    SELECT COUNT(*) AS orphan_count, COUNT(DISTINCT doc_title) AS affected_doc_titles
--    FROM sys_knowledge_chunk WHERE doc_id IS NULL;
--
-- 2. 列出孤儿切片详情（按文档标题分组）：
--    SELECT doc_title, COUNT(*) AS chunk_count, MIN(created_at), MAX(created_at)
--    FROM sys_knowledge_chunk WHERE doc_id IS NULL GROUP BY doc_title;
--
-- 3. 检查孤儿是否有可关联的文档（结果 > 0 说明可重新运行上述 UPDATE）：
--    SELECT c.doc_title, COUNT(c.id) AS orphan_chunks, d.id AS matching_doc_id, d.status
--    FROM sys_knowledge_chunk c
--    LEFT JOIN sys_knowledge_doc d ON c.doc_title = d.title AND d.status = 'PUBLISHED'
--    WHERE c.doc_id IS NULL GROUP BY c.doc_title, d.id, d.status;
--
-- 4. 反向检查：已关联切片的 doc_id 是否仍指向存在的文档（防止文档被删但切片残留）：
--    SELECT c.doc_id, COUNT(c.id) AS dangling_chunks
--    FROM sys_knowledge_chunk c LEFT JOIN sys_knowledge_doc d ON c.doc_id = d.id
--    WHERE c.doc_id IS NOT NULL AND d.id IS NULL GROUP BY c.doc_id;
-- =====================================================================
