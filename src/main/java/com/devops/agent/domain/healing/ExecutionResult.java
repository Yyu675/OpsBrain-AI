package com.devops.agent.domain.healing;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 执行结果（S3-1）：dryRun / execute / undo 三种调用共用的返回值。
 * <p>
 * 关键字段：
 * <ul>
 *   <li>{@code preSnapshot} —— 执行前快照（PRD §九「自愈回滚触发器」的
 *       Snapshot_Before_Healing 载体），撤销时回传给 {@code undo}；</li>
 *   <li>{@code undoToken} —— 撤销凭据（如 rollout 的历史版本号），
 *       为 null 表示该次执行不可撤销；</li>
 *   <li>{@code dryRun} —— 本次是否只演算不落地；审批展示与真实执行
 *       的审计记录靠它区分。</li>
 * </ul>
 * </p>
 */
public record ExecutionResult(
        String executorKey,
        String actionKey,
        boolean success,
        boolean dryRun,
        String output,
        String error,
        Map<String, Object> preSnapshot,
        String undoToken,
        Instant startedAt,
        Instant finishedAt) {

    /** 紧凑构造：preSnapshot 归一为非 null。 */
    public ExecutionResult {
        preSnapshot = preSnapshot == null ? new LinkedHashMap<>() : new LinkedHashMap<>(preSnapshot);
    }

    /** 演算成功（未触碰目标系统）。 */
    public static ExecutionResult dryRunOk(String executorKey, String actionKey, String plan) {
        return new ExecutionResult(executorKey, actionKey, true, true,
                plan, null, Map.of(), null, Instant.now(), Instant.now());
    }

    /** 真实执行成功。 */
    public static ExecutionResult ok(String executorKey, String actionKey, String output,
                                     Map<String, Object> preSnapshot, String undoToken) {
        return new ExecutionResult(executorKey, actionKey, true, false,
                output, null, preSnapshot, undoToken, Instant.now(), Instant.now());
    }

    /** 失败（dryRun 或真实执行均可）。 */
    public static ExecutionResult fail(String executorKey, String actionKey, boolean dryRun,
                                       String error) {
        return new ExecutionResult(executorKey, actionKey, false, dryRun,
                null, error, Map.of(), null, Instant.now(), Instant.now());
    }
}
