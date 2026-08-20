CREATE TABLE IF NOT EXISTS sys_knowledge_tag (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(64) NOT NULL,
    normalized_name VARCHAR(64) NOT NULL,
    description VARCHAR(255),
    color VARCHAR(16),
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_knowledge_tag_normalized
    ON sys_knowledge_tag (normalized_name);
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
