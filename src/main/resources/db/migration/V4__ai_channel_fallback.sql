-- =============================================================================
-- V4: AI 渠道备用模型（方案 A：模型池降级，2026-09-22）
--
-- 用途：chat 渠道支持配置 1 个备用模型（不同厂商/不同端点均可）。
--       主模型超时/限流/宕机（熔断 OPEN）时，FallbackChatModel /
--       FallbackStreamingChatModel 装饰器自动切换到备用模型，对上层透明。
--
-- 约束：
--   - 仅 chat 渠道有意义；embedding 渠道禁用备用（维度铁律 1536 维——
--     换 embedding 模型 = 向量语义空间不兼容 = 全库重建，ModelFingerprintGuard
--     就是为拦截这种静默混用而存在的）
--   - fallback_base_url 与 fallback_model 必须同时有值或同时为空
--   - fallback_api_key_enc 加密规则与主 key 一致（ApiKeyCrypt，enc:v1: 前缀）
--
-- 幂等：IF NOT EXISTS，重复执行无副作用。
-- =============================================================================
ALTER TABLE sys_ai_channel ADD COLUMN IF NOT EXISTS fallback_base_url    VARCHAR(512);
ALTER TABLE sys_ai_channel ADD COLUMN IF NOT EXISTS fallback_model       VARCHAR(128);
ALTER TABLE sys_ai_channel ADD COLUMN IF NOT EXISTS fallback_api_key_enc TEXT;
ALTER TABLE sys_ai_channel ADD COLUMN IF NOT EXISTS fallback_key_masked  VARCHAR(48);

COMMENT ON COLUMN sys_ai_channel.fallback_base_url IS '备用模型端点（仅 chat 渠道；与 fallback_model 同存同空）';
COMMENT ON COLUMN sys_ai_channel.fallback_model IS '备用模型名（主模型熔断时自动切换）';
COMMENT ON COLUMN sys_ai_channel.fallback_api_key_enc IS '备用模型 API Key（AES 加密，规则同 api_key_enc）';
COMMENT ON COLUMN sys_ai_channel.fallback_key_masked IS '备用 Key 脱敏展示（API 只回这个）';
