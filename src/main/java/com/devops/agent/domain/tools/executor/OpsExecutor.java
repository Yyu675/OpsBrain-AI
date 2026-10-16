package com.devops.agent.domain.tools.executor;

import java.util.Set;

/**
 * 运维执行器统一接口（D1 执行器适配器模式，2026-08-26）。
 *
 * <p>适配器模式核心：治理链路（白名单 / 风险分级 / 审批 / 记录 / 审计）
 * 只依赖本接口，不感知目标系统。目标系统选型只影响具体实现类：</p>
 * <ul>
 *   <li>{@code K8sOpsExecutor}——K8s（fabric8），第一个实现（V1.2 queryPodStatus）</li>
 *   <li>{@code CloudOpsExecutor}——云厂商 API（预留，FinOps）</li>
 *   <li>{@code ScriptOpsExecutor}——内部脚本平台（预留）</li>
 * </ul>
 *
 * <p>安全约定：</p>
 * <ul>
 *   <li>只读动作无副作用，免审批；写动作必须由治理层审批后调用；</li>
 *   <li>写动作的爆炸半径（namespace 限定 / 单实例）由命令参数承载，治理层校验；</li>
 *   <li>凭证由各适配器自管（KubeConfig / AK-SK / 平台令牌），不进仓库。</li>
 * </ul>
 */
public interface OpsExecutor {

    /** 目标系统标识（注册表键），如 k8s / aliyun / script */
    String targetSystem();

    /** 支持的动作白名单（供校验与 UI 展示），如 queryPodStatus / restartPod */
    Set<String> supportedActions();

    /**
     * 执行动作。
     *
     * @param command 统一命令（action + params + targetSystem）
     * @return 统一结果（成功/失败 + 摘要 + 结构化数据）
     * @throws IllegalArgumentException 参数缺失或动作不支持时
     */
    OpsExecutionResult execute(OpsCommand command);

    /** 该动作是否有补偿动作（写操作需在 @ToolMeta.compensationAction 声明） */
    default boolean supportsCompensation(String action) {
        return false;
    }
}
