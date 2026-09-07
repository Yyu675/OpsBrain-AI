-- =====================================================================
-- S3-3：执行后验证列（路线图 §7.4 3-4.4——验证结果写入执行台账）
-- =====================================================================
-- 设计依据（防漂移）：
-- * 验证结论直接落在执行行（单值：最近一次验证为准）——「这次执行好了
--   没有」是该行自己的事，不值得独立表；before/after 指标组进
--   verify_result_json，前端「执行前后对比」零换汁读取。
-- * verify_status 三值 + 两个程序态：
--     PASS / FAIL / UNKNOWN（验证器结论）
--     SKIPPED（无匹配验证器——必须留痕，未验证的 SUCCEEDED 不能长得像已验证）
--   失败自动回滚/升级的结果仍体现在 status 主列（UNDONE / UNDO_FAILED）。
-- =====================================================================
ALTER TABLE sys_healing_execution
    ADD COLUMN IF NOT EXISTS verify_status VARCHAR(16);
ALTER TABLE sys_healing_execution
    ADD COLUMN IF NOT EXISTS verify_result_json TEXT;
ALTER TABLE sys_healing_execution
    ADD COLUMN IF NOT EXISTS verified_at TIMESTAMP;

-- 待验证扫描的热路径：SUCCEEDED 且未验证且在观察窗内
CREATE INDEX IF NOT EXISTS idx_healing_execution_verify_scan
    ON sys_healing_execution (status, verify_status, finished_at);
