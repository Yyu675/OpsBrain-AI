package com.devops.agent.domain.tools.executor;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 执行器注册表（D1 执行器适配器模式，2026-08-26）。
 *
 * <p>按 targetSystem 注册/解析执行器。Spring 注入的每个 {@link OpsExecutor}
 * 在构造时自注册；{@code resolve} 未命中时抛明确错误（「未配置目标系统」），
 * 保证未配置集群时系统不崩溃、动作有明确提示。</p>
 */
@Component
public class OpsExecutorRegistry {

    private final Map<String, OpsExecutor> executors = new ConcurrentHashMap<>();

    /** 注册执行器（幂等：同 targetSystem 后注册者覆盖，便于测试注入 Fake） */
    public void register(OpsExecutor executor) {
        if (executor == null || executor.targetSystem() == null || executor.targetSystem().isBlank()) {
            throw new IllegalArgumentException("执行器 targetSystem 不能为空");
        }
        executors.put(executor.targetSystem(), executor);
    }

    /** 解析目标系统对应的执行器；未注册抛明确错误 */
    public OpsExecutor resolve(String targetSystem) {
        OpsExecutor executor = executors.get(targetSystem);
        if (executor == null) {
            throw new IllegalArgumentException(
                    "未配置目标系统执行器: " + targetSystem + "（已注册: " + executors.keySet() + "）");
        }
        return executor;
    }

    /** 已注册的目标系统集合 */
    public Set<String> registeredSystems() {
        return executors.keySet();
    }

    /** 是否已注册某目标系统 */
    public boolean has(String targetSystem) {
        return executors.containsKey(targetSystem);
    }
}
