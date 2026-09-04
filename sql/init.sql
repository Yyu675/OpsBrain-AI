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
    -- C1 可见性（冗余自 sys_knowledge_doc，供检索 SQL 免 JOIN 过滤）。
    -- 为什么冗余：检索走 HNSW 向量索引，若权限字段只在 doc 表，
    -- 检索 SQL 必须 JOIN，而带 JOIN 的 ORDER BY embedding <=> ? 会让 PG
    -- 放弃 HNSW 走全表扫描——几十万切片下从毫秒退化到秒级。
    visibility       VARCHAR(16) DEFAULT 'PUBLIC'  NOT NULL,  -- PUBLIC/INTERNAL/RESTRICTED
    owner_dept       VARCHAR(64),                             -- RESTRICTED 时的归属部门
    create_time    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    -- 取值约束：防止应用层写入拼写错误的档位（如 'Public'/'PRIVATE'），
    -- 那会让该切片在所有权限比较中落到未知分支，行为不可预测
    CONSTRAINT ck_chunk_visibility CHECK (visibility IN ('PUBLIC', 'INTERNAL', 'RESTRICTED'))
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

-- C1 权限过滤索引：检索热路径的过滤条件是 (status, visibility)
CREATE INDEX IF NOT EXISTS idx_chunk_status_visibility
    ON sys_knowledge_chunk (status, visibility);
-- 部分索引只覆盖受限切片，体积远小于全表索引（绝大多数是 PUBLIC）
CREATE INDEX IF NOT EXISTS idx_chunk_owner_dept
    ON sys_knowledge_chunk (owner_dept)
    WHERE visibility = 'RESTRICTED';

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

-- ---------------------------------------------------------------------
-- Table 6.1: sys_agent_conversation_turn - 对话原文（B-2 冷归档补全）
-- ---------------------------------------------------------------------
-- 为什么需要这张表
--   三层记忆里，热层（Redis）存对话原文但 TTL 仅 120 分钟，
--   而冷归档按天执行——归档时原文早已过期。于是冷归档只能存摘要，
--   并在 JSON 里用 contentScope=SUMMARY_ONLY 如实标注。
--   合规审计与模型评测取数都需要逐轮原文，摘要不够。
--
-- 为什么不延长 Redis TTL 代替本表
--   对话原文体积远大于摘要（单轮可达数 KB，含日志/堆栈粘贴）。
--   把保留期从 2 小时拉到 90 天，等于把 Redis 当主存储用——
--   内存成本不可接受，且 Redis 无持久化保证时数据仍会丢。
--
-- 写入语义：旁路、幂等、不阻塞主流程
--   由 AgentMemoryManager.recordCompletedTurn 在每轮结束后写入。
--   失败只记日志——用户已经收到回答了，不能因为「存档」失败而报错。
--   (session_id, turn_seq) 唯一：重放或重试不会写重。
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sys_agent_conversation_turn (
    id            BIGSERIAL PRIMARY KEY,
    session_id    VARCHAR(64) NOT NULL,
    trace_id      VARCHAR(64),
    tenant_id     VARCHAR(64) DEFAULT 'default',
    -- 轮次序号：从 1 递增。与 session_id 组成唯一键，保证重试幂等
    turn_seq      INT         NOT NULL,
    user_query    TEXT,
    ai_answer     TEXT,
    -- 工具调用结果（JSON 数组）。评测时需要它区分「模型自己答的」
    -- 与「基于工具返回答的」——只看问答对无法判断
    tool_results  TEXT,
    tokens        INT              DEFAULT 0,
    cost_rmb      DOUBLE PRECISION DEFAULT 0,
    final_state   VARCHAR(32),
    create_time   TIMESTAMP        DEFAULT CURRENT_TIMESTAMP
);
-- 幂等键：同一会话同一轮次只存一条
CREATE UNIQUE INDEX IF NOT EXISTS uk_turn_session_seq
    ON sys_agent_conversation_turn (session_id, turn_seq);
-- 按会话回放：归档与审计都按 session 维度取全量
CREATE INDEX IF NOT EXISTS idx_turn_session
    ON sys_agent_conversation_turn (session_id, turn_seq);
-- 按时间清理：保留期到期后批量删除
CREATE INDEX IF NOT EXISTS idx_turn_create_time
    ON sys_agent_conversation_turn (create_time);
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
    -- C1 可见性三档，越严格数值越大：
    --   PUBLIC     全员可见（默认，兼容存量数据）
    --   INTERNAL   登录用户可见
    --   RESTRICTED 仅 owner_dept 内成员 + ADMIN 可见
    -- 默认 PUBLIC 是刻意的向后兼容：若默认 RESTRICTED，升级后所有历史文档
    -- 对所有人不可见，知识库瞬间「清空」，比权限过宽更像事故。
    visibility       VARCHAR(16)  DEFAULT 'PUBLIC' NOT NULL,
    owner_dept       VARCHAR(64),
    create_time      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_doc_visibility CHECK (visibility IN ('PUBLIC', 'INTERNAL', 'RESTRICTED'))
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
CREATE INDEX IF NOT EXISTS idx_doc_visibility  ON sys_knowledge_doc (visibility);

