-- V12: sys_ai_channel 增加 protocol / provider 列（2026-09-29）
--
-- 背景：模型渠道此前把「API 协议」和「供应商」设计成从 baseUrl 硬推断——
-- 这是单一固定思维。用户若用 Azure OpenAI / Anthropic / 自建网关等非
-- OpenAI 兼容协议，URL 里根本没有 compatible-mode 或 /v1 关键词，
-- 推断必错。协议是调用契约（决定请求体结构/认证头），供应商是归属标识，
-- 都应是运维显式配置的事实，不是从 URL 猜出来的。
--
-- protocol 枚举：OPENAI_COMPATIBLE（默认，绝大多数厂商兼容）/ AZURE_OPENAI /
-- ANTHROPIC / CUSTOM。存量行按既有 baseUrl 推断回填（当前都是阿里云
-- DashScope 兼容模式，回填 OPENAI_COMPATIBLE）。

ALTER TABLE sys_ai_channel ADD COLUMN IF NOT EXISTS protocol VARCHAR(32) NOT NULL DEFAULT 'OPENAI_COMPATIBLE';
ALTER TABLE sys_ai_channel ADD COLUMN IF NOT EXISTS provider VARCHAR(64);

COMMENT ON COLUMN sys_ai_channel.protocol IS 'API 协议（调用契约）：OPENAI_COMPATIBLE/AZURE_OPENAI/ANTHROPIC/CUSTOM。显式配置，不从 URL 推断';
COMMENT ON COLUMN sys_ai_channel.provider IS '供应商名称（如 阿里云 assistant/assistant/DeepSeek）。可显式编辑，留空时展示层从 baseUrl 推断兜底';
