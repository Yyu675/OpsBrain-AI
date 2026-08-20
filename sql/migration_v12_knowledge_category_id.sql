-- v12: use the managed category row as the document's canonical category relation.
ALTER TABLE sys_knowledge_doc
    ADD COLUMN IF NOT EXISTS category_id BIGINT;

UPDATE sys_knowledge_doc d
   SET category_id = c.id
  FROM sys_knowledge_category c
 WHERE d.category_id IS NULL
   AND d.category IS NOT NULL
   AND LOWER(TRIM(d.category)) = LOWER(c.name);

CREATE INDEX IF NOT EXISTS idx_doc_category_id ON sys_knowledge_doc (category_id);

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