-- 分类外键：category_id 指向 sys_knowledge_category。
-- 建表时无法内联声明（sys_knowledge_category 在本表之后才创建），
-- 故在分类表建好后用 DO 块补加，保持幂等。
COMMENT ON COLUMN sys_knowledge_doc.visibility IS
    'PUBLIC=全员可见 / INTERNAL=登录可见 / RESTRICTED=仅 owner_dept + ADMIN';
COMMENT ON COLUMN sys_knowledge_chunk.visibility IS
    '冗余自 sys_knowledge_doc，供检索 SQL 免 JOIN 过滤（保住 HNSW 索引）';

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

-- 回填文档的 category_id（把自由文本分类对应到分类表的行）
UPDATE sys_knowledge_doc d
   SET category_id = c.id
  FROM sys_knowledge_category c
 WHERE d.category_id IS NULL
   AND d.category IS NOT NULL
   AND LOWER(TRIM(d.category)) = LOWER(c.name);

-- 文档 → 分类的外键。必须放在分类表创建之后，故用 DO 块补加。
-- pg_constraint 判重使其可重复执行（ADD CONSTRAINT 无 IF NOT EXISTS 语法）。
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'fk_knowledge_doc_category'
    ) THEN
        ALTER TABLE sys_knowledge_doc
            ADD CONSTRAINT fk_knowledge_doc_category
            FOREIGN KEY (category_id) REFERENCES sys_knowledge_category(id);
    END IF;
END $$;

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

-- 兼容旧数据：把文档上已有的自由文本标签收进标签字典。
-- 与上面分类的回填对称——升级前打在文档上的标签若不入字典，
-- 标签管理页会看不到它们，用户会以为标签「丢了」而重新建一遍，
-- 造成同义标签并存（"K8s" 与 "k8s"）。
-- 按 LOWER(TRIM()) 分组归一，取字典序最小的原始写法作展示名。
INSERT INTO sys_knowledge_tag (name, normalized_name)
SELECT s.name, s.normalized_name
  FROM (
        SELECT MIN(TRIM(tag)) AS name, LOWER(TRIM(tag)) AS normalized_name
          FROM sys_knowledge_doc_tag
         WHERE TRIM(tag) <> ''
         GROUP BY LOWER(TRIM(tag))
       ) s
 WHERE NOT EXISTS (
       SELECT 1 FROM sys_knowledge_tag k
        WHERE k.normalized_name = s.normalized_name
   );

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
    dept          VARCHAR(64),                                  -- C1 RESTRICTED 文档的可见性判定依据
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

