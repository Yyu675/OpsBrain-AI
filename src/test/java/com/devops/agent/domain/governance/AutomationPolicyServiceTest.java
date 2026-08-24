package com.devops.agent.domain.governance;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 自动化策略服务测试（v27）。
 *
 * <p>覆盖重点是「策略与白名单之间的一致性」以及「匹配预演的准确性」。
 * 前者防止产出永不执行的僵尸策略，后者是用户上线策略前唯一的验证手段——
 * 预演结论若与引擎真实行为不符，比没有预演更危险。</p>
 */
@DisplayName("自动化策略服务")
class AutomationPolicyServiceTest {

    private RiskPolicyRepository riskRepo;
    private ActionAllowlistRepository allowlistRepo;
    private AutomationPolicyRepository policyRepo;
    private AutomationGovernanceService service;

    @BeforeEach
    void setUp() {
        riskRepo = mock(RiskPolicyRepository.class);
        allowlistRepo = mock(ActionAllowlistRepository.class);
        policyRepo = mock(AutomationPolicyRepository.class);
        service = new AutomationGovernanceService(riskRepo, allowlistRepo, policyRepo);
    }

    // ==================== 夹具 ====================

    private ActionAllowlistEntry action(String key, boolean enabled, String envs) {
        ActionAllowlistEntry e = new ActionAllowlistEntry();
        e.setActionKey(key);
        e.setDisplayName("重启 Pod");
        e.setCategory("k8s");
        e.setRiskLevel("CONTROLLED_WRITE");
        e.setEnvironments(envs);
        e.setEnabled(enabled);
        return e;
    }

    private RiskPolicy risk(boolean autoExecute, ApprovalMode mode, String envs) {
        RiskPolicy p = new RiskPolicy();
        p.setRiskLevel("CONTROLLED_WRITE");
        p.setDisplayName("受控写操作");
        p.setApprovalMode(mode);
        p.setAutoExecuteAllowed(autoExecute);
        p.setMaxBlastRadiusPercent(20);
        p.setMaxBlastRadiusCount(5);
        p.setCooldownSeconds(60);
        p.setAllowedEnvironments(envs);
        return p;
    }

    private AutomationPolicy policy(String name, String levels, String actionKey, String env) {
        AutomationPolicy p = new AutomationPolicy();
        p.setName(name);
        p.setMatchAlertLevels(levels);
        p.setMatchModule("K8S");
        p.setMatchServicePattern("*");
        p.setMatchAlertNamePattern("*");
        p.setActionKey(actionKey);
        p.setEnvironment(env);
        p.setPriority(100);
        p.setStopOnMatch(true);
        p.setCooldownMinutes(30);
        p.setMaxExecutionsPerDay(10);
        p.setDryRun(true);
        p.setEnabled(true);
        return p;
    }

    // ==================================================================

    @Nested
    @DisplayName("与白名单的一致性校验")
    class ActionConsistency {

        @Test
        @DisplayName("引用不存在的动作被拒绝，并提示先去登记")
        void rejectsUnknownAction() {
            when(allowlistRepo.findByActionKey("k8s.pod.nuke")).thenReturn(Optional.empty());

            AutomationPolicy p = policy("测试", "P3", "k8s.pod.nuke", "dev");
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> service.createAutomationPolicy(p, "admin"));
            assertTrue(ex.getMessage().contains("不存在于白名单"), ex.getMessage());
            verify(policyRepo, never()).insert(any(), anyString());
        }

