package com.devops.agent.domain.healing;

import java.util.Map;

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

    /**
     * 撤销一次已成功的执行（批次 3：手动撤销入口 + 后续监控联动回滚触发器共用）。
     *
     * @param action      原始执行动作（从台账回放）
     * @param undoToken   执行时返回的撤销凭据
     * @param preSnapshot 执行前快照（V7 台账 pre_snapshot_json 反序列化值）
     * @return 撤销结果；默认实现返回失败（与 {@code undoSupported=false} 自洽）
     */
    default ExecutionResult undo(HealingAction action, String undoToken,
                                 Map<String, Object> preSnapshot) {
        return ExecutionResult.fail(executorKey(), action.actionKey(), false,
                "执行器「" + executorKey() + "」不支持撤销 " + action.actionKey());
    }

    /**
     * 执行器级撤销能力声明（注册强契约的判定依据，批次 5）。
     * <p>
     * 与按动作细分的 {@link #undoSupported(String)} 的区别：本方法是
     * 「这只手到底有没有撤销能力」的总开关，供注册表在<b>启动期</b>校验；
     * 运行期撤销仍按 undoSupported(actionKey) 细粒度裁决。
     * </p>
     *
     * @return 默认 true——声明「我的全部写动作都可撤销」
     */
    default boolean undoCapable() {
        return true;
    }

    /**
     * 本执行器触达的最高权限等级（注册强契约的判定依据，批次 5）。
     * <p>
     * 契约（3-1.4 折中，报告 109 记录）：
     * <b>声明非只读最高权限的执行器必须 undoCapable()=true</b>，否则
     * 注册表启动即抛异常。只读执行器（PRD D1 的 V1.2 K8s 只读轨）
     * 天然没有撤销语义，显式声明 READ_ONLY 即可豁免。
     * </p>
     */
    default ActionPermissionLevel maxPermissionLevel() {
        return ActionPermissionLevel.SAFE_AUTO_HEALING;
    }
}
