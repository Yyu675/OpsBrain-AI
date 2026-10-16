package com.devops.agent.domain.healing;

import com.devops.agent.domain.alert.entity.Alert;
import com.devops.agent.domain.governance.AutomationPolicy;
import com.devops.agent.domain.governance.AutomationPolicyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @Display策略引擎（S4-1 批次 7）：匹配→演练留痕→真递交；冷却/日上限两把保险 + stopOnMatch 语义。
 */
@DisplayName("HealingAutoTrigger（告警→策略→自愈动作 触发引擎）")
class HealingAutoTriggerTest {

    private AutomationPolicyRepository policyRepository;
    private HealingExecutionRepository executionRepository;
    private HealingOrchestrator orchestrator;
    private HealingAutoTrigger trigger;

    @BeforeEach
    void setUp() {
        policyRepository = mock(AutomationPolicyRepository.class);
        executionRepository = mock(HealingExecutionRepository.class);
        orchestrator = mock(HealingOrchestrator.class);
        trigger = new HealingAutoTrigger(policyRepository, executionRepository, orchestrator);
        when(executionRepository.insert(any())).thenReturn(7001L);
    }

    private static Alert alert() {
        Alert a = new Alert();
        a.setId(42L);
        a.setAlertName("PodCrashLooping");
        a.setLevel("P2");
        a.setModule("k8s");
        a.setService("order-service");
        return a;
    }

    /** 生成一条会命中 alert() 的策略；dryRun/冷却/日上限/止配按需覆盖 */
    private static AutomationPolicy policyMatch(long id, boolean dryRun) {
        AutomationPolicy p = new AutomationPolicy();
        p.setId(id);
        p.setName("P2 崩溃自愈");
        p.setActionKey("mock.disk.cleanup");
        p.setEnvironment("prod");
        p.setMatchAlertLevels("P2,P3");
        p.setActionParams("{\"gracePeriodSeconds\":30,\"note\":\"hit ${service} #${alertId}\"}");
        p.setEnabled(true);
        p.setDryRun(dryRun);
        return p;
    }

    @Test
    @DisplayName("无启用策略：零构造零递交（引擎在场也如不在场）")
    void noPoliciesActsAsAbsent() {
        when(policyRepository.findEnabledInEvalOrder()).thenReturn(List.of());

        trigger.firePolicies(alert());

        verify(orchestrator, never()).handle(any());
        verify(executionRepository, never()).insert(any());
    }

    @Test
    @DisplayName("条件不命中：策略在列也不动（四条件 AND 的拒绝只需一环不符）")
    void unmatchedPolicyIsSkipped() {
        AutomationPolicy p = policyMatch(11L, false);
        p.setMatchModule("billing");      // alert.module=k8s → 不命中
        when(policyRepository.findEnabledInEvalOrder()).thenReturn(List.of(p));

        trigger.firePolicies(alert());

        verify(orchestrator, never()).handle(any());
        verify(executionRepository, never()).insert(any());
    }

    @Test
    @DisplayName("演练策略命中：只留 POLICY_DRYRUN 留痕行，执行链零调用")
    void dryRunPolicyLeavesTraceOnly() {
        when(policyRepository.findEnabledInEvalOrder()).thenReturn(List.of(policyMatch(12L, true)));

        trigger.firePolicies(alert());

        verify(orchestrator, never()).handle(any());
        ArgumentCaptor<HealingExecution> captor = ArgumentCaptor.forClass(HealingExecution.class);
        verify(executionRepository).insert(captor.capture());
        HealingExecution row = captor.getValue();
        assertEquals("POLICY_DRYRUN", row.gateDecision());
        assertEquals("REJECTED", row.status());
        assertEquals("mock.disk.cleanup", row.actionKey());
        assertEquals("order-service", row.target());
        assertEquals(42L, row.alertId());
        assertTrue(row.dryRunPlan().contains("policy #12"), row.dryRunPlan());
        // 演练回放材料：POLICY_MATCH + POLICY_DRYRUN 两节点
        verify(executionRepository).updateStepsJson(eq(7001L),
                org.mockito.ArgumentMatchers.argThat(j ->
                        j.contains("POLICY_MATCH") && j.contains("POLICY_DRYRUN")));
    }

