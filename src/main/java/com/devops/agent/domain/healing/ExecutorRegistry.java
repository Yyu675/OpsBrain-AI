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
