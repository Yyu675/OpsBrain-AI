-- =====================================================================
-- S1-2 变更取证：sys_change_event 变更事件注册表（路线图 §5.3）
-- =====================================================================
-- 设计依据（防漂移，与 PROGRESS 决策表一致）：
-- * 「变更关联是根因定位性价比最高的单一方向」（路线图原文）；
--   生产故障大部分与近期变更相关——先建事件注册表 + CI 回调写入端点，
--   比事后翻 CI 系统 API 更稳（CI 系统认证/限流/格式都是额外故障面）。
-- * UNIQUE (source, external_id)：CI/CD 流水线重发/重试不重复入库，
--   写入天然幂等，与台账「injection-restore 唯一性」同一品味。
-- * change_time 与 reported_at 分离：上报延迟本身是可观测信号
--   （延迟过大的变更事件，取证时要打折）。
-- * 列级口径与 V1 baseline 对齐（BIGSERIAL/VARCHAR/TIMESTAMP，不上 TZ——
--   全库现状如此，单表引入 timestamptz 只会让对比 SQL 更绕）。
-- =====================================================================
CREATE TABLE IF NOT EXISTS sys_change_event (
    id           BIGSERIAL PRIMARY KEY,
    service_name VARCHAR(128) NOT NULL,                 -- 受影响服务名（工具层已有白名单字符集）
    change_type  VARCHAR(32)  NOT NULL,                 -- deploy / config / scale / rollback / ...
    operator     VARCHAR(64)  NOT NULL DEFAULT 'unknown',
    summary      VARCHAR(2000) NOT NULL,                -- 变更内容摘要（写入端截断，不撑爆上下文）
    change_time  TIMESTAMP    NOT NULL,                 -- 变更实际发生时刻
    reported_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,  -- 入库时刻
    source       VARCHAR(64)  NOT NULL DEFAULT 'api',   -- ci-callback / manual / ...
    external_id  VARCHAR(191),                          -- 外部系统事件 ID（幂等键，可空=手工录入）
    CONSTRAINT uk_change_source_external UNIQUE (source, external_id)
);
CREATE INDEX IF NOT EXISTS idx_change_service_time ON sys_change_event (service_name, change_time);
CREATE INDEX IF NOT EXISTS idx_change_time         ON sys_change_event (change_time);
