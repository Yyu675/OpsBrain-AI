-- =====================================================================
-- e2e 种子数据（幂等，可重复执行）
--
-- ── 为什么需要它 ─────────────────────────────────────────────────────
-- e2e 腿（.github/workflows/ci.yml 的 `e2e` 作业）只 psql 灌 V1 基线、
-- 其余 V{n} 交给应用启动时的 Flyway，之后直接跑 Playwright——**库里没有
-- 任何业务数据**。而三条用例自述的依赖是「dev 库常驻/人工联调造数」：
--   - tickets.spec        需要库里常驻 ≥1 张工单（列表行/搜索框/详情链接）
--   - knowledge-v2.spec   需要第二个知识库「故障FAQ库」（1200/300/50）
--     与其库内文档 `faq-test-doc`
--   - role-matrix.spec    需要可登录的角色账号 e2e_operator(OPS) / e2e_viewer(VIEWER)，
--     批90 R0-3 只在 dev 库用 SQL upsert 过，仓库里无可复现种子
-- 结果是 6 例（2026-10-13 实测：38422954784 / 721fff5）+ 角色矩阵 6 例
-- （2026-10-14 实测：38430788677 / 3604862，7 failed 中占 6）在 CI 上必红。
-- 本文件把这批前置数据从「人脑记得的联调产物」变成**可复现的脚本**。
--
-- ── 为什么不做成 Flyway 迁移 ─────────────────────────────────────────
-- 迁移会在生产库上执行。测试数据混进 V{n} 的代价是：任何环境升级都会被
-- 塞进一张假工单和一个假知识库，且「删掉测试数据」变成一次新的迁移。
-- 种子的生命周期属于「测试环境制备」，不属于「schema 演进」，故独立成文件，
-- 由 CI 在跑用例前显式灌入（本地同理，见下）。
--
-- ── 用法 ────────────────────────────────────────────────────────────
--   psql -h localhost -p 25432 -U devops -d devops_knowledge_db \
--        -v ON_ERROR_STOP=1 -f tools/seed/e2e-seed.sql
--   本地 dev 库（docker-compose.dev.yml 暴露 25432）同一条命令即可。
--
-- ── 约定 ────────────────────────────────────────────────────────────
-- 1) 全部语句幂等：靠 INSERT ... SELECT ... WHERE NOT EXISTS，重跑不重复插。
-- 2) 工单标题**不**用 E2E_PW_ 前缀：那是 tickets.spec 自建自清用例的标记，
--    其 afterEach 会按前缀删库，常驻种子若同名会被清理掉（自断数据源）。
-- 3) 文件尾部自带校验：种子缺任何一样即 RAISE，psql 非零退出 →
--    CI 在「种子未生效」处快速失败，而不是让 6 个用例各红一次再靠人猜。
-- 4) 已知边界：若 dev 库里已存在**同名但参数不同**的「故障FAQ库」，
--    knowledge-v2.spec 的 `1200 / 300 / 50` 断言会红（种子不覆盖已有数据，
--    不去改人手工建的库）。CI 是全新库，不受此影响。
-- =====================================================================

BEGIN;

-- ---------------------------------------------------------------------
-- 1) 故障 FAQ 库：第二个知识库 + 自定义切片参数 1200/300/50
--    默认知识库由 V2 迁移种下（code='default'，参数留空跟随全局 2400/600/100），
--    本文件只补第二个库——「多知识库」这条链路才有对照物。
-- ---------------------------------------------------------------------
INSERT INTO sys_knowledge_base (name, code, description, parent_chunk_size, child_chunk_size, chunk_overlap)
SELECT '故障FAQ库', 'faq',
       'e2e 种子库：验证多知识库筛选与按库切片参数（tools/seed/e2e-seed.sql）',
       1200, 300, 50
 WHERE NOT EXISTS (
       SELECT 1 FROM sys_knowledge_base WHERE LOWER(code) = 'faq' OR name = '故障FAQ库');

-- ---------------------------------------------------------------------
-- 2) 该库内的文档 faq-test-doc
--    index_status='SKIPPED' 是刻意的：本行由 SQL 直接插入，没有走切片/向量化，
--    标成 PENDING 会让「索引空洞」告警把种子数据算成故障，标成 INDEXED 则是说谎。
--    content_hash 按表约束（NOT NULL）填真实 SHA-256 —— 用 PG11+ 内置 sha256()
--    现算，避免在脚本里写死一串哈希（改内容忘了改哈希=埋雷）。
-- ---------------------------------------------------------------------
WITH target AS (
    SELECT id FROM sys_knowledge_base
     WHERE LOWER(code) = 'faq' OR name = '故障FAQ库'
     ORDER BY id LIMIT 1
), body AS (
    SELECT '故障FAQ库种子文档：本文件由 tools/seed/e2e-seed.sql 注入，供 knowledge-v2.spec 验证「选中库 → 列表按 kbId 过滤 → URL 写回 ?kb=」链路。'
           '真实故障 FAQ 内容不应出现在这里——该库在 CI 中是空库的对照物。' AS content
)
INSERT INTO sys_knowledge_doc (title, category, author, content, summary, status, index_status,
                               knowledge_source, visibility, kb_id, content_hash, chunk_count)