-- ---------------------------------------------------------------------
-- Table 26: sys_automation_policy - 自动化策略（v27，L3）
-- ---------------------------------------------------------------------
-- 三张表的分工：
--   sys_action_allowlist  —— 能不能做（允许清单）
--   sys_risk_policy       —— 怎么做（审批、爆炸半径、升级）
--   sys_automation_policy —— 什么时候做（本表：告警匹配规则）
--
-- 策略只引用 action_key，不内联动作定义。否则会出现「策略说能跑、
-- 白名单说不能跑」的自相矛盾状态，而运维无法判断该信哪个。
--
-- dry_run 是一等公民且新建默认开启：自动化最危险的时刻是
-- 「刚配好、还没人知道它会匹配到什么」，直接上线的策略若匹配范围写宽了，
-- 第一次触发就是事故。
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sys_automation_policy (
    id                  BIGSERIAL    PRIMARY KEY,

    -- 人可读的规则名，如「P3 Pod 崩溃自动重启」
    name                VARCHAR(64)  NOT NULL,
    description         VARCHAR(255),

    -- ---- 匹配条件（全部留空=通配，但不允许全空，见 Service 校验）----
    -- 告警级别，逗号分隔，如 'P2,P3'。对应 sys_alert.level
    match_alert_levels  VARCHAR(64),
    -- 业务模块，对应 sys_alert.module（K8S/MYSQL/NETWORK/...）
    match_module        VARCHAR(32),
    -- 服务名匹配模式，支持 * 通配，如 'order-*'。对应 sys_alert.service
    match_service_pattern VARCHAR(128),
    -- 告警规则名匹配模式，如 'PodCrashLoopBackOff'。对应 sys_alert.alert_name
    match_alert_name_pattern VARCHAR(128),

    -- ---- 命中后做什么 ----
    -- 引用 sys_action_allowlist.action_key。不建外键：与 v26 同理，
    -- 引用完整性由 Service 层校验（能同时校验「存在」与「已启用」，DB 外键只能查前者）
    action_key          VARCHAR(64)  NOT NULL,
    -- 传给动作的参数（JSONB），须满足白名单条目的 param_schema
    action_params       JSONB,

    -- 生效环境。必须是所引用动作允许环境的子集（Service 校验）
    environment         VARCHAR(16)  NOT NULL DEFAULT 'dev',

    -- ---- 执行控制 ----
    -- 求值顺序，越小越先。同值时按 id 兜底保证确定性
    priority            INT          NOT NULL DEFAULT 100,
    -- 命中后是否停止求值后续策略
    stop_on_match       BOOLEAN      NOT NULL DEFAULT TRUE,
    -- 冷却期：同策略对同目标在此期间内不重复执行，防自动化风暴
    cooldown_minutes    INT          NOT NULL DEFAULT 30,
    -- 单次触发最多重试几次（超出则按风险策略升级）
    max_executions_per_day INT       NOT NULL DEFAULT 10,

    -- ---- 安全开关 ----
    -- 演练模式：照常匹配与记录，但不真正执行。新建策略默认 TRUE
    dry_run             BOOLEAN      NOT NULL DEFAULT TRUE,
    enabled             BOOLEAN      NOT NULL DEFAULT FALSE,

    version             INT          NOT NULL DEFAULT 0,
    created_by          VARCHAR(64),
    updated_by          VARCHAR(64),
    create_time         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 策略名唯一：同名策略会让日志里「策略 X 已触发」无法定位是哪一条
CREATE UNIQUE INDEX IF NOT EXISTS uk_automation_policy_name
    ON sys_automation_policy (name);

-- 引擎热路径：按 priority 顺序取启用的策略。
-- 部分索引只覆盖启用项——引擎从不求值停用策略
CREATE INDEX IF NOT EXISTS idx_automation_policy_eval
    ON sys_automation_policy (priority, id) WHERE enabled = TRUE;

-- 反查：「这个动作被哪些策略引用」。停用动作前必须能查到影响面
CREATE INDEX IF NOT EXISTS idx_automation_policy_action
    ON sys_automation_policy (action_key);

COMMENT ON TABLE sys_automation_policy IS
    'L3 自动化策略：告警匹配条件 → 白名单动作。能不能做看白名单，什么时候做看本表';
COMMENT ON COLUMN sys_automation_policy.dry_run IS
    '演练模式。新建默认 TRUE——直接上线的策略若匹配范围写宽了，第一次触发就是事故';
COMMENT ON COLUMN sys_automation_policy.cooldown_minutes IS
    '冷却期，防「重启→没起来→又告警→又重启」的自动化风暴';
COMMENT ON COLUMN sys_automation_policy.priority IS
    '求值顺序，越小越先。不定义顺序会依赖 DB 返回顺序，同一告警两次触发可能走不同分支';

-- ---------------------------------------------------------------------
-- 种子数据：对齐蓝图 §二 三档故障的典型策略
-- ---------------------------------------------------------------------
-- 全部 enabled=FALSE + dry_run=TRUE —— 装好就自动重启生产 Pod 是
-- 不可接受的默认值。运维需先启用、观察演练日志、再关掉 dry_run。
INSERT INTO sys_automation_policy (
    name, description,
    match_alert_levels, match_module, match_service_pattern, match_alert_name_pattern,
    action_key, action_params, environment,
    priority, stop_on_match, cooldown_minutes, dry_run, enabled
) VALUES
    ('P4 磁盘告警自动回收日志',
     '蓝图 P4 全自治场景：/var/log 占用超阈值时自动 logrotate，不惊动人',
     'P4', 'OTHER', '*', 'DiskSpaceLow',
     'host.log.rotate', '{"olderThanDays":7}'::jsonb, 'dev',
     10, TRUE, 60, TRUE, FALSE),

    ('P3 Pod 崩溃自动重启',
     '蓝图 P2/P3 半自动自愈：CrashLoopBackOff 时优雅重启，受爆炸半径约束',
     'P3', 'K8S', '*', 'PodCrashLoopBackOff',
     'k8s.pod.restart', '{"gracePeriodSeconds":30}'::jsonb, 'staging',
     20, TRUE, 30, TRUE, FALSE),

    ('P0/P1 数据库连接池打满',
     '蓝图 P0/P1 人机协同：仅提议终止长事务，必须人工审批后才执行',
     'P0,P1', 'MYSQL', '*', 'DBConnectionPoolExhausted',
     'db.connection.kill', '{"minDurationSeconds":300}'::jsonb, 'dev',
     5, TRUE, 15, TRUE, FALSE)
ON CONFLICT (name) DO NOTHING;

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
