-- =====================================================================
-- v22: 用户表（方向三：真实鉴权，JWT + BCrypt）
-- =====================================================================
-- 背景：此前 stores/app.ts 硬编码假管理员（isAuthenticated:true / permissions:['*']），
-- 配额 key 用 sessionId 兜底（无法按人累积）。本表提供真实用户来源。
--
-- 密码：BCrypt 哈希，绝不明文。种子用户（admin）由 AuthDataInitializer 在应用启动时
-- 用 BCryptPasswordEncoder 编码写入（幂等：已存在则跳过）——迁移不写死哈希，
-- 因 BCrypt 每次 salt 不同，运行时编码才能保证与登录校验一致。
--
-- 关联：display_name 可对齐 sys_team_member.name（工单负责人名录），
-- 后续把「登录用户」与「工单处理人」打通。
--
-- 幂等性：CREATE TABLE / CREATE INDEX 全部 IF NOT EXISTS。
-- =====================================================================

CREATE TABLE IF NOT EXISTS sys_user (
    id            BIGSERIAL PRIMARY KEY,
    username      VARCHAR(64)  NOT NULL,                        -- 登录名（唯一）
    password      VARCHAR(100) NOT NULL,                        -- BCrypt 哈希（$2a$... 60 字符，留余量）
    display_name  VARCHAR(64),                                  -- 展示名（可对齐 sys_team_member.name）
    role          VARCHAR(32)  NOT NULL DEFAULT 'OPS',          -- 角色：ADMIN/OPS（对齐前端 Role）
    status        VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',       -- ACTIVE/DISABLED
    last_login_at TIMESTAMP,                                    -- 末次登录时刻
    create_time   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 登录名唯一（大小写敏感——用户名通常区分大小写）
CREATE UNIQUE INDEX IF NOT EXISTS uk_user_username ON sys_user (username);