    @Test
    @DisplayName("真策略命中：模板替换+溯源键齐备后构造 HealingAction 递交治理门")
    void enabledPolicyHandsToGateStack() {
        when(policyRepository.findEnabledInEvalOrder()).thenReturn(List.of(policyMatch(13L, false)));
        when(executionRepository.lastExecutionAt(anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());

        trigger.firePolicies(alert());

        ArgumentCaptor<HealingAction> captor = ArgumentCaptor.forClass(HealingAction.class);
        verify(orchestrator).handle(captor.capture());
        HealingAction action = captor.getValue();
        assertEquals("mock.disk.cleanup", action.actionKey());
        assertEquals("prod", action.environment());
        assertEquals("order-service", action.target());
        assertEquals(42L, action.alertId());
        assertEquals("auto", action.requestedBy());
        assertEquals(30, ((Number) action.params().get("gracePeriodSeconds")).intValue());
        assertEquals("hit order-service #42", action.params().get("note"),
                "${service}/${alertId} 模板必须就地展开");
        assertEquals(13L, action.params().get("__policyId"));
    }

    @Test
    @DisplayName("冷却中：窗口内动过手 → 静默跳过，且 stopOnMatch 不让位饿死后续策略")
    void coolingPolicySkipsAndContinues() {
        AutomationPolicy wide = policyMatch(14L, false);
        wide.setCooldownMinutes(60);
        wide.setStopOnMatch(true);
        AutomationPolicy narrow = policyMatch(15L, false);
        when(policyRepository.findEnabledInEvalOrder()).thenReturn(List.of(wide, narrow));
        when(executionRepository.lastExecutionAt(eq("mock.disk.cleanup"), eq("prod"), eq("order-service")))
                .thenReturn(Optional.of(LocalDateTime.now().minusMinutes(5)));

        trigger.firePolicies(alert());

        // wide 冷却跳过；narrow 接手递交（冷却不算「拿到单」，stopOnMatch 不生效）
        verify(orchestrator).handle(any());
    }

    @Test
    @DisplayName("日上限：今日已至上限 → 拒绝递交（防夜间执行风暴）")
    void dailyCapSkips() {
        AutomationPolicy p = policyMatch(16L, false);
        p.setMaxExecutionsPerDay(2);
        when(policyRepository.findEnabledInEvalOrder()).thenReturn(List.of(p));
        when(executionRepository.lastExecutionAt(anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(executionRepository.countSince(anyString(), anyString(), any())).thenReturn(2);

        trigger.firePolicies(alert());

        verify(orchestrator, never()).handle(any());
    }

    @Test
    @DisplayName("stopOnMatch：真拿到单的策略之后，后续策略不再求值")
    void stopOnMatchHaltsLaterPolicies() {
        AutomationPolicy first = policyMatch(17L, false);
        first.setStopOnMatch(true);
        first.setCooldownMinutes(1);   // 让它真的走一次冷却查询——否则 times(1) 无从验证「后续策略零求值」
        AutomationPolicy second = policyMatch(18L, false);
        when(policyRepository.findEnabledInEvalOrder()).thenReturn(List.of(first, second));
        when(executionRepository.lastExecutionAt(anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());

        trigger.firePolicies(alert());

        // 只递交一次；second 连冷却查询都未发生（求值被完全短路）
        verify(orchestrator, org.mockito.Mockito.times(1)).handle(any());
        verify(executionRepository, org.mockito.Mockito.times(1))
                .lastExecutionAt(anyString(), anyString(), anyString());
    }
}
