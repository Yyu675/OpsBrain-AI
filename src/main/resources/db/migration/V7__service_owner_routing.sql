-- V7: 服务 → 值班负责人路由表
--
-- 背景（2026-09-25）：真实库里 27/28 张工单负责人为「待分配」——告警自动建单
-- 恒传 assignee=null，建出来的单子没有任何路由把它交到人手里，
-- 首响超时扫描（FirstResponseBreachScheduler）只能标记，找不到该通知谁。
-- 本表提供最小路由能力：告警建单时按服务名查负责人，命中则直接指派；
-- 未配置的服务保持「待分配」原行为，由人工在工单详情页认领/转派。
--
-- 唯一键按 lower(service) 且仅对启用行生效：服务名大小写在各告警源里
-- 写法不一（order-service / Order-Service），停用行不占用唯一名额，
-- 允许同名服务重建路由而不必先物理删除。
CREATE TABLE sys_service_owner (
    id          BIGSERIAL PRIMARY KEY,
    service     VARCHAR(128) NOT NULL,
    owner       VARCHAR(64)  NOT NULL,
    enabled     BOOLEAN      NOT NULL DEFAULT TRUE,
    create_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX uk_service_owner_service
    ON sys_service_owner (lower(service)) WHERE enabled = TRUE;

COMMENT ON TABLE sys_service_owner IS
    '服务→值班负责人路由表：告警自动建单时按服务名指派负责人（2026-09-25，工单待分配积压治理）';
COMMENT ON COLUMN sys_service_owner.owner IS
    '负责人姓名，需与 sys_team_member.name 一致；建单时不校验成员存在性——成员停用与路由停用是两件独立的事';
