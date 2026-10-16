package com.devops.agent.domain.tools.executor;

import java.util.Map;

/**
 * 统一执行结果（D1 执行器适配器模式，2026-08-26）。
 *
 * <p>适配器执行动作后的统一返回形态：成功/失败 + 人类可读摘要（审计/展示）
 * + 结构化数据（前端渲染）。治理层与前端不感知具体目标系统的返回差异。</p>
 *
 * @param success      是否成功
 * @param summary      人类可读摘要（审计、通知、前端展示用）
 * @param data         结构化结果（前端渲染用；失败时可为空）
 * @param errorMessage 失败原因（成功时为空）
 */
public record OpsExecutionResult(
        boolean success,
        String summary,
        Map<String, Object> data,
        String errorMessage) {

    public static OpsExecutionResult ok(String summary, Map<String, Object> data) {
        return new OpsExecutionResult(true, summary, data, null);
    }

    public static OpsExecutionResult fail(String errorMessage) {
        return new OpsExecutionResult(false, errorMessage, Map.of(), errorMessage);
    }

    public boolean isSuccess() {
        return success;
    }
}
