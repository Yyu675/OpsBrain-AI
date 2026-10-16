package com.devops.agent.domain.tools.executor;

import java.util.Map;

/**
 * 统一执行命令模型（D1 执行器适配器模式，2026-08-26）。
 *
 * <p>所有目标系统（K8s / 云厂商 API / 内部脚本平台）的动作都归一为
 * 同一个命令形态，使治理链路（白名单 / 风险分级 / 审批 / 记录 / 审计）
 * 只依赖 {@link OpsExecutor} 接口，不感知具体目标系统。</p>
 *
 * @param action      动作标识，如 queryPodStatus / restartPod / logCleanup
 * @param params      动作参数（namespace / pod / path 等）
 * @param targetSystem 目标系统标识，如 k8s / aliyun / script
 */
public record OpsCommand(
        String action,
        Map<String, String> params,
        String targetSystem) {

    /**
     * 读取必填参数，缺失抛 IllegalArgumentException（由适配器在动作执行前校验）。
     *
     * @param key 参数名
     * @return 参数值
     * @throws IllegalArgumentException 参数缺失时
     */
    public String requireParam(String key) {
        String v = params == null ? null : params.get(key);
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException(
                    "动作 [" + action + "] 缺少必填参数: " + key + "（目标系统: " + targetSystem + "）");
        }
        return v;
    }

    /** 便捷工厂：构建无参数的命令 */
    public static OpsCommand of(String targetSystem, String action) {
        return new OpsCommand(action, Map.of(), targetSystem);
    }

    /** 便捷工厂：构建带参数的命令 */
    public static OpsCommand of(String targetSystem, String action, Map<String, String> params) {
        return new OpsCommand(action, params == null ? Map.of() : params, targetSystem);
    }
}
