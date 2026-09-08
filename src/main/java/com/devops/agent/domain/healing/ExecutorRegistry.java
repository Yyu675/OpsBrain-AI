package com.devops.agent.domain.healing;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 执行器注册表（S3-1）：actionKey → 执行器的路由中枢。
 * <p>
 * Spring 会把所有 {@link ActionExecutor} 实现按 @Order/声明顺序注入
 * {@code List}，本类按声明顺序匹配第一个 {@code supports(actionKey)}
 * 为 true 的执行器。<b>真实执行器必须先于 Mock</b>——Mock 只声明
 * {@code mock.*} 前缀，天然不会抢占真实动作。
 * </p>
 * <p>
 * 查不到执行器 = 拒绝（返回 {@link Optional#empty()}），与治理门
 * 「白名单未登记 = 拒绝」同一默认安全语义。
 * </p>
 */
@Component
public class ExecutorRegistry {

    private final List<ActionExecutor> executors;

    public ExecutorRegistry(List<ActionExecutor> executors) {
        // 注册强契约（路线图 §7.1 3-1.4 折中版，报告 109/110 记录）：
        // 声明非只读最高权限（SAFE_AUTO_HEALING / DESTRUCTIVE_HIGH_RISK）的
        // 执行器必须具备撤销能力，否则启动即失败——宁可起不来，
        // 不让一只「不可撤销的手」摸到生产系统。只读执行器（PRD D1 的
        // V1.2 K8s 只读轨）显式声明 READ_ONLY_DIAGNOSTIC 豁免。
        for (ActionExecutor executor : executors) {
            if (executor.maxPermissionLevel() != ActionPermissionLevel.READ_ONLY_DIAGNOSTIC
                    && !executor.undoCapable()) {
                throw new IllegalStateException(
                        "执行器「" + executor.executorKey() + "」声明最高权限 "
                                + executor.maxPermissionLevel() + " 但不具备撤销能力（undoCapable=false），"
                                + "违反注册强契约：非只读执行器必须可撤销");
            }
        }
        this.executors = List.copyOf(executors);
    }

    /** 路由到第一个声明支持该动作的执行器；无声明者返回 empty。 */
    public Optional<ActionExecutor> locate(String actionKey) {
        return executors.stream()
                .filter(e -> e.supports(actionKey))
                .findFirst();
    }

    /** 已注册的执行器标识列表（管理页/健康检查展示用）。 */
    public List<String> registeredExecutorKeys() {
        return executors.stream().map(ActionExecutor::executorKey).toList();
    }
}
