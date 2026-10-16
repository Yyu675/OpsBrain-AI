-- V8: 效能指标快照表（效能大盘的趋势维度）
--
-- 背景（2026-09-25）：效能大盘的复盘率/压缩比/命中率都是「当前值」——
-- 治理指标的价值在趋势里（在变好还是变坏），没有历史就只能看快照。
-- 本表按天存一行聚合读数，趋势图与周报都从这里取。
--
-- 一天一行（snapshot_date 唯一）：重跑快照是重算覆盖，不是重复追加——
-- 调度失败重试与人工补算都不产生重复行。
CREATE TABLE sys_effectiveness_snapshot (
    id                    BIGSERIAL PRIMARY KEY,
    snapshot_date         DATE        NOT NULL,
    total_tickets         BIGINT      NOT NULL DEFAULT 0,
    finished_tickets      BIGINT      NOT NULL DEFAULT 0,
    postmortem_count      BIGINT      NOT NULL DEFAULT 0,
    alert_sourced_tickets BIGINT      NOT NULL DEFAULT 0,
    alerts_total          BIGINT      NOT NULL DEFAULT 0,
    diagnosis_total       BIGINT      NOT NULL DEFAULT 0,
    diagnosis_sufficient  BIGINT      NOT NULL DEFAULT 0,
    knowledge_evidence    BIGINT      NOT NULL DEFAULT 0,
    knowledge_hits        BIGINT      NOT NULL DEFAULT 0,
    healing_total         BIGINT      NOT NULL DEFAULT 0,
    healing_succeeded     BIGINT      NOT NULL DEFAULT 0,
    created_at            TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_effectiveness_snapshot_date UNIQUE (snapshot_date)
);

COMMENT ON TABLE sys_effectiveness_snapshot IS
    '效能指标每日快照：效能大盘趋势图与复盘治理周报的数据源（2026-09-25）';
