-- =====================================================================
-- v24: 知识库可见性（C1 —— 修复「任意登录用户可检索全部知识」）
-- =====================================================================
-- 背景（审查发现的 P1 缺陷）：
--   sys_knowledge_doc 没有任何可见性/归属字段，HybridRetrieverService
--   的检索 SQL 也不带用户维度过滤。结果是任何登录用户都能检索到全部
--   知识库内容——对「企业私有化知识库」这个定位是硬伤。
--
--   更隐蔽的是：即便前端把某些文档藏起来，用户仍可通过 AI 对话
--   间接套出内容（"帮我查一下核心交易库的主从切换步骤"）。
--   RAG 场景下权限**必须在检索层做**，只在 API 层做等于没做。
--
-- 设计要点
-- ---------------------------------------------------------------------
-- 1) visibility 三档，语义按「越严格数值越大」排列，便于 SQL 比较：
--      PUBLIC(0)     —— 全员可见（默认，兼容存量数据）
--      INTERNAL(1)   —— 登录用户可见（当前与 PUBLIC 等价，为后续访客只读预留）
--      RESTRICTED(2) —— 仅 owner_dept 内成员 + ADMIN 可见
--
-- 2) 为什么把 visibility / owner_dept **冗余到 chunk 表**：
--    检索走 sys_knowledge_chunk 的 HNSW 向量索引。若权限字段只在 doc 表，
--    检索 SQL 必须 JOIN sys_knowledge_doc 才能过滤，而**带 JOIN 的
--    ORDER BY embedding <=> ? 会让 PG 放弃 HNSW 索引走全表扫描**，
--    在几十万切片规模下从毫秒级退化到秒级。
--    冗余的代价是写入时需同步两张表（由 DocumentIndexer 负责，
--    且文档权限变更时需重刷切片——已在 KnowledgeDocService 处理）。
--
-- 3) 存量数据一律置为 PUBLIC：
--    这是刻意的向后兼容。若默认 RESTRICTED，升级后所有历史文档
--    对所有人不可见，知识库瞬间"清空"，比权限过宽更像事故。
--    收紧应由管理员按需逐步进行。
--
-- 幂等性：ADD COLUMN IF NOT EXISTS / CREATE INDEX IF NOT EXISTS。
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. 文档表
-- ---------------------------------------------------------------------
ALTER TABLE sys_knowledge_doc
    ADD COLUMN IF NOT EXISTS visibility VARCHAR(16) NOT NULL DEFAULT 'PUBLIC';

ALTER TABLE sys_knowledge_doc
    ADD COLUMN IF NOT EXISTS owner_dept VARCHAR(64);

-- 取值约束：防止应用层写入拼写错误的档位（如 'Public' / 'PRIVATE'），
-- 那会让该文档在所有权限比较中都落到未知分支，行为不可预测。
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'ck_doc_visibility'
    ) THEN
        ALTER TABLE sys_knowledge_doc
            ADD CONSTRAINT ck_doc_visibility
            CHECK (visibility IN ('PUBLIC', 'INTERNAL', 'RESTRICTED'));
    END IF;
END $$;

-- ---------------------------------------------------------------------
-- 2. 切片表（冗余，供检索层直接过滤，理由见文件头）
-- ---------------------------------------------------------------------
ALTER TABLE sys_knowledge_chunk
    ADD COLUMN IF NOT EXISTS visibility VARCHAR(16) NOT NULL DEFAULT 'PUBLIC';

ALTER TABLE sys_knowledge_chunk
    ADD COLUMN IF NOT EXISTS owner_dept VARCHAR(64);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'ck_chunk_visibility'
    ) THEN
        ALTER TABLE sys_knowledge_chunk
            ADD CONSTRAINT ck_chunk_visibility
            CHECK (visibility IN ('PUBLIC', 'INTERNAL', 'RESTRICTED'));
    END IF;
END $$;

-- ---------------------------------------------------------------------
-- 3. 用户部门（RESTRICTED 判定依据）
-- ---------------------------------------------------------------------
ALTER TABLE sys_user
    ADD COLUMN IF NOT EXISTS dept VARCHAR(64);

-- ---------------------------------------------------------------------
-- 4. 索引
-- ---------------------------------------------------------------------
-- 检索热路径的过滤条件是 (status, visibility)，联合索引避免
-- 在 visibility 上单独扫描。
CREATE INDEX IF NOT EXISTS idx_chunk_status_visibility
    ON sys_knowledge_chunk (status, visibility);

-- RESTRICTED 文档按部门过滤时用到；部分索引只覆盖受限文档，
-- 体积远小于全表索引（绝大多数文档是 PUBLIC）。
CREATE INDEX IF NOT EXISTS idx_chunk_owner_dept
    ON sys_knowledge_chunk (owner_dept)
    WHERE visibility = 'RESTRICTED';

CREATE INDEX IF NOT EXISTS idx_doc_visibility
    ON sys_knowledge_doc (visibility);

-- ---------------------------------------------------------------------
-- 5. 存量数据回填
-- ---------------------------------------------------------------------
-- DEFAULT 'PUBLIC' 只对新行生效，已存在的行由 ADD COLUMN 自动填充默认值
-- （PG 11+ 不重写表）。这里显式兜底处理可能的 NULL，确保不会有行
-- 因 visibility IS NULL 而在权限比较中被意外过滤掉。
UPDATE sys_knowledge_doc   SET visibility = 'PUBLIC' WHERE visibility IS NULL;
UPDATE sys_knowledge_chunk SET visibility = 'PUBLIC' WHERE visibility IS NULL;

-- 切片的可见性以其所属文档为准（历史切片可能与文档不同步）
UPDATE sys_knowledge_chunk c
   SET visibility = d.visibility,
       owner_dept = d.owner_dept
  FROM sys_knowledge_doc d
 WHERE c.doc_id = d.id
   AND (c.visibility IS DISTINCT FROM d.visibility
        OR c.owner_dept IS DISTINCT FROM d.owner_dept);

COMMENT ON COLUMN sys_knowledge_doc.visibility IS
    'PUBLIC=全员可见 / INTERNAL=登录可见 / RESTRICTED=仅 owner_dept + ADMIN';
COMMENT ON COLUMN sys_knowledge_chunk.visibility IS
    '冗余自 sys_knowledge_doc，供检索 SQL 免 JOIN 过滤（保住 HNSW 索引）';
