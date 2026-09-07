package com.devops.agent.domain.healing;

import com.devops.agent.domain.governance.AutomationGovernanceService;
import com.devops.agent.domain.governance.GovernanceViews;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * S3-1 批次 1 核心闭环：注册表路由、Mock 执行器契约、治理门四种裁决
 * 及破坏性权限的硬性收紧。纯 JUnit + Mockito，不依赖 Spring 容器。
 */
@DisplayName("healing 核心（S3-1 批次 1：执行器抽象 + 治理门）")
class HealingCoreTest {

    private static HealingAction action(String key) {
        return new HealingAction(key, "prod", "ns:prod/app-user",
                Map.of("gracePeriodSeconds", 30), 42L, "auto", Instant.now());
    }

    // ---------- 注册表路由 ----------

    @Test
    @DisplayName("注册表路由：mock.* 命中 Mock 执行器，未知动作返回 empty（拒绝语义）")
    void registryRoutesByDeclaration() {
        ExecutorRegistry registry = new ExecutorRegistry(List.of(new MockActionExecutor()));

        assertTrue(registry.locate("mock.disk.cleanup").isPresent());
        assertTrue(registry.locate("k8s.pod.restart").isEmpty(),
                "真实动作在 fabric8 执行器落地前必须查无执行器");
        assertEquals(List.of("mock"), registry.registeredExecutorKeys());
    }

    // ---------- Mock 执行器契约 ----------

    @Test
    @DisplayName("Mock 执行器：dryRun 不动真、execute 带快照与撤销凭据")
    void mockExecutorContract() {
        MockActionExecutor executor = new MockActionExecutor();
        HealingAction act = action("mock.disk.cleanup");

        ExecutionResult dry = executor.dryRun(act);
        assertTrue(dry.success());
        assertTrue(dry.dryRun(), "dryRun 结果 must 标记 dryRun=true（审批展示与审计靠它区分）");
        assertNotNull(dry.output());

        ExecutionResult real = executor.execute(act);
        assertTrue(real.success());
        assertFalse(real.dryRun());
        assertNotNull(real.undoToken(), "执行成功必须给出撤销凭据供回滚触发器使用");
        assertEquals("ns:prod/app-user", real.preSnapshot().get("target"),
                "前置快照必须携带目标标识，撤销时可回放");
        assertTrue(executor.undoSupported(act.actionKey()));
        assertEquals(ActionPermissionLevel.SAFE_AUTO_HEALING,
                executor.permissionLevel(act.actionKey()));
    }

    // ---------- 治理门裁决 ----------

    private HealingGate gateWith(GovernanceViews.EvaluateResult eval, List<ActionExecutor> executors) {
        AutomationGovernanceService governance = mock(AutomationGovernanceService.class);
        when(governance.evaluate(anyString(), anyString())).thenReturn(eval);
        return new HealingGate(governance, new ExecutorRegistry(executors));
    }

    @Test
    @DisplayName("门裁决：白名单免审批 → AUTO_EXECUTE，携带生效的爆炸半径与冷却")
    void gateAutoExecute() {
        HealingGate gate = gateWith(
                GovernanceViews.EvaluateResult.allow("mock.disk.cleanup", "prod",
                        false, "NONE", 3, 300),
                List.of(new MockActionExecutor()));

        var decision = gate.decide(action("mock.disk.cleanup"));
        assertEquals(HealingGate.DecisionType.AUTO_EXECUTE, decision.type());
        assertEquals("mock", decision.executorKey());
        assertEquals(3, decision.blastRadiusCount());
        assertEquals(300, decision.cooldownSeconds());
    }

    @Test
    @DisplayName("门裁决：白名单要求审批 → REQUIRES_APPROVAL 模式透传")
    void gateRequiresApproval() {
        HealingGate gate = gateWith(
                GovernanceViews.EvaluateResult.allow("mock.pod.restart", "prod",
                        true, "SINGLE", 1, 600),
                List.of(new MockActionExecutor()));

        var decision = gate.decide(action("mock.pod.restart"));
        assertEquals(HealingGate.DecisionType.REQUIRES_APPROVAL, decision.type());
        assertEquals("SINGLE", decision.approvalMode());
    }

    @Test
    @DisplayName("门裁决：白名单未登记/策略拒绝 → DENIED，原因必须人类可读")
    void gateDenied() {
        HealingGate gate = gateWith(
                GovernanceViews.EvaluateResult.deny("mock.disk.cleanup", "prod",
                        "该动作未登记在白名单中"),
                List.of(new MockActionExecutor()));

        var decision = gate.decide(action("mock.disk.cleanup"));
        assertEquals(HealingGate.DecisionType.DENIED, decision.type());
        assertTrue(decision.reason().contains("白名单"));
    }

    @Test
    @DisplayName("门裁决：查无执行器 → NO_EXECUTOR，先于白名单判定短路")
    void gateNoExecutorShortCircuits() {
        AutomationGovernanceService governance = mock(AutomationGovernanceService.class);
        // 故意不 stub evaluate：若门先查白名单则会拿到 null 并 NPE——NO_EXECUTOR 短路应让它根本不被依赖
        HealingGate gate = new HealingGate(governance,
                new ExecutorRegistry(List.of(new MockActionExecutor())));

        var decision = gate.decide(action("k8s.pod.restart"));
        assertEquals(HealingGate.DecisionType.NO_EXECUTOR, decision.type());
        assertTrue(decision.reason().contains("k8s.pod.restart"));
    }

    @Test
    @DisplayName("门裁决：破坏性执行器即使白名单免审批，也被强制提为双人审批")
    void gateDestructiveOverride() {
        // 声明一个破坏性动作的假执行器
        ActionExecutor dangerous = new ActionExecutor() {
            @Override public String executorKey() { return "danger"; }
            @Override public boolean supports(String actionKey) { return "danger.wipe".equals(actionKey); }
            @Override public ActionPermissionLevel permissionLevel(String actionKey) {
                return ActionPermissionLevel.DESTRUCTIVE_HIGH_RISK;
            }
            @Override public ExecutionResult dryRun(HealingAction a) { return null; }
            @Override public ExecutionResult execute(HealingAction a) { return null; }
        };
        HealingGate gate = gateWith(
                GovernanceViews.EvaluateResult.allow("danger.wipe", "prod",
                        false, "NONE", 10, 0),
                List.of(dangerous, new MockActionExecutor()));

        var decision = gate.decide(action("danger.wipe"));
        assertEquals(HealingGate.DecisionType.REQUIRES_APPROVAL, decision.type());
        assertEquals("DUAL", decision.approvalMode(),
                "破坏性动作的审批模式必须被硬性提升为 DUAL，配置放松不了");
    }
}