SELECT 'faq-test-doc', '故障处理', 'e2e-seed', b.content,
       'e2e 种子文档（不走切片，index_status=SKIPPED）',
       'PUBLISHED', 'SKIPPED', 'SOP', 'PUBLIC', t.id,
       encode(sha256(convert_to(b.content, 'UTF8')), 'hex'), 0
  FROM target t CROSS JOIN body b
 WHERE NOT EXISTS (
       SELECT 1 FROM sys_knowledge_doc d WHERE d.kb_id = t.id AND d.title = 'faq-test-doc');

-- ---------------------------------------------------------------------
-- 3) 常驻工单 1 张：tickets.spec 的「列表渲染 / 搜索过滤 / 点工单号进详情」
--    三例都建立在「列表里本来就有行」之上（创建用例是自建自清的，不能当数据源）。
--    id 用可读前缀便于人工识别；status/priority 取常规值，让 KPI 卡与状态
--    筛选都有真实数据可算。
-- ---------------------------------------------------------------------
INSERT INTO sys_devops_ticket (id, title, priority, module, description, status,
                               creator, category, response_deadline, resolve_deadline)
SELECT 'TKT-E2E-SEED-0001',
       'E2E 种子工单：日志盘水位告警演练',
       'P2', '基础设施',
       '常驻种子工单（tools/seed/e2e-seed.sql）——tickets.spec 的列表类用例依赖库内至少 1 张工单。请勿删除：删了 CI 会连红三例。',
       'PENDING', 'e2e-seed', '存储',
       CURRENT_TIMESTAMP + INTERVAL '30 minutes', CURRENT_TIMESTAMP + INTERVAL '4 hours'
 WHERE NOT EXISTS (SELECT 1 FROM sys_devops_ticket WHERE id = 'TKT-E2E-SEED-0001');

-- ---------------------------------------------------------------------
-- 4) RBAC 角色矩阵账号：e2e_operator(OPS) / e2e_viewer(VIEWER)
--    为什么必须进种子：后端没有用户管理端点（角色账号只能 SQL 入库），而批90 R0-3
--    的账号只存在于当时那台 dev 库里——CI 全新库上 role-matrix.spec 的 6 例全部
--    停在 /login（2026-10-14 实测）。口令与 spec 一致（明文在 spec 与值班SOP 里），
--    这里只存哈希。
--    哈希为什么必须是 $2a$：后端用 jBCrypt（org.mindrot）——它只认 $2a$，
--    喂 $2b$ 会抛 IllegalArgumentException: Invalid salt revision（本地用
--    jBCrypt 真源码编译实证；Python bcrypt 产出的 $2b$ 换前缀即等价）。
--    安全纪律见 docs/09-operations/值班SOP.md §7：e2e_ 前缀账号在非本机环境必须 DISABLED。
--    与前面几节同样幂等：已存在同名账号不动它（不去改人手工建的库）。
-- ---------------------------------------------------------------------
INSERT INTO sys_user (username, password, display_name, role, dept, status)
SELECT 'e2e_operator', '$2a$10$XLNjWHM/J5kfcXiFNw9rZOAvojKVLBYnIHMmD3T1D7wiJGQf2abTW',
       'E2E 运维（OPS）', 'OPS', 'SRE', 'ACTIVE'
 WHERE NOT EXISTS (SELECT 1 FROM sys_user WHERE username = 'e2e_operator');

INSERT INTO sys_user (username, password, display_name, role, dept, status)
SELECT 'e2e_viewer', '$2a$10$2uioDh72AxUcrc7wDbcXm.q6mmpf4JQV4.iExdaBOD5mA5BBvRaOC',
       'E2E 只读（VIEWER）', 'VIEWER', 'SRE', 'ACTIVE'
 WHERE NOT EXISTS (SELECT 1 FROM sys_user WHERE username = 'e2e_viewer');

-- ---------------------------------------------------------------------
-- 5) 自校验：种子四件套逐一回读，缺任何一样立即失败（ON_ERROR_STOP 生效）
-- ---------------------------------------------------------------------
DO $$
DECLARE
    n_kb     INT;
    n_doc    INT;
    n_ticket INT;
    n_role   INT;
BEGIN
    SELECT COUNT(*) INTO n_kb FROM sys_knowledge_base
     WHERE LOWER(code) = 'faq' OR name = '故障FAQ库';
    SELECT COUNT(*) INTO n_doc
      FROM sys_knowledge_doc d
      JOIN sys_knowledge_base b ON d.kb_id = b.id
     WHERE (LOWER(b.code) = 'faq' OR b.name = '故障FAQ库') AND d.title = 'faq-test-doc';
    SELECT COUNT(*) INTO n_ticket FROM sys_devops_ticket;
    -- 两个角色账号都必须在且 ACTIVE：停用账号登录会明确报「账号已停用」，
    -- 与「密码错」同属登不进，但排查方向完全不同，故分开计数
    SELECT COUNT(*) INTO n_role FROM sys_user
     WHERE username IN ('e2e_operator', 'e2e_viewer') AND status = 'ACTIVE';

    IF n_kb < 1 OR n_doc < 1 OR n_ticket < 1 OR n_role < 2 THEN
        RAISE EXCEPTION 'e2e 种子未生效：FAQ库=% 文档=% 工单总数=% 角色账号=%',
            n_kb, n_doc, n_ticket, n_role;
    END IF;
    RAISE NOTICE 'e2e 种子就绪：FAQ库=% 种子文档=% 工单总数=% 角色账号=%',
        n_kb, n_doc, n_ticket, n_role;
END $$;

COMMIT;
