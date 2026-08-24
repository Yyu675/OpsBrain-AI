-- =====================================================================
-- v27: L3 自动化策略（告警 → 动作 的匹配规则）
-- =====================================================================
-- 背景
-- ---------------------------------------------------------------------
-- v26 落了两块基础：
--   sys_risk_policy      —— 每个风险等级怎么管（审批、爆炸半径、升级）
--   sys_action_allowlist —— 允许引擎调用哪些动作
--
-- 但两者之间缺一环：**什么情况下该调哪个动作**。
-- 现在的链路是「告警进来 → 自动建单 → 人来看」，中间没有任何
-- 「P3 级别的 Pod 崩溃，可以先自动重启试试」这样的规则表达能力。
--
-- 本表就是那一环。对齐蓝图 §二的分级决策表：
--   P0/P1 → 人机协同（策略可匹配，但动作必然需要审批）
--   P2/P3 → 半自动自愈（策略匹配后按风险策略决定是否直接执行）
--   P4    → 全自治（免审批动作可直接执行）
--
-- 关键设计取舍
-- ---------------------------------------------------------------------
-- 1) 【策略只引用 action_key，不内联动作定义】
--    策略说「匹配上了就执行 k8s.pod.restart」，至于这个动作能不能执行、
--    要不要审批、爆炸半径多大，全部由 v26 两张表回答。
--    若在策略里再存一份动作参数，就会出现「策略说能跑、白名单说不能跑」
--    这种自相矛盾的状态，而运维无法判断该信哪个。
--    单一真相：**能不能做看白名单，什么时候做看策略。**
--
-- 2) 【dry_run 是一等公民，且新建策略默认开启】
--    自动化最危险的时刻是「刚配好、还没人知道它会匹配到什么」。
--    dry_run 模式下策略照常匹配、照常记录「我本来会做什么」，但不执行。
--    运维观察几天，确认匹配范围符合预期后再关掉 dry_run。
--    这是业界推荐的自动化上线方式，不是可选的锦上添花——
--    直接上线的策略一旦匹配范围写宽了，第一次触发就是事故。
--
-- 3) 【priority + stop_on_match：显式的求值顺序】
--    多条策略可能同时匹配一个告警。不定义顺序就会依赖数据库返回顺序，
--    那是不确定的——同一个告警两次触发可能走不同分支，无法复现也无法排查。
--    priority 越小越先求值；stop_on_match=TRUE 时命中即停。
--
-- 4) 【匹配条件留空 = 不限制，而非不匹配】
--    与白名单的「无记录=拒绝」相反，这里留空表示通配。
--    因为策略是「主动声明我要管什么」，条件越少覆盖越广，符合直觉；
--    而白名单是「授权清单」，未授权必须拒绝。两者语义方向不同是刻意的。
--    但**不允许所有条件同时为空**（见 Service 层校验）——
--    那等于「对所有告警执行这个动作」，几乎必然是配错了。
--
-- 5) 【冷却期 cooldown_minutes：防自动化风暴】
--    同一策略对同一目标在冷却期内只执行一次。
--    没有它，一个反复 firing 的告警会让策略每次都触发重启，
--    形成「重启→还没起来→又告警→又重启」的死循环，
--    比不自动化更糟。
--
-- 幂等性：CREATE TABLE / CREATE INDEX / INSERT 均可重复执行。
-- =====================================================================

