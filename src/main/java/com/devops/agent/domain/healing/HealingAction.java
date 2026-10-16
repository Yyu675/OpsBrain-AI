package com.devops.agent.domain.healing;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 自愈动作（S3-1，PRD §九 L4 受控自愈的执行请求）。
 * <p>
 * record 字段名即 JSON 键名，与本仓证据/假设台账同风格——执行全程可回放。
 * </p>
 *
 * @param actionKey    语言无关的原子动作标识（如 {@code k8s.pod.restart}、
 *                     {@code mock.disk.cleanup}），与
 *                     {@code sys_action_allowlist.actionKey} 严格对齐——
 *                     治理门（AutomationGovernanceService.evaluate）以它为键
 * @param environment  目标环境（如 prod / staging），治理门按环境取交集判定
 * @param target       目标资源标识（如 {@code ns:prod/app-user}）
 * @param params       动作参数（执行器各自解释；落库前由白名单 paramSchema 校验）
 * @param alertId      触发该动作的告警 id（可空：手工触发的动作为 null）
 * @param requestedBy  发起人（{@code auto} 表示引擎自动闭环，否则为操作员账号）
 * @param requestedAt  发起时间
 */
public record HealingAction(
        String actionKey,
        String environment,
        String target,
        Map<String, Object> params,
        Long alertId,
        String requestedBy,
        Instant requestedAt) {

    /** 紧凑构造：params 归一为非 null 的 LinkedHashMap（防御外部传入 null）。 */
    public HealingAction {
        params = params == null ? new LinkedHashMap<>() : new LinkedHashMap<>(params);
    }
}
