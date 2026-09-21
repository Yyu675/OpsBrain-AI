-- =====================================================================
-- V2: 多知识库顶层实体（sys_knowledge_base）+ 按库切片参数 + 上传原件留存
--
-- 背景（对标外部 RAG 设计后的两项借鉴，决策记录见 docs/09-decisions/）：
-- 1) 知识库三层结构：knowledge_base → knowledge_doc → knowledge_chunk。
--    切片参数（父/子段落大小、重叠）挂知识库维度——SOP 长文手册与
--    故障 FAQ 的最优切片粒度不同，全局一套参数必然对其中一类欠优。
-- 2) 二进制文档上传解析入库（Tika）：doc 表留原件出处，供审计与
--    将来重解析。
--
-- kb_id 同步冗余到 chunk 表：与 visibility 冗余同理由——检索走 chunk 的
-- HNSW 向量索引，过滤字段只在 doc 表就必须 JOIN，带 JOIN 的
-- ORDER BY embedding <=> ? 会让 PG 放弃 HNSW 退化为全表扫描
-- （本项目实测过的坑，见 V1 中 chunk.visibility 的注释）。
--
-- 幂等：全部写法可重复执行（IF NOT EXISTS / DO 块 / WHERE kb_id IS NULL），
-- 与 V1 基线同一风格。
-- =====================================================================

-- ---------------------------------------------------------------------
-- Table: sys_knowledge_base - 知识库（切片参数的挂载点）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sys_knowledge_base (
    id                BIGSERIAL PRIMARY KEY,
    name              VARCHAR(64)  NOT NULL,
    -- 业务编码：API 与配置引用用，不因改名漂移；大小写不敏感唯一
    code              VARCHAR(64)  NOT NULL,
    description       VARCHAR(512),
    -- 切片参数三件套：NULL = 跟随全局默认（切片器的 2400/600/100）。
    -- 逐字段独立回落，允许只覆盖其中一项；
    -- 与默认值合并后的完整校验在应用层做（SQL 里引用默认常量会重复真相源）。
    parent_chunk_size INT,
    child_chunk_size  INT,
    chunk_overlap     INT,
    status            VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',   -- ACTIVE/DISABLED
    create_time       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- 参数合法性底线：防「子片比父片大」「重叠比切片大」这类让切片器
    -- 行为不可预测的配置落库。只校验两侧都显式给了值的组合，
    -- 单侧 NULL 的组合由应用层拿默认值代入后校验。
    CONSTRAINT ck_kb_chunk_params CHECK (
        (child_chunk_size IS NULL OR child_chunk_size > 0)
        AND (chunk_overlap IS NULL OR chunk_overlap >= 0)
        AND (parent_chunk_size IS NULL OR child_chunk_size IS NULL
             OR parent_chunk_size > child_chunk_size)
        AND (child_chunk_size IS NULL OR chunk_overlap IS NULL
             OR child_chunk_size > chunk_overlap)
    ),
    CONSTRAINT ck_kb_status CHECK (status IN ('ACTIVE', 'DISABLED'))
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_knowledge_base_code
    ON sys_knowledge_base (LOWER(code));

-- 默认知识库：承接全部存量文档。code='default' 是应用层的回落键，
-- 允许改名，勿删此行勿改 code。
INSERT INTO sys_knowledge_base (name, code, description)
SELECT '默认知识库', 'default', '承接 V2 之前的全部存量文档；切片参数留空即跟随全局默认'
WHERE NOT EXISTS (SELECT 1 FROM sys_knowledge_base WHERE LOWER(code) = 'default');

-- ---------------------------------------------------------------------
-- sys_knowledge_doc：挂知识库 + 上传原件出处
-- ---------------------------------------------------------------------
ALTER TABLE sys_knowledge_doc ADD COLUMN IF NOT EXISTS kb_id BIGINT;
ALTER TABLE sys_knowledge_doc ADD COLUMN IF NOT EXISTS original_filename VARCHAR(255);
ALTER TABLE sys_knowledge_doc ADD COLUMN IF NOT EXISTS original_file_path VARCHAR(512);

-- 存量文档回填默认库（幂等：只动 NULL，重跑不产生二次写入）
UPDATE sys_knowledge_doc
   SET kb_id = (SELECT id FROM sys_knowledge_base WHERE LOWER(code) = 'default')
 WHERE kb_id IS NULL;

-- 外键在回填之后补加（回填依赖目标行已存在），DO 块保持幂等
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_doc_kb') THEN
        ALTER TABLE sys_knowledge_doc
            ADD CONSTRAINT fk_doc_kb FOREIGN KEY (kb_id) REFERENCES sys_knowledge_base(id);
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_doc_kb ON sys_knowledge_doc (kb_id);

-- ---------------------------------------------------------------------
-- sys_knowledge_chunk：kb_id 冗余下沉（免 JOIN 保 HNSW，同 visibility 模式）
-- 代价同 visibility：文档换库后必须重建索引，否则切片上仍是旧库。
-- ---------------------------------------------------------------------
ALTER TABLE sys_knowledge_chunk ADD COLUMN IF NOT EXISTS kb_id BIGINT;

UPDATE sys_knowledge_chunk c
   SET kb_id = d.kb_id
  FROM sys_knowledge_doc d
 WHERE c.doc_id = d.id
   AND c.kb_id IS NULL;

CREATE INDEX IF NOT EXISTS idx_chunk_kb ON sys_knowledge_chunk (kb_id);

COMMENT ON TABLE  sys_knowledge_base IS '知识库顶层实体：切片参数挂载点（字段 NULL=跟随全局默认）';
COMMENT ON COLUMN sys_knowledge_doc.kb_id IS '所属知识库，关联 sys_knowledge_base.id；V2 存量回填默认库';
COMMENT ON COLUMN sys_knowledge_chunk.kb_id IS '冗余自 sys_knowledge_doc，供检索按库过滤免 JOIN（保住 HNSW 索引）';
COMMENT ON COLUMN sys_knowledge_doc.original_file_path IS '上传原件 MinIO 对象键（审计/重解析用），手工录入为 NULL';
