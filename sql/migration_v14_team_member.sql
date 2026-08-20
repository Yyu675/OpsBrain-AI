-- =====================================================================
-- v14: sys_team_member - 运维团队成员名录（工单负责人来源）
-- =====================================================================
-- 背景：前端 ASSIGNEE_OPTIONS = ['张明','李四','王五','赵六','孙七','周八','待分配']
-- 是硬编码编造名单——库里只有「张明」一个真实负责人，其余五人从不存在。
-- 用户选人后写入 sys_devops_ticket.assignee（VARCHAR(64) 自由文本），
-- 导致工单被指派给不存在的人，且筛选下拉框恒定七项不随真实数据变化。
--
-- 策略：建成员名录表，由 GET /api/v1/users 下发，前端不再硬编码。
-- 名录种子从**真实存量数据**回填（现有工单的 assignee/creator），不编造姓名。
--
-- 幂等性：CREATE TABLE IF NOT EXISTS + NOT EXISTS 子查询，可重复执行。
-- =====================================================================

CREATE TABLE IF NOT EXISTS sys_team_member (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(64)  NOT NULL,                      -- 显示名，与 sys_devops_ticket.assignee 对应
    email       VARCHAR(128),
    role        VARCHAR(32)  NOT NULL DEFAULT 'operator',   -- admin/operator/viewer（对齐前端 Role 词表）
    title       VARCHAR(64),                                -- 职位，如「高级运维工程师」
    status      VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',     -- ACTIVE/DISABLED（停用者不再出现在选人列表）
    sort_order  INT          NOT NULL DEFAULT 0,
    create_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 同名成员只允许一条：assignee 是按姓名匹配的自由文本，
-- 重名会让「张明」与「张明 」指向两个人，筛选结果割裂
CREATE UNIQUE INDEX IF NOT EXISTS uk_team_member_name
    ON sys_team_member (LOWER(name));

-- 选人列表查询索引（status 过滤 + 排序）
CREATE INDEX IF NOT EXISTS idx_team_member_status
    ON sys_team_member (status, sort_order, id);

-- ---------------------------------------------------------------------
-- 种子 1：兜底管理员
-- ---------------------------------------------------------------------
-- 全新部署时库中无任何工单，名录为空会导致选人下拉框空白、无法指派。
-- 「管理员」与前端 stores/app.ts DEFAULT_USER.name 一致，是平台真实默认账号。
INSERT INTO sys_team_member (name, email, role, title, sort_order)
SELECT '管理员', 'admin@devops.local', 'admin', '高级运维工程师', 0
 WHERE NOT EXISTS (
       SELECT 1 FROM sys_team_member m WHERE LOWER(m.name) = LOWER('管理员')
   );

-- ---------------------------------------------------------------------
-- 种子 2：回填存量工单中出现过的真实负责人
-- ---------------------------------------------------------------------
-- 只取库里真实存在的 assignee，不编造姓名。
-- 排除「待分配」——它是前端「未指派」哨兵值，不是人。
INSERT INTO sys_team_member (name, role, sort_order)
SELECT s.name, 'operator', 10
  FROM (
        SELECT MIN(TRIM(t.assignee)) AS name
          FROM sys_devops_ticket t
         WHERE t.assignee IS NOT NULL
           AND TRIM(t.assignee) <> ''
           AND TRIM(t.assignee) <> '待分配'
         GROUP BY LOWER(TRIM(t.assignee))
       ) s
 WHERE NOT EXISTS (
       SELECT 1 FROM sys_team_member m WHERE LOWER(m.name) = LOWER(s.name)
   );
