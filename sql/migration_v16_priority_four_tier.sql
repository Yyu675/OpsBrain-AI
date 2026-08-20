-- =====================================================================
-- v16: 优先级四档迁移 + SLA deadline 字段（B0：业务闭环前置）
-- =====================================================================
-- 背景：
--   1) 优先级原为三档 HIGH/MEDIUM/LOW，而前端有 urgent/high/medium/low 四档，
--      映射时 urgent→HIGH、high→HIGH 两档塌缩——用户选「高」保存后回读变「紧急」，
--      high 档事实上不存在。且三档无法实现 PRD §2.3 要求的
--      P0(15min)/P1(30min)/P2(4h)/P3(24h) 分级首响 SLA。
--   2) SLA 此前只有展示串（如「4h 响应 / 8h 解决」），无法用于计时。
--      首响超时、MTTR 统计都需要可比较的时间戳。
--
-- 迁移映射：HIGH→P1、MEDIUM→P2、LOW→P3
--   为何 HIGH→P1 而非 P0：旧 HIGH 混装了「紧急」与「高」两种语义（因前端塌缩），
--   无法区分。统一降为 P1 更保守——误把普通高优当 P0 会让 15 分钟首响时限
--   失去可信度，反而使 SLA 形同虚设。需要 P0 的工单由人工重新标记。
--
-- 幂等性：列用 IF NOT EXISTS；数据迁移带 WHERE priority IN (旧值) 守卫，
--         重复执行时第二次影响 0 行。
-- =====================================================================

-- ---------------------------------------------------------------------
-- Step 1: 新增 SLA 计时字段
-- ---------------------------------------------------------------------
-- 为何 deadline 存字段而非每次按当前时限表计算：
--   SLA 时限策略可能调整，若每次实时计算，历史工单的截止时间会被「追溯改写」
--   ——考核数据必须冻结在建单时刻的口径。且存字段可直接用 SQL 查
--   「即将超时」清单，无需应用层遍历。
ALTER TABLE sys_devops_ticket
    ADD COLUMN IF NOT EXISTS response_deadline TIMESTAMP,          -- 首响截止时刻
    ADD COLUMN IF NOT EXISTS resolve_deadline  TIMESTAMP;          -- 解决截止时刻

-- ---------------------------------------------------------------------
-- Step 2: 存量优先级迁移（三档 → 四档）
-- ---------------------------------------------------------------------
DO $$
DECLARE
    n_high   INTEGER;
    n_medium INTEGER;
    n_low    INTEGER;
BEGIN
    UPDATE sys_devops_ticket SET priority = 'P1' WHERE priority = 'HIGH';
    GET DIAGNOSTICS n_high = ROW_COUNT;

    UPDATE sys_devops_ticket SET priority = 'P2' WHERE priority = 'MEDIUM';
    GET DIAGNOSTICS n_medium = ROW_COUNT;

    UPDATE sys_devops_ticket SET priority = 'P3' WHERE priority = 'LOW';
    GET DIAGNOSTICS n_low = ROW_COUNT;

    IF n_high + n_medium + n_low > 0 THEN
        RAISE NOTICE '[v16] 优先级迁移完成: HIGH→P1=% 条, MEDIUM→P2=% 条, LOW→P3=% 条',
            n_high, n_medium, n_low;
    ELSE
        RAISE NOTICE '[v16] 无需迁移优先级（已是四档或表为空）';
    END IF;
END $$;

-- ---------------------------------------------------------------------
-- Step 2b: 刷新 SLA 展示串，使其与新优先级的时限一致
-- ---------------------------------------------------------------------
-- 必要性：只迁移 priority 与 deadline 会留下自相矛盾的数据——
-- 例如 HIGH→P1 后 response_deadline 是 +30 分钟，但 sla 串仍写着「4h 响应」，
-- 前端悬浮卡会同时显示「还剩 -5375 分钟」和「目标 4h 响应」，用户无从判断哪个是真的。
--
-- 安全性：仅覆盖**旧版自动派生**的三种串（精确匹配）。
-- 用户通过 API 自定义的 SLA 串不在此列，不会被破坏。
UPDATE sys_devops_ticket
   SET sla = CASE priority
                 WHEN 'P0' THEN '15m 响应 / 4h 解决'
                 WHEN 'P1' THEN '30m 响应 / 8h 解决'
                 WHEN 'P2' THEN '4h 响应 / 24h 解决'
                 WHEN 'P3' THEN '24h 响应 / 72h 解决'
                 ELSE sla
             END
 WHERE sla IN ('4h 响应 / 8h 解决', '8h 响应 / 24h 解决', '24h 响应');

-- ---------------------------------------------------------------------
-- Step 3: 回填存量工单的 deadline
-- ---------------------------------------------------------------------
-- 时限表与 TicketEnums.Sla 保持一致：
--   P0: 15min 响应 / 4h 解决      P1: 30min / 8h
--   P2: 4h / 24h                  P3: 24h / 72h
-- 只回填 NULL 的行，避免覆盖已有值（幂等）。
UPDATE sys_devops_ticket
   SET response_deadline = create_time + (
           CASE priority
               WHEN 'P0' THEN INTERVAL '15 minutes'
               WHEN 'P1' THEN INTERVAL '30 minutes'
               WHEN 'P3' THEN INTERVAL '24 hours'
               ELSE INTERVAL '4 hours'
           END
       ),
       resolve_deadline = create_time + (
           CASE priority
               WHEN 'P0' THEN INTERVAL '4 hours'
               WHEN 'P1' THEN INTERVAL '8 hours'
               WHEN 'P3' THEN INTERVAL '72 hours'
               ELSE INTERVAL '24 hours'
           END
       )
 WHERE response_deadline IS NULL OR resolve_deadline IS NULL;

-- ---------------------------------------------------------------------
-- Step 4: 索引
-- ---------------------------------------------------------------------
-- 「即将超时 / 已超时」清单查询：WHERE response_deadline < NOW() AND first_response_at IS NULL
-- （first_response_at 由 B1 批次添加，此处先建 deadline 索引）
CREATE INDEX IF NOT EXISTS idx_ticket_response_deadline
    ON sys_devops_ticket (response_deadline);
CREATE INDEX IF NOT EXISTS idx_ticket_resolve_deadline
    ON sys_devops_ticket (resolve_deadline);

-- ---------------------------------------------------------------------
-- 对账查询（迁移后应无旧值残留）
-- ---------------------------------------------------------------------
-- SELECT priority, COUNT(*) FROM sys_devops_ticket GROUP BY priority;
--   预期只出现 P0/P1/P2/P3，不应再有 HIGH/MEDIUM/LOW
-- SELECT COUNT(*) FROM sys_devops_ticket WHERE response_deadline IS NULL;
--   预期 0
