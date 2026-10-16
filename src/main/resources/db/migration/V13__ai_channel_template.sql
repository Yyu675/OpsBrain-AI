-- V13: 渠道模板库（多渠道一键切换，2026-09-29）
--
-- 背景：对标 ccswitch 的多供应商快速切换。现有结构是 chat/embedding/reranker
-- 三个固定渠道（channel_key 主键），切换供应商要手动改 baseUrl/协议/模型名，
-- 易错且慢。本表预存常见供应商的完整配置模板，渠道卡片一键「从模板切换」
-- 即把模板配置应用进对应渠道——不破坏现有三渠道结构，只做「配置的快速填充」。
--
-- 安全：模板不存 apiKey（密钥不预置），应用模板时保留渠道现有 key 或要求重填。

CREATE TABLE IF NOT EXISTS sys_ai_channel_template (
    id              BIGSERIAL PRIMARY KEY,
    channel_key     VARCHAR(32)  NOT NULL,                 -- 适用渠道：chat/embedding/reranker
    template_name   VARCHAR(64)  NOT NULL,                 -- 模板名（供应商名）
    provider        VARCHAR(64),                           -- 供应商展示名
    base_url        VARCHAR(512) NOT NULL,
    protocol        VARCHAR(32)  NOT NULL DEFAULT 'OPENAI_COMPATIBLE',
    turbo_model     VARCHAR(128),                          -- chat 渠道
    reasoner_model  VARCHAR(128),                          -- chat 渠道
    model           VARCHAR(128),                          -- embedding/reranker 渠道
    dimension       INTEGER,                               -- embedding 向量维度（铁律 1536）
    description     VARCHAR(255),
    sort_order      INTEGER      NOT NULL DEFAULT 100,
    enabled         BOOLEAN      NOT NULL DEFAULT TRUE,
    create_time     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_channel_template UNIQUE (channel_key, template_name)
);

COMMENT ON TABLE sys_ai_channel_template IS 'AI 渠道配置模板库（一键切换供应商）。不存 apiKey——应用模板时保留渠道现有密钥';

-- 预置常见供应商模板（覆盖多云/自建/本地等场景，考虑最坏业务场景）
INSERT INTO sys_ai_channel_template
    (channel_key, template_name, provider, base_url, protocol, turbo_model, reasoner_model, model, dimension, description, sort_order) VALUES
    -- chat 渠道
    ('chat', '阿里云 assistant（通义）', '阿里云 assistant', 'https://dashscope.aliyuncs.com/compatible-mode/v1', 'OPENAI_COMPATIBLE', 'qwen3.7-flash', 'deepseek-v4-flash-0731', NULL, NULL, '阿里云百炼平台，国内访问稳定', 10),
    ('chat', 'DeepSeek 官方', 'DeepSeek', 'https://api.deepseek.com/v1', 'OPENAI_COMPATIBLE', 'deepseek-chat', 'deepseek-reasoner', NULL, NULL, 'DeepSeek 官方 API', 20),
    ('chat', '智谱 GLM', '智谱（GLM）', 'https://open.bigmodel.cn/api/paas/v4', 'OPENAI_COMPATIBLE', 'glm-4-flash', 'glm-4-plus', NULL, NULL, '智谱 AI 开放平台', 30),
    ('chat', 'assistant 官方', 'assistant', 'https://api.openai.com/v1', 'OPENAI_COMPATIBLE', 'gpt-4o-mini', 'gpt-4o', NULL, NULL, 'assistant 官方（需海外网络）', 40),
    ('chat', '本地 Ollama/vLLM', '本地/自建', 'http://localhost:11434/v1', 'OPENAI_COMPATIBLE', 'qwen2.5:7b', 'qwen2.5:7b', NULL, NULL, '本地部署，零 API 成本（需自建模型服务）', 50),
    -- embedding 渠道（维度铁律 1536，模板 dimension 必须 1536）
    ('embedding', '阿里云 assistant text-embedding-v3', '阿里云 assistant', 'https://dashscope.aliyuncs.com/compatible-mode/v1', 'OPENAI_COMPATIBLE', NULL, NULL, 'text-embedding-v3', 1536, '阿里云向量模型', 10),
    ('embedding', 'assistant text-embedding-3-small', 'assistant', 'https://api.openai.com/v1', 'OPENAI_COMPATIBLE', NULL, NULL, 'text-embedding-3-small', 1536, 'assistant 向量模型（需海外网络）', 20)
ON CONFLICT (channel_key, template_name) DO NOTHING;
