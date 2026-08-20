BEGIN;

CREATE TABLE IF NOT EXISTS sys_knowledge_category (
    id BIGSERIAL PRIMARY KEY,
    parent_id BIGINT REFERENCES sys_knowledge_category(id),
    name VARCHAR(64) NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_knowledge_category_name
    ON sys_knowledge_category (LOWER(name));
CREATE INDEX IF NOT EXISTS idx_knowledge_category_parent
    ON sys_knowledge_category (parent_id, sort_order, id);

INSERT INTO sys_knowledge_category (name, sort_order)
SELECT DISTINCT TRIM(d.category), 0
  FROM sys_knowledge_doc d
 WHERE d.category IS NOT NULL
   AND TRIM(d.category) <> ''
   AND NOT EXISTS (
       SELECT 1 FROM sys_knowledge_category c
        WHERE LOWER(c.name) = LOWER(TRIM(d.category))
   );

COMMIT;