        @Test
        @DisplayName("引用已停用的动作被拒绝——否则产出永不执行的僵尸策略")
        void rejectsDisabledAction() {
            when(allowlistRepo.findByActionKey("k8s.pod.restart"))
                    .thenReturn(Optional.of(action("k8s.pod.restart", false, "dev")));

            AutomationPolicy p = policy("测试", "P3", "k8s.pod.restart", "dev");
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> service.createAutomationPolicy(p, "admin"));
            assertTrue(ex.getMessage().contains("已停用"), ex.getMessage());
            assertTrue(ex.getMessage().contains("僵尸"), ex.getMessage());
        }

        @Test
        @DisplayName("策略环境超出动作开放范围时被拒绝")
        void rejectsEnvironmentBeyondAction() {
            when(allowlistRepo.findByActionKey("k8s.pod.restart"))
                    .thenReturn(Optional.of(action("k8s.pod.restart", true, "staging,dev")));

            AutomationPolicy p = policy("测试", "P3", "k8s.pod.restart", "prod");
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> service.createAutomationPolicy(p, "admin"));
            assertTrue(ex.getMessage().contains("未在 prod 环境开放"), ex.getMessage());
        }
    }

    @Nested
    @DisplayName("规则合法性")
    class RuleValidation {

        @BeforeEach
        void stubAction() {
            when(allowlistRepo.findByActionKey("k8s.pod.restart"))
                    .thenReturn(Optional.of(action("k8s.pod.restart", true, "prod,staging,dev")));
        }

        @Test
        @DisplayName("所有匹配条件为空时被拒绝——那等于对所有告警执行该动作")
        void rejectsAllBlankConditions() {
            AutomationPolicy p = policy("通配一切", null, "k8s.pod.restart", "dev");
            p.setMatchAlertLevels(null);
            p.setMatchModule(null);
            p.setMatchServicePattern("*");
            p.setMatchAlertNamePattern(null);

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> service.createAutomationPolicy(p, "admin"));
            assertTrue(ex.getMessage().contains("至少要指定一个匹配条件"), ex.getMessage());
        }

        @Test
        @DisplayName("只要有一个有效条件就放行")
        void acceptsSingleCondition() {
            AutomationPolicy p = policy("仅按级别", "P3", "k8s.pod.restart", "dev");
            p.setMatchModule(null);
            p.setMatchServicePattern("*");
            p.setMatchAlertNamePattern("*");

            when(policyRepo.findByName(anyString())).thenReturn(Optional.empty());
            when(policyRepo.insert(any(), anyString())).thenReturn(1L);
            when(policyRepo.findById(1L)).thenReturn(Optional.of(p));

            service.createAutomationPolicy(p, "admin");
            verify(policyRepo).insert(any(), anyString());
        }

        @Test
        @DisplayName("未知告警级别被拒绝——写错的级别会让策略静默永不匹配")
        void rejectsUnknownAlertLevel() {
            AutomationPolicy p = policy("测试", "P9", "k8s.pod.restart", "dev");

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> service.createAutomationPolicy(p, "admin"));
            assertTrue(ex.getMessage().contains("未知告警级别"), ex.getMessage());
        }

        @Test
        @DisplayName("非法 JSON 参数被拒绝——留到执行时才发现意味着故障当下自愈失败")
        void rejectsInvalidJsonParams() {
            AutomationPolicy p = policy("测试", "P3", "k8s.pod.restart", "dev");
            p.setActionParams("{ not json");

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> service.createAutomationPolicy(p, "admin"));
            assertTrue(ex.getMessage().contains("合法 JSON"), ex.getMessage());
        }

        @Test
        @DisplayName("重复策略名被拒绝，理由是日志无法定位")
        void rejectsDuplicateName() {
            AutomationPolicy existing = policy("重启策略", "P3", "k8s.pod.restart", "dev");
            when(policyRepo.findByName("重启策略")).thenReturn(Optional.of(existing));

            AutomationPolicy p = policy("重启策略", "P3", "k8s.pod.restart", "dev");
            assertThrows(IllegalStateException.class,
                    () -> service.createAutomationPolicy(p, "admin"));
        }

        @Test
        @DisplayName("级别列表归一化：去重、大写、去空格")
        void normalizesLevels() {
            AutomationPolicy p = policy("测试", " p3 , P3 ,p2", "k8s.pod.restart", "dev");
            when(policyRepo.findByName(anyString())).thenReturn(Optional.empty());
            when(policyRepo.insert(any(), anyString())).thenReturn(1L);
            when(policyRepo.findById(1L)).thenReturn(Optional.of(p));

            service.createAutomationPolicy(p, "admin");
            assertEquals("P3,P2", p.getMatchAlertLevels());
        }
    }

    @Nested
    @DisplayName("启用与关闭演练时的复查")
    class Revalidation {

        @Test
        @DisplayName("动作被停用后，策略无法被重新启用")
        void rejectsEnableWhenActionDisabled() {
            AutomationPolicy stale = policy("测试", "P3", "k8s.pod.restart", "dev");
            stale.setEnabled(false);
            when(policyRepo.findById(5L)).thenReturn(Optional.of(stale));
            when(allowlistRepo.findByActionKey("k8s.pod.restart"))
                    .thenReturn(Optional.of(action("k8s.pod.restart", false, "dev")));

            assertThrows(IllegalArgumentException.class,
                    () -> service.toggleAutomationPolicy(5L, true, 0, "admin"));
            verify(policyRepo, never()).toggleEnabled(anyLong(), anyBoolean(), anyInt(), anyString());
        }

        @Test
        @DisplayName("停用策略不复查——收紧方向永远允许")
        void alwaysAllowsDisable() {
            AutomationPolicy stale = policy("测试", "P3", "k8s.pod.restart", "dev");
            when(policyRepo.findById(5L)).thenReturn(Optional.of(stale));
            // 刻意不 stub 动作：若停用也走校验，这里会因动作缺失而失败

            service.toggleAutomationPolicy(5L, false, 0, "admin");
            verify(policyRepo).toggleEnabled(5L, false, 0, "admin");
        }

        @Test
        @DisplayName("关闭演练要复查——这是策略开始真实动手的时刻")
        void revalidatesWhenLeavingDryRun() {
            AutomationPolicy p = policy("测试", "P3", "k8s.pod.restart", "dev");
            when(policyRepo.findById(5L)).thenReturn(Optional.of(p));
            when(allowlistRepo.findByActionKey("k8s.pod.restart"))
                    .thenReturn(Optional.of(action("k8s.pod.restart", false, "dev")));

            assertThrows(IllegalArgumentException.class,
                    () -> service.toggleDryRun(5L, false, 0, "admin"));
            verify(policyRepo, never()).toggleDryRun(anyLong(), anyBoolean(), anyInt(), anyString());
        }

        @Test
        @DisplayName("切回演练不复查——回到更安全的状态不该被阻拦")
        void allowsReturningToDryRun() {
            AutomationPolicy p = policy("测试", "P3", "k8s.pod.restart", "dev");
            when(policyRepo.findById(5L)).thenReturn(Optional.of(p));

            service.toggleDryRun(5L, true, 0, "admin");
            verify(policyRepo).toggleDryRun(5L, true, 0, "admin");
        }
    }

    @Nested
    @DisplayName("生效状态装填")
    class EffectiveState {

        @Test
        @DisplayName("策略启用但动作被停用时，标记为不生效并说明原因")
        void marksIneffectiveWhenActionDisabled() {
            AutomationPolicy p = policy("测试", "P3", "k8s.pod.restart", "dev");
            when(policyRepo.findById(1L)).thenReturn(Optional.of(p));
            when(allowlistRepo.findByActionKey("k8s.pod.restart"))
                    .thenReturn(Optional.of(action("k8s.pod.restart", false, "dev")));

            AutomationPolicy loaded = service.getAutomationPolicy(1L);
            assertEquals(Boolean.FALSE, loaded.getEffective());
            assertTrue(loaded.getIneffectiveReason().contains("已停用"));
        }

        @Test
        @DisplayName("动作不在白名单时也标记不生效")
        void marksIneffectiveWhenActionMissing() {
            AutomationPolicy p = policy("测试", "P3", "gone.action", "dev");
            when(policyRepo.findById(1L)).thenReturn(Optional.of(p));
            when(allowlistRepo.findByActionKey("gone.action")).thenReturn(Optional.empty());

            AutomationPolicy loaded = service.getAutomationPolicy(1L);
            assertEquals(Boolean.FALSE, loaded.getEffective());
            assertTrue(loaded.getIneffectiveReason().contains("不在白名单"));
        }

        @Test
        @DisplayName("全部就绪时标记为生效")
        void marksEffectiveWhenAllReady() {
            AutomationPolicy p = policy("测试", "P3", "k8s.pod.restart", "dev");
            when(policyRepo.findById(1L)).thenReturn(Optional.of(p));
            when(allowlistRepo.findByActionKey("k8s.pod.restart"))
                    .thenReturn(Optional.of(action("k8s.pod.restart", true, "dev")));

            AutomationPolicy loaded = service.getAutomationPolicy(1L);
            assertEquals(Boolean.TRUE, loaded.getEffective());
            assertEquals("重启 Pod", loaded.getActionDisplayName());
        }

        @Test
        @DisplayName("列表查询为每一行装填，前端不必自己判断")
        void appliesToListItems() {
            AutomationPolicy p = policy("测试", "P3", "k8s.pod.restart", "dev");
            Map<String, Object> page = new LinkedHashMap<>();
            page.put("items", List.of(p));
            page.put("total", 1L);
            when(policyRepo.query(any(), any(), any(), any(), anyInt(), anyInt()))
                    .thenReturn(page);
            when(allowlistRepo.findByActionKey("k8s.pod.restart"))
                    .thenReturn(Optional.of(action("k8s.pod.restart", true, "dev")));

            service.listAutomationPolicies(null, null, null, null, 1, 20);
            assertEquals(Boolean.TRUE, p.getEffective());
        }
    }

    @Nested
    @DisplayName("匹配预演")
    class Simulate {

        @Test
        @DisplayName("没有策略命中时明确告知走默认流程")
        void reportsNoMatch() {
            when(policyRepo.findEnabledInEvalOrder()).thenReturn(List.of());

            Map<String, Object> r = service.simulate("P3", "K8S", "svc", "Alert", "dev");
            assertEquals(0L, r.get("matchedCount"));
            assertTrue(String.valueOf(r.get("summary")).contains("默认流程"));
        }

        @Test
        @DisplayName("演练模式的策略命中后结论是 DRY_RUN，不是 EXECUTE")
        void dryRunPolicyReportsDryRun() {
            AutomationPolicy p = policy("测试", "P3", "k8s.pod.restart", "dev");
            p.setDryRun(true);
            when(policyRepo.findEnabledInEvalOrder()).thenReturn(List.of(p));
            when(allowlistRepo.findByActionKey("k8s.pod.restart"))
                    .thenReturn(Optional.of(action("k8s.pod.restart", true, "dev")));
            when(riskRepo.findByRiskLevel("CONTROLLED_WRITE"))
                    .thenReturn(Optional.of(risk(true, ApprovalMode.NONE, "dev")));

            Map<String, Object> r = service.simulate("P3", "K8S", "svc", "Alert", "dev");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rows = (List<Map<String, Object>>) r.get("evaluated");
            assertEquals("DRY_RUN", rows.get(0).get("outcome"));
        }

        @Test
        @DisplayName("需审批的动作命中后结论是 PENDING_APPROVAL")
        void approvalRequiredReportsPending() {
            AutomationPolicy p = policy("测试", "P3", "k8s.pod.restart", "dev");
            p.setDryRun(false);
            when(policyRepo.findEnabledInEvalOrder()).thenReturn(List.of(p));
            when(allowlistRepo.findByActionKey("k8s.pod.restart"))
                    .thenReturn(Optional.of(action("k8s.pod.restart", true, "dev")));
            when(riskRepo.findByRiskLevel("CONTROLLED_WRITE"))
                    .thenReturn(Optional.of(risk(true, ApprovalMode.SINGLE, "dev")));

            Map<String, Object> r = service.simulate("P3", "K8S", "svc", "Alert", "dev");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rows = (List<Map<String, Object>>) r.get("evaluated");
            assertEquals("PENDING_APPROVAL", rows.get(0).get("outcome"));
        }

        @Test
        @DisplayName("动作被白名单拦下时结论是 BLOCKED，且带上拦截原因")
        void blockedByAllowlist() {
            AutomationPolicy p = policy("测试", "P3", "k8s.pod.restart", "dev");
            p.setDryRun(false);
            when(policyRepo.findEnabledInEvalOrder()).thenReturn(List.of(p));
            when(allowlistRepo.findByActionKey("k8s.pod.restart"))
                    .thenReturn(Optional.of(action("k8s.pod.restart", false, "dev")));

            Map<String, Object> r = service.simulate("P3", "K8S", "svc", "Alert", "dev");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rows = (List<Map<String, Object>>) r.get("evaluated");
            assertEquals("BLOCKED", rows.get(0).get("outcome"));
            assertTrue(String.valueOf(rows.get(0).get("reason")).contains("停用"));
        }

        @Test
        @DisplayName("stopOnMatch 命中后，后续策略标为「未被求值」而非「未匹配」")
        void marksSkippedAfterStop() {
            AutomationPolicy first = policy("高优", "P3", "k8s.pod.restart", "dev");
            first.setPriority(10);
            first.setStopOnMatch(true);
            AutomationPolicy second = policy("低优", "P3", "k8s.pod.restart", "dev");
            second.setPriority(20);

            when(policyRepo.findEnabledInEvalOrder()).thenReturn(List.of(first, second));
            when(allowlistRepo.findByActionKey("k8s.pod.restart"))
                    .thenReturn(Optional.of(action("k8s.pod.restart", true, "dev")));
            when(riskRepo.findByRiskLevel("CONTROLLED_WRITE"))
                    .thenReturn(Optional.of(risk(true, ApprovalMode.NONE, "dev")));

            Map<String, Object> r = service.simulate("P3", "K8S", "svc", "Alert", "dev");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rows = (List<Map<String, Object>>) r.get("evaluated");

            // 区分「求值了但没匹配」与「根本没被求值」——两者的排查方向完全不同
            assertEquals(Boolean.TRUE, rows.get(1).get("skipped"));
            assertTrue(String.valueOf(rows.get(1).get("reason")).contains("命中即停"));
        }

        @Test
        @DisplayName("环境不符时给出针对性原因，而不是笼统的「不匹配」")
        void explainsEnvironmentMismatch() {
            AutomationPolicy p = policy("测试", "P3", "k8s.pod.restart", "dev");
            when(policyRepo.findEnabledInEvalOrder()).thenReturn(List.of(p));

            Map<String, Object> r = service.simulate("P3", "K8S", "svc", "Alert", "prod");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rows = (List<Map<String, Object>>) r.get("evaluated");
            assertEquals(false, rows.get(0).get("matched"));
            assertTrue(String.valueOf(rows.get(0).get("reason")).contains("生效环境"));
        }

        @Test
        @DisplayName("条件不符时逐条说明差在哪，用户才能自己改规则")
        void explainsWhichConditionFailed() {
            AutomationPolicy p = policy("测试", "P0", "k8s.pod.restart", "dev");
            when(policyRepo.findEnabledInEvalOrder()).thenReturn(List.of(p));

            Map<String, Object> r = service.simulate("P3", "K8S", "svc", "Alert", "dev");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rows = (List<Map<String, Object>>) r.get("evaluated");
            String reason = String.valueOf(rows.get(0).get("reason"));
            assertTrue(reason.contains("级别要求"), reason);
            assertTrue(reason.contains("P3"), reason);
        }

        @Test
        @DisplayName("firstEffective 指向第一条命中的策略，供页面高亮")
        void reportsFirstEffective() {
            AutomationPolicy p = policy("测试", "P3", "k8s.pod.restart", "dev");
            when(policyRepo.findEnabledInEvalOrder()).thenReturn(List.of(p));
            when(allowlistRepo.findByActionKey("k8s.pod.restart"))
                    .thenReturn(Optional.of(action("k8s.pod.restart", true, "dev")));
            when(riskRepo.findByRiskLevel("CONTROLLED_WRITE"))
                    .thenReturn(Optional.of(risk(true, ApprovalMode.NONE, "dev")));

            Map<String, Object> r = service.simulate("P3", "K8S", "svc", "Alert", "dev");
            assertNotNull(r.get("firstEffective"));
            assertTrue(String.valueOf(r.get("summary")).contains("测试"));
        }
    }
}
