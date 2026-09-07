package com.devops.agent.domain.healing;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Mock 执行器（S3-1 双轨的离线一轨）——处理所有 {@code mock.*} 动作。
 * <p>
 * 职责与本仓 MockChatModel 同族：
 * <ul>
 *   <li>治理链路（白名单判定 → 审批 → 执行 → 快照/撤销）全程可联调，
 *       不需要真实 K8s 集群；</li>
 *   <li>CI 契约测试的稳定底座：真实 fabric8 执行器落地后，
 *       同一套编排逻辑用 Mock 走完回归；</li>
 *   <li>{@code dryRun} 与 {@code execute} 的返回结构永远是合法的
 *       {@link ExecutionResult}，编排层不需要为 Mock 写特判。</li>
 * </ul>
 * </p>
 */
@Component
public class MockActionExecutor implements ActionExecutor {

    public static final String EXECUTOR_KEY = "mock";
    private static final String ACTION_PREFIX = "mock.";

    @Override
    public String executorKey() {
        return EXECUTOR_KEY;
    }

    @Override
    public boolean supports(String actionKey) {
        return actionKey != null && actionKey.startsWith(ACTION_PREFIX);
    }

    @Override
    public ActionPermissionLevel permissionLevel(String actionKey) {
        // Mock 只承诺「安全自愈」等级；演示高危动作时由真实执行器另行声明
        return ActionPermissionLevel.SAFE_AUTO_HEALING;
    }

    @Override
    public ExecutionResult dryRun(HealingAction action) {
        return ExecutionResult.dryRunOk(EXECUTOR_KEY, action.actionKey(),
                "MOCK 演算通过：将在环境「" + action.environment() + "」对目标「"
                        + action.target() + "」执行 " + action.actionKey()
                        + "，参数=" + action.params());
    }

    @Override
    public ExecutionResult execute(HealingAction action) {
        // 前置快照：把入参原样回声，让撤销链路有可回放的载体
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("target", action.target());
        snapshot.put("environment", action.environment());
        snapshot.put("paramsEcho", action.params());
        String undoToken = "mock-undo-" + UUID.randomUUID().toString().substring(0, 8);
        return ExecutionResult.ok(EXECUTOR_KEY, action.actionKey(),
                "MOCK 执行成功：" + action.actionKey() + " @ " + action.target(),
                snapshot, undoToken);
    }

    @Override
    public boolean undoSupported(String actionKey) {
        return true;
    }

    @Override
    public ExecutionResult undo(HealingAction action, String undoToken,
                                Map<String, Object> preSnapshot) {
        // Mock 撤销：回显凭据与快照，证明链路通了
        return ExecutionResult.ok(EXECUTOR_KEY, action.actionKey(),
                "MOCK 撤销成功：" + action.actionKey() + " @ " + action.target()
                        + "（凭据 " + undoToken + " 已消费）",
                Map.of(), null);
    }
}