CREATE TABLE IF NOT EXISTS sys_automation_policy (
    id                  BIGSERIAL    PRIMARY KEY,

    -- 人可读的规则名，如「P3 Pod 崩溃自动重启」
    name                VARCHAR(64)  NOT NULL,
    description         VARCHAR(255),

    -- ---- 匹配条件（全部留空=通配，但不允许全空，见 Service 校验）----
    -- 告警级别，逗号分隔，如 'P2,P3'。对应 sys_alert.level
    match_alert_levels  VARCHAR(64),
    -- 业务模块，对应 sys_alert.module（K8S/MYSQL/NETWORK/...）
    match_module        VARCHAR(32),
    -- 服务名匹配模式，支持 * 通配，如 'order-*'。对应 sys_alert.service
    match_service_pattern VARCHAR(128),
    -- 告警规则名匹配模式，如 'PodCrashLoopBackOff'。对应 sys_alert.alert_name
    match_alert_name_pattern VARCHAR(128),

    -- ---- 命中后做什么 ----
    -- 引用 sys_action_allowlist.action_key。不建外键：与 v26 同理，
    -- 引用完整性由 Service 层校验（能同时校验「存在」与「已启用」，DB 外键只能查前者）
    action_key          VARCHAR(64)  NOT NULL,
    -- 传给动作的参数（JSONB），须满足白名单条目的 param_schema
    action_params       JSONB,

    -- 生效环境。必须是所引用动作允许环境的子集（Service 校验）
    environment         VARCHAR(16)  NOT NULL DEFAULT 'dev',

    -- ---- 执行控制 ----
    -- 求值顺序，越小越先。同值时按 id 兜底保证确定性
    priority            INT          NOT NULL DEFAULT 100,
    -- 命中后是否停止求值后续策略
    stop_on_match       BOOLEAN      NOT NULL DEFAULT TRUE,
    -- 冷却期：同策略对同目标在此期间内不重复执行，防自动化风暴
    cooldown_minutes    INT          NOT NULL DEFAULT 30,
    -- 单次触发最多重试几次（超出则按风险策略升级）
    max_executions_per_day INT       NOT NULL DEFAULT 10,

    -- ---- 安全开关 ----
    -- 演练模式：照常匹配与记录，但不真正执行。新建策略默认 TRUE
    dry_run             BOOLEAN      NOT NULL DEFAULT TRUE,
    enabled             BOOLEAN      NOT NULL DEFAULT FALSE,

    version             INT          NOT NULL DEFAULT 0,
    created_by          VARCHAR(64),
    updated_by          VARCHAR(64),
    create_time         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 策略名唯一：同名策略会让日志里「策略 X 已触发」无法定位是哪一条
CREATE UNIQUE INDEX IF NOT EXISTS uk_automation_policy_name
    ON sys_automation_policy (name);

-- 引擎热路径：按 priority 顺序取启用的策略。
-- 部分索引只覆盖启用项——引擎从不求值停用策略
CREATE INDEX IF NOT EXISTS idx_automation_policy_eval
    ON sys_automation_policy (priority, id) WHERE enabled = TRUE;

-- 反查：「这个动作被哪些策略引用」。停用动作前必须能查到影响面
CREATE INDEX IF NOT EXISTS idx_automation_policy_action
    ON sys_automation_policy (action_key);

COMMENT ON TABLE sys_automation_policy IS
    'L3 自动化策略：告警匹配条件 → 白名单动作。能不能做看白名单，什么时候做看本表';
COMMENT ON COLUMN sys_automation_policy.dry_run IS
    '演练模式。新建默认 TRUE——直接上线的策略若匹配范围写宽了，第一次触发就是事故';
COMMENT ON COLUMN sys_automation_policy.cooldown_minutes IS
    '冷却期，防「重启→没起来→又告警→又重启」的自动化风暴';
COMMENT ON COLUMN sys_automation_policy.priority IS
    '求值顺序，越小越先。不定义顺序会依赖 DB 返回顺序，同一告警两次触发可能走不同分支';

-- ---------------------------------------------------------------------
-- 种子数据：对齐蓝图 §二 三档故障的典型策略
-- ---------------------------------------------------------------------
-- 全部 enabled=FALSE + dry_run=TRUE —— 装好就自动重启生产 Pod 是
-- 不可接受的默认值。运维需先启用、观察演练日志、再关掉 dry_run。
INSERT INTO sys_automation_policy (
    name, description,
    match_alert_levels, match_module, match_service_pattern, match_alert_name_pattern,
    action_key, action_params, environment,
    priority, stop_on_match, cooldown_minutes, dry_run, enabled
) VALUES
    ('P4 磁盘告警自动回收日志',
     '蓝图 P4 全自治场景：/var/log 占用超阈值时自动 logrotate，不惊动人',
     'P4', 'OTHER', '*', 'DiskSpaceLow',
     'host.log.rotate', '{"olderThanDays":7}'::jsonb, 'dev',
     10, TRUE, 60, TRUE, FALSE),

    ('P3 Pod 崩溃自动重启',
     '蓝图 P2/P3 半自动自愈：CrashLoopBackOff 时优雅重启，受爆炸半径约束',
     'P3', 'K8S', '*', 'PodCrashLoopBackOff',
     'k8s.pod.restart', '{"gracePeriodSeconds":30}'::jsonb, 'staging',
     20, TRUE, 30, TRUE, FALSE),

    ('P0/P1 数据库连接池打满',
     '蓝图 P0/P1 人机协同：仅提议终止长事务，必须人工审批后才执行',
     'P0,P1', 'MYSQL', '*', 'DBConnectionPoolExhausted',
     'db.connection.kill', '{"minDurationSeconds":300}'::jsonb, 'dev',
     5, TRUE, 15, TRUE, FALSE)
ON CONFLICT (name) DO NOTHING;
