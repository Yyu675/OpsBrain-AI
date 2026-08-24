-- =====================================================================
-- v26: L3 自动化治理配置（动作白名单 + 风险等级策略）
-- =====================================================================
-- 背景
-- ---------------------------------------------------------------------
-- 蓝图 §三 要求「原子操作白名单枚举」与「爆炸半径控制」两条安全防线，
-- 但当前它们**只存在于代码常量里**：
--   - 风险等级：ToolRiskLevel 四个枚举值，硬编码在 Java 里
--   - 是否需审批：@ToolMeta(requiresApproval = true) 写死在方法注解上
--   - 爆炸半径：完全不存在，蓝图写的「先重启 1/20 个 Pod」无处配置
--
-- 后果是：运维团队想调整「哪些动作允许自动执行、什么风险等级需要几个人审批」
-- 必须改 Java 代码 + 重新构建 + 重启服务。这在 L3「人机协同」阶段不可接受——
-- 安全边界的调整往往发生在故障当下（「先把自动重启关掉」），
-- 那时没人能等一次发布。
--
-- 本迁移把这两类**策略**从代码搬到数据库，使其可在运行时调整。
--
-- 关键设计取舍
-- ---------------------------------------------------------------------
-- 1) 【风险等级不是 CRUD，是四行固定记录】
--    sys_risk_policy 的主键就是 ToolRiskLevel 的枚举名。
--    刻意**不提供新增/删除**：等级由 Java 枚举定义，引擎只会产出这四个值之一。
--    若允许用户新建第五个等级，它永远不会被任何动作命中——
--    页面上看着有、实际是死配置，比没有更糟（用户会以为自己设过了）。
--    可改的是「每级怎么管」，不是「有哪几级」。
--
-- 2) 【白名单是「允许清单」而非「禁止清单」】
--    表里没有记录 = 不允许自动执行，而不是「不限制」。
--    默认拒绝是安全配置的唯一正确默认值：漏配一条动作的后果应当是
--    「这个动作没自动跑」，而不是「这个动作不受任何约束地跑了」。
--
-- 3) 【requires_approval 可覆盖但不可放宽到低于风险策略】
--    白名单条目可以把某个动作**收紧**（本来不用审批的改成要审批），
--    但不能放宽（HIGH_RISK_EXECUTION 的动作不能配成免审批）。
--    校验在 Service 层（ActionAllowlistService.validate），不放 CHECK 约束——
--    因为它依赖另一张表的当前值，DB 层做需要触发器，维护成本更高。
--
-- 4) 【爆炸半径用百分比 + 绝对值双上限】
--    只配百分比：20 个实例的集群配 5% = 1 个，合理；
--                但 1000 个实例的集群 5% = 50 个，一次挂 50 个不可接受。
--    只配绝对值：小集群配 1 个合理，大集群 1 个又太保守失去意义。
--    两者取较小值，兼顾两端。
--
-- 5) 【version 列做乐观锁】
--    与 sys_knowledge_doc 一致。两个管理员同时编辑同一条策略时，
--    后提交者会收到 40009 而不是静默覆盖前者的修改——
--    安全策略被静默覆盖是「以为关掉了实际没关」这类事故的典型成因。
--
-- 幂等性：CREATE TABLE / CREATE INDEX / INSERT 均可重复执行。
-- =====================================================================

