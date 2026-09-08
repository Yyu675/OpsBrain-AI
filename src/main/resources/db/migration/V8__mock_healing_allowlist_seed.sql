-- =====================================================================
-- S3-1 补丁：Mock 自愈动作白名单种子（让 Mock 轨开箱即可走通全链路）
-- =====================================================================
-- 设计依据（防漂移）：
-- * Mock 轨是治理链路的演示/CI 底座（与 MockChatModel 同族），但白名单
--   语义是「未登记 = 拒绝」——不登记 mock 动作，Mock 执行器在治理门下
--   连演算都到不了，演示即死。
-- * 风险等级取 DRAFT（免审批 + auto_execute_allowed=TRUE）：Mock 动作
--   零副作用，用 DRAFT 让开箱路径落在 AUTO_EXECUTE，全链路一遍走通；
--   环境限 staging,dev——prod 演示需管理员到治理页显式放行（与
--   「装好就自动重启生产 Pod 不可接受」同一默认保守精神）。
-- * mock.pod.restart 留一条 enabled=FALSE 的示例：审批路径的演示
--   由管理员启用后走（风险挂 CONTROLLED_WRITE → SINGLE 审批）。
-- =====================================================================
INSERT INTO sys_action_allowlist (
    action_key, display_name, description, category, risk_level,
    target_pattern, environments, param_schema, enabled
) VALUES
    ('mock.disk.cleanup', 'Mock 磁盘清理', 'Mock 轨演示动作：全链路（门→演算→执行→台账）零副作用走通',
     'script', 'DRAFT', '*', 'staging,dev',
     '{"gracePeriodSeconds":{"type":"int","max":120,"default":30}}'::jsonb, TRUE),
    ('mock.pod.restart', 'Mock 重启 Pod', 'Mock 轨审批路径演示：启用后走 SINGLE 审批再执行',
     'script', 'CONTROLLED_WRITE', '*', 'staging,dev',
     '{"gracePeriodSeconds":{"type":"int","max":120,"default":30}}'::jsonb, FALSE)
ON CONFLICT (action_key) DO NOTHING;
