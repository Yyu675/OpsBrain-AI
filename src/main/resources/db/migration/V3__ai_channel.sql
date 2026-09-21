-- =============================================================================
-- V3: AI 渠道配置表（阶段A-P0 模型配置可视化）
--
-- 用途：把模型渠道配置（base_url/model/dimension/key）从 application.yml
--       硬编码镜像到 DB，供可视化展示（P0 只读）→ 后续 CRUD/热更新（P1/P2）。
--
-- 安全约束：
--   - api_key_enc  用 AES 加密存储（密钥走 MODEL_KEY_CRYPT_SECRET 环境变量），
--                   明文 key 永不落库、永不进出 API 响应/日志
--   - key_masked   脱敏展示（如 sk-ws-****abcd），API 只回这个
--
-- 幂等：P0 阶段仅作为「启动镜像当前配置」的靶表，写入由服务端 upsert 控制。
-- =============================================================================
CREATE TABLE IF NOT EXISTS sys_ai_channel (
    channel_key      VARCHAR(32) PRIMARY KEY,      -- chat / embedding / reranker
    base_url         VARCHAR(512) NOT NULL,
    api_key_enc      TEXT,                          -- AES 加密后的 key；可为空（无 key/仅占位）
    key_masked       VARCHAR(48),                   -- 脱敏展示，如 sk-ws-****abcd
    turbo_model      VARCHAR(128),                  -- chat 渠道：turbo 模型
    reasoner_model   VARCHAR(128),                  -- chat 渠道：reasoner 模型
    model            VARCHAR(128),                  -- embedding/reranker 渠道：模型名
    dimension        INT,                           -- 向量维度（仅 embedding 有值）
    status           VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    updated_at       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE sys_ai_channel IS 'AI 模型渠道配置（阶段A：可视化 + 热更新靶表）';