-- ---------------------------------------------------------------------
-- 一、风险等级策略（四行固定记录，对应 ToolRiskLevel）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sys_risk_policy (
    -- 主键即 ToolRiskLevel 枚举名。用 VARCHAR 而非自增 ID：
    -- 这张表的行与 Java 枚举一一对应，用枚举名做主键让「代码里的常量」
    -- 与「库里的行」可直接对照，排查时不必再查一次映射
    risk_level          VARCHAR(32)  PRIMARY KEY,

    -- 展示用。冗余自枚举的 displayName，便于 SQL 直接查看时可读
    display_name        VARCHAR(64)  NOT NULL,
    description         VARCHAR(255),

    -- ---- 审批门槛 ----
    -- NONE=免审批 / SINGLE=单人审批 / DUAL=双人审批（四眼原则）
    approval_mode       VARCHAR(16)  NOT NULL DEFAULT 'SINGLE',
    -- 审批时限（分钟）。超时未审批由定时任务标 EXPIRED，避免动作无限挂起
    approval_timeout_minutes INT     NOT NULL DEFAULT 30,

    -- ---- 执行限制 ----
    -- 是否允许引擎自动执行（false = 即便审批通过也只允许人工手动执行）
    auto_execute_allowed BOOLEAN     NOT NULL DEFAULT FALSE,
    -- 爆炸半径：百分比与绝对值双上限，取较小值（见文件头设计取舍 4）
    max_blast_radius_percent INT     NOT NULL DEFAULT 5,
    max_blast_radius_count   INT     NOT NULL DEFAULT 1,
    -- 两批之间的观察窗口（秒）。蓝图 §三 的「等待 60 秒校验健康心跳」
    cooldown_seconds    INT          NOT NULL DEFAULT 60,
    -- 单个动作允许的最大重试次数
    max_retries         INT          NOT NULL DEFAULT 0,

    -- ---- 升级路径 ----
    -- 执行失败后多少分钟未恢复则升级为人工介入
    escalate_after_minutes   INT     NOT NULL DEFAULT 15,
    -- 升级目标：TICKET=开工单 / ONCALL=呼叫值班 / NONE=仅记录
    escalate_target     VARCHAR(16)  NOT NULL DEFAULT 'TICKET',

    -- ---- 生效范围 ----
    -- 允许生效的环境，逗号分隔（prod,staging,dev）。空串=不允许任何环境
    allowed_environments VARCHAR(128) NOT NULL DEFAULT 'dev',

    -- 乐观锁：防两个管理员并发编辑时静默互相覆盖
    version             INT          NOT NULL DEFAULT 0,

    updated_by          VARCHAR(64),
    create_time         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE sys_risk_policy IS
    'L3 风险等级策略。四行固定记录，主键对应 ToolRiskLevel 枚举名，不可增删只可改';
COMMENT ON COLUMN sys_risk_policy.max_blast_radius_percent IS
    '爆炸半径百分比上限。与 max_blast_radius_count 取较小值——只配百分比会让大集群一次挂太多';
COMMENT ON COLUMN sys_risk_policy.version IS
    '乐观锁版本。安全策略被并发静默覆盖 = 「以为关掉了实际没关」，必须显式冲突';

-- 种子数据：与 ToolRiskLevel 四个枚举值一一对应。
-- 默认值刻意保守——新部署的系统应当「几乎什么都不自动做」，
-- 由运维团队按自己的风险偏好逐步放开，而不是反过来。
INSERT INTO sys_risk_policy (
    risk_level, display_name, description,
    approval_mode, approval_timeout_minutes,
    auto_execute_allowed, max_blast_radius_percent, max_blast_radius_count,
    cooldown_seconds, max_retries,
    escalate_after_minutes, escalate_target, allowed_environments
) VALUES
    ('READ_ONLY', '只读查询', '无副作用，可安全重试降级',
     'NONE', 30, TRUE, 100, 9999, 0, 3, 0, 'NONE', 'prod,staging,dev'),

    ('DRAFT', '草稿生成', '不直接改状态，输出供人工审核',
     'NONE', 30, TRUE, 100, 9999, 0, 2, 0, 'NONE', 'prod,staging,dev'),

    ('CONTROLLED_WRITE', '受控写操作', '有副作用但可控，需幂等补偿',
     'SINGLE', 30, FALSE, 20, 5, 60, 1, 15, 'TICKET', 'staging,dev'),

    ('HIGH_RISK_EXECUTION', '高风险执行', '不可逆或难逆，必须审批人工确认',
     'DUAL', 15, FALSE, 5, 1, 300, 0, 10, 'ONCALL', 'dev')
ON CONFLICT (risk_level) DO NOTHING;   -- 幂等：已有配置不被脚本重跑覆盖


-- ---------------------------------------------------------------------
-- 二、动作白名单（允许清单，无记录=不允许）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sys_action_allowlist (
    id                  BIGSERIAL    PRIMARY KEY,

    -- 语言无关的动作标识，如 k8s.pod.restart。与 AuditActionRegistry 的
    -- action 同风格：稳定、可用于统计与告警规则，展示文案单独存
    action_key          VARCHAR(64)  NOT NULL,
    display_name        VARCHAR(64)  NOT NULL,
    description         VARCHAR(255),

    -- 归类，便于列表分组：k8s / cloud / database / script / notify
    category            VARCHAR(32)  NOT NULL DEFAULT 'k8s',

    -- 风险等级，逻辑关联 sys_risk_policy.risk_level。
    -- 刻意不建外键：策略表将来若按租户分表，外键会成为迁移障碍；
    -- 且引用完整性由 Service 层对着 Java 枚举校验，比 DB 外键更严
    -- （DB 只能保证「策略表里有这行」，Service 能保证「枚举里有这个值」）
    risk_level          VARCHAR(32)  NOT NULL,

    -- 目标资源匹配模式，如 order-service-* 或 ns:prod/deploy:*。
    -- 空 = 不限制（危险，仅建议用于 READ_ONLY）
    target_pattern      VARCHAR(255),

    -- 允许生效的环境，逗号分隔。与风险策略取交集，不能超出策略允许的范围
    environments        VARCHAR(128) NOT NULL DEFAULT 'dev',

    -- 参数约束（JSONB）。形如 {"replicas":{"type":"int","max":3}}
    -- 引擎执行前按此校验模型给出的参数，防止「重启 1 个」被写成「重启 100 个」
    param_schema        JSONB,

    -- 条目级审批覆盖。NULL = 跟随风险等级策略；
    -- TRUE = 强制要求审批（只能收紧不能放宽，Service 层校验）
    requires_approval   BOOLEAN,

    -- 条目级爆炸半径覆盖。NULL = 跟随风险等级策略
    max_blast_radius_count INT,

    -- 停用而非删除：历史执行记录会引用 action_key，删掉会让审计变成孤儿
    enabled             BOOLEAN      NOT NULL DEFAULT FALSE,

    version             INT          NOT NULL DEFAULT 0,
    created_by          VARCHAR(64),
    updated_by          VARCHAR(64),
    create_time         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- action_key 全局唯一：同一个动作有两条配置时，引擎该信哪条？
-- 让 DB 直接拒绝，而不是靠应用层「取第一条」这种隐式规则
CREATE UNIQUE INDEX IF NOT EXISTS uk_action_allowlist_key
    ON sys_action_allowlist (action_key);

-- 列表页默认视图：按类别分组、类别内按风险从高到低
CREATE INDEX IF NOT EXISTS idx_action_allowlist_category
    ON sys_action_allowlist (category, risk_level);

-- 引擎热路径：「这个动作现在允许执行吗」。
-- 部分索引只覆盖启用项——引擎从不查停用的条目，索引体积可小一半
CREATE INDEX IF NOT EXISTS idx_action_allowlist_enabled
    ON sys_action_allowlist (action_key) WHERE enabled = TRUE;

COMMENT ON TABLE sys_action_allowlist IS
    'L3 动作白名单（允许清单）。无记录=不允许自动执行，默认拒绝是安全配置的唯一正确默认';
COMMENT ON COLUMN sys_action_allowlist.enabled IS
    '停用而非删除：历史执行记录引用 action_key，物理删除会让审计变孤儿';
COMMENT ON COLUMN sys_action_allowlist.requires_approval IS
    'NULL=跟随风险等级策略。只能收紧（改 TRUE），不能把高危动作放宽为免审批';

-- 种子数据：蓝图 §二/§三 点名的典型动作。
-- 全部 enabled=FALSE —— 装好就能自动重启生产 Pod 是不可接受的默认值，
-- 必须由管理员在页面上逐条确认开启。
INSERT INTO sys_action_allowlist (
    action_key, display_name, description, category, risk_level,
    target_pattern, environments, param_schema, enabled
) VALUES
    ('k8s.pod.describe', '查看 Pod 详情', '读取 Pod 配置、事件与状态，用于诊断上下文补充',
     'k8s', 'READ_ONLY', '*', 'prod,staging,dev',
     '{"namespace":{"type":"string","required":true}}'::jsonb, TRUE),

    ('k8s.logs.tail', '拉取容器日志', '读取最近 N 行容器日志，用于 RCA 归因',
     'k8s', 'READ_ONLY', '*', 'prod,staging,dev',
     '{"lines":{"type":"int","max":1000,"default":200}}'::jsonb, TRUE),

    ('k8s.pod.restart', '优雅重启 Pod', '对应蓝图 P2/P3 场景的 rollout restart，需受爆炸半径约束',
     'k8s', 'CONTROLLED_WRITE', 'ns:staging/*', 'staging,dev',
     '{"gracePeriodSeconds":{"type":"int","max":120,"default":30}}'::jsonb, FALSE),

    ('k8s.deploy.scale', '调整副本数', '扩缩容。缩容可能引发容量不足，受 max 参数约束',
     'k8s', 'CONTROLLED_WRITE', 'ns:staging/*', 'staging,dev',
     '{"replicas":{"type":"int","min":1,"max":10}}'::jsonb, FALSE),

    ('host.log.rotate', '日志空间回收', '蓝图 P4 场景：logrotate 释放磁盘，无业务影响',
     'host', 'CONTROLLED_WRITE', '*', 'staging,dev',
     '{"olderThanDays":{"type":"int","min":1,"default":7}}'::jsonb, FALSE),

    ('host.docker.prune', '清理废弃镜像', '蓝图 P4 场景：docker system prune 回收宿主机磁盘',
     'host', 'CONTROLLED_WRITE', '*', 'dev',
     '{"includeVolumes":{"type":"bool","default":false}}'::jsonb, FALSE),

    ('k8s.rollout.undo', '回滚发布', '蓝图 §三 的自愈回滚触发器，影响面覆盖整个 Deployment',
     'k8s', 'HIGH_RISK_EXECUTION', 'ns:staging/*', 'dev',
     '{"toRevision":{"type":"int"}}'::jsonb, FALSE),

    ('db.connection.kill', '终止数据库连接', '主库连接池打满时终止长事务，误杀会导致业务报错',
     'database', 'HIGH_RISK_EXECUTION', '*', 'dev',
     '{"minDurationSeconds":{"type":"int","min":60}}'::jsonb, FALSE),

    ('cloud.securitygroup.block', '封禁攻击源 IP', 'SecOps 场景：写入安全组黑名单，误封会切断正常访问',
     'cloud', 'HIGH_RISK_EXECUTION', '*', 'dev',
     '{"durationHours":{"type":"int","max":24,"default":24}}'::jsonb, FALSE)
ON CONFLICT (action_key) DO NOTHING;   -- 幂等：不覆盖管理员已调整过的条目
