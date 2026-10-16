package com.devops.agent.domain.healing;

import com.devops.agent.domain.governance.AutomationGovernanceService;
import com.devops.agent.domain.governance.GovernanceViews;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 自愈治理门（S3-1）：执行前唯一的准入裁决点。
 * <p>
 * 裁决顺序（任一拒绝即短路）：
 * <ol>
 *   <li><b>执行器存在性</b>：注册表查不到该 actionKey 的执行器 →
 *       NO_EXECUTOR（「没有手」比「不该动」更早拦截，避免审批单
 *       发给一个根本执行不了的动作）；</li>
 *   <li><b>白名单 + 风险策略</b>：复用
 *       {@link AutomationGovernanceService#evaluate}（未登记 = 拒绝、
 *       停用 = 拒绝、环境未开放 = 拒绝、策略缺失 = 拒绝）；</li>
 *   <li><b>执行器权限收紧</b>：执行器声明
 *       {@link ActionPermissionLevel#DESTRUCTIVE_HIGH_RISK} 时，无论
 *       白名单策略多宽松，强制提升为双人审批；</li>
 *   <li><b>审批需求</b>：requiresApproval 或 approvalMode≠NONE →
 *       REQUIRES_APPROVAL，否则 AUTO_EXECUTE。</li>
 * </ol>
 * </p>
 * <p>
 * 本类只判门，不创建审批单、不落库——那是批次 2 编排器的职责。
 * </p>
 */
@Service
public class HealingGate {

    private final AutomationGovernanceService governanceService;
    private final ExecutorRegistry executorRegistry;

    public HealingGate(AutomationGovernanceService governanceService,
                       ExecutorRegistry executorRegistry) {
        this.governanceService = governanceService;
        this.executorRegistry = executorRegistry;
    }

    /** 对动作做准入裁决。 */
    public GateDecision decide(HealingAction action) {
        // 1. 执行器存在性
        Optional<ActionExecutor> executorOpt = executorRegistry.locate(action.actionKey());
        if (executorOpt.isEmpty()) {
            return GateDecision.noExecutor(action.actionKey(),
                    "注册表中没有声明支持「" + action.actionKey() + "」的执行器"
                            + "（当前已注册：" + executorRegistry.registeredExecutorKeys() + "）");
        }
        ActionExecutor executor = executorOpt.get();

        // 2. 白名单 + 风险策略（复用治理服务的完整判定链）
        GovernanceViews.EvaluateResult eval =
                governanceService.evaluate(action.actionKey(), action.environment());
        if (!eval.allowed()) {
            return GateDecision.denied(action.actionKey(), eval.reason());
        }

        // 3. 执行器权限收紧：破坏性动作至少双人审批，任何配置都放松不了
        ActionPermissionLevel permission = executor.permissionLevel(action.actionKey());
        String approvalMode = eval.approvalMode();
        boolean requiresApproval = Boolean.TRUE.equals(eval.requiresApproval())
                || (approvalMode != null && !"NONE".equals(approvalMode));
        if (permission == ActionPermissionLevel.DESTRUCTIVE_HIGH_RISK) {
            requiresApproval = true;
            approvalMode = "DUAL";
        }

        // 4. 终审
        if (requiresApproval) {
            return GateDecision.needsApproval(action.actionKey(),
                    approvalMode == null ? "SINGLE" : approvalMode,
                    executor.executorKey(), eval.blastRadiusCount(), eval.cooldownSeconds());
        }
        return GateDecision.auto(action.actionKey(), executor.executorKey(),
                eval.blastRadiusCount(), eval.cooldownSeconds());
    }

    /**
     * 门裁决结果。record 字段名即 JSON 键名。
     *
     * @param type             裁决类型
     * @param actionKey        被裁决动作
     * @param reason           人类可读原因（拒绝/无执行器时必有值）
     * @param approvalMode     要求的审批模式（REQUIRES_APPROVAL 时有效：SINGLE/DUAL）
     * @param executorKey      将承接该动作的执行器（拒绝类裁决为 null）
     * @param blastRadiusCount 生效的爆炸半径上限
     * @param cooldownSeconds  生效的冷却时间
     */
    public record GateDecision(
            DecisionType type,
            String actionKey,
            String reason,
            String approvalMode,
            String executorKey,
            Integer blastRadiusCount,
            Integer cooldownSeconds) {

        public static GateDecision auto(String actionKey, String executorKey,
                                        Integer blastRadius, Integer cooldown) {
            return new GateDecision(DecisionType.AUTO_EXECUTE, actionKey,
                    "门裁决通过：免审批，可自动执行", null, executorKey, blastRadius, cooldown);
        }

        public static GateDecision needsApproval(String actionKey, String mode, String executorKey,
                                                 Integer blastRadius, Integer cooldown) {
            return new GateDecision(DecisionType.REQUIRES_APPROVAL, actionKey,
                    "门裁决通过：需人工审批（" + mode + "）", mode, executorKey, blastRadius, cooldown);
        }

        public static GateDecision denied(String actionKey, String reason) {
            return new GateDecision(DecisionType.DENIED, actionKey,
                    reason, null, null, null, null);
        }

        public static GateDecision noExecutor(String actionKey, String reason) {
            return new GateDecision(DecisionType.NO_EXECUTOR, actionKey,
                    reason, null, null, null, null);
        }
    }

    /** 裁决类型枚举。 */
    public enum DecisionType {
        /** 免审批，编排层可直接调执行器 */
        AUTO_EXECUTE,
        /** 需人工审批，编排层转审批流 */
        REQUIRES_APPROVAL,
        /** 治理策略拒绝（白名单/环境/策略缺失） */
        DENIED,
        /** 没有能承接该动作的执行器 */
        NO_EXECUTOR
    }
}
