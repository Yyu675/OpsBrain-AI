-- 批89（2026-10-06）：通知渠道配置 DB 权威源（方案1「企微+通知配置页」的地基）。
--
-- 语义：**行存在 = DB 权威**（页面保存过即覆盖 yml/env，热生效无需重启）；
-- **无行 = 回落 yml/env**（DINGTALK_WEBHOOK / WECOM_WEBHOOK 等环境变量照旧，
-- 现有部署零迁移成本——刻意不种子行，防止空行遮蔽已有 env 配置）。
-- webhook/secret 走 ApiKeyCrypt（MODEL_KEY_CRYPT_SECRET 派生 AES-256；
-- 主密钥未配时 encrypt 降级明文并 WARN，同模型渠道先例）。
--
-- 消费链：NotifyChannelService.effective() → DingTalkNotifier/WeComNotifier
-- 每次发送时读取（改完即生效，灰度期零重启）→ CompositeNotifier 多渠道分发。
CREATE TABLE sys_notify_channel (
    channel_key VARCHAR(32)  PRIMARY KEY,          -- dingtalk / wecom
    enabled     BOOLEAN      NOT NULL DEFAULT FALSE,
    webhook_enc TEXT         NOT NULL DEFAULT '',  -- enc:v1:...（或降级明文）
    secret_enc  TEXT         NOT NULL DEFAULT '',  -- 钉钉加签密钥；企微当前无签名模式
    updated_by  VARCHAR(64),                       -- 修改人（审计表另有 actor 双保险）
    update_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE sys_notify_channel IS '通知渠道配置（行存在=DB权威覆盖yml/env；webhook/secret经ApiKeyCrypt加密）';
