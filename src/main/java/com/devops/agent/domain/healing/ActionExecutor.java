package com.devops.agent.domain.healing;

/**
 * 动作执行器抽象（S3-1，PRD D1 决策门的落地骨架）。
 * <p>
 * 与本仓离线基调同族的双轨设计：
 * <ul>
 *   <li><b>Mock 执行器永远可用</b>（{@code mock.*} 动作族）——演示、CI、
 *       治理链路联调都不依赖真实 K8s；</li>
 *   <li>真实执行器（K8s fabric8 只读 → 安全自愈）以可插拔 Bean 形态后补，
 *       注册进 {@link ExecutorRegistry} 即生效，编排层零感知。</li>
 * </ul>
 * </p>
 * <p>
 * 安全立场：执行器只负责「能不能、怎么做」。<b>该不该做</b>永远由
 * 治理门（白名单 + 风险策略 + 审批）在执行器被调用之前裁决——
 * 执行器实现里不允许也不必要再做一次策略判断。
 * </p>
 */
public interface ActionExecutor {

    /** 执行器标识（如 {@code mock}、{@code k8s-fabric8}），落库与审计用。 */
    String executorKey();

    /**
     * 能力声明：本执行器能否处理该 actionKey。
     * <p>注册表按此路由；多个执行器声明同一 actionKey 时先注册者胜出，
     * 真实执行器应排在 Mock 之前。</p>
     */
    boolean supports(String actionKey);

    /**
     * 该动作在本执行器下的权限等级。治理门对
     * {@link ActionPermissionLevel#DESTRUCTIVE_HIGH_RISK} 强制双人审批。
     */
    ActionPermissionLevel permissionLevel(String actionKey);

    /**
     * 演算（dry-run）：验证参数、生成执行计划，<b>绝不触碰目标系统</b>。
     * 审批单展示的「将要发生什么」来自此方法的 output。
     */
    ExecutionResult dryRun(HealingAction action);

    /** 真实执行。前置快照与撤销凭据随结果返回。 */
    ExecutionResult execute(HealingAction action);

    /** 是否支持撤销（回滚触发器需要）。默认不支持。 */
    default boolean undoSupported(String actionKey) {
        return false;
    }
}
