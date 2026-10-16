-- =============================================================================
-- V5: AI 渠道变更历史 + 能力探测结果（2026-09-22）
--
-- 背景：渠道编辑+热更新已闭环（V3/V4），但改错配置只能「重置为 yml 默认」，
--       回不到「上一个自定义值」；能力探测结果目前只在内存，刷新即丢。
--
-- 1) sys_ai_channel_history —— 每次 update/rollback 前把旧行整行快照进来，
--    支持「回滚到任意历史版本」。snapshot 含 key 密文（历史表同样不出 API）。
-- 2) sys_ai_channel_capability —— 能力探测实测结果（对话/流式/工具调用/JSON 等），
--    与权威配置分表：一个是「人配的」，一个是「机器实测的」，职责不混。
--    探测是真实 API 调用（计费+秒级延迟），结果必须落库复用，不能每次打开页面重探。
--
-- 幂等：IF NOT EXISTS，重复执行无副作用。
-- =============================================================================

CREATE TABLE IF NOT EXISTS sys_ai_channel_history (
    id                   BIGSERIAL PRIMARY KEY,
    channel_key          VARCHAR(32)  NOT NULL,
    base_url             VARCHAR(512),
    api_key_enc          TEXT,
    key_masked           VARCHAR(48),
    turbo_model          VARCHAR(128),
    reasoner_model       VARCHAR(128),
    model                VARCHAR(128),
    dimension            INTEGER,
    status               VARCHAR(16),
    fallback_base_url    VARCHAR(512),
    fallback_model       VARCHAR(128),
    fallback_api_key_enc TEXT,
    fallback_key_masked  VARCHAR(48),
    changed_at           TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    changed_by           VARCHAR(64)  NOT NULL DEFAULT 'system',
    change_note          VARCHAR(256)
);

CREATE INDEX IF NOT EXISTS idx_ai_channel_history_key_time
    ON sys_ai_channel_history (channel_key, changed_at DESC);

COMMENT ON TABLE sys_ai_channel_history IS 'AI 渠道配置变更历史（整行快照，回滚依据）；密文不出 API';
COMMENT ON COLUMN sys_ai_channel_history.changed_by IS '操作人（Sa-Token loginId；系统触发=system）';
COMMENT ON COLUMN sys_ai_channel_history.change_note IS '变更说明（如：编辑/回滚到#N/重置默认）';

CREATE TABLE IF NOT EXISTS sys_ai_channel_capability (
    channel_key       VARCHAR(32) PRIMARY KEY,
    capabilities_json TEXT        NOT NULL,
    probed_at         TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE sys_ai_channel_capability IS 'AI 渠道能力实测结果（JSON：{capability: {state, detail}}，三态 SUPPORTED/UNSUPPORTED/UNKNOWN）';
COMMENT ON COLUMN sys_ai_channel_capability.capabilities_json IS '能力探测结果 JSON；UNKNOWN=探测失败（超时/限流），不代表不支持';
