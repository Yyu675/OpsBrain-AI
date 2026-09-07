package com.devops.agent.domain.healing;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 执行后验证器（S3-3/§7.4 3-4.1）：回答「这次自愈到底好了没有」。
 * <p>
 * 立场（路线图 §7.4 原文）：没有验证的自动执行 = 蒙眼开车。
 * 「修复后错误率是否下降 / Pod 是否就绪」是判断自愈成功的唯一标准，
 * 执行器返回 success 只代表「动作发出去了」，不代表「病灶没了」。
 * </p>
 */
public interface ActionVerifier {

    /** 验证器标识（如 {@code mock}、{@code k8s-readiness}）。 */
    String verifierKey();

    /** 能力声明：本验证器能否验证该 actionKey 的执行结果。 */
    boolean supports(String actionKey);

    /**
     * 对一次已 SUCCEEDED 的执行做后验验证。
     *
     * @param action    原始动作（台账回放）
     * @param execution 执行时返回的结果（含 preSnapshot 与 undoToken）
     * @return 验证结论；实现自身失败（如集群不可达）应返回
     *         {@link VerificationResult#unknown} 而不是健康或病态——
     *         「验证不了」和「验证失败」是两种事实
     */
    VerificationResult verify(HealingAction action, ExecutionResult execution);

    /**
     * 验证结论。before/after 双指标组是前端「执行前后对比」的数据源（3-4.4）。
     *
     * @param status    HEALTHY / UNHEALTHY / UNKNOWN
     * @param summary   人类可读结论（进台账 verify_result_json 与工单）
     * @param before    执行前指标组（来自执行快照）
     * @param after     执行后指标组（来自本次采集）
     * @param verifiedAt 验证时间
     */
    record VerificationResult(
            String status,
            String summary,
            Map<String, Object> before,
            Map<String, Object> after,
            Instant verifiedAt) {

        public static final String HEALTHY = "HEALTHY";
        public static final String UNHEALTHY = "UNHEALTHY";
        public static final String UNKNOWN = "UNKNOWN";

        public VerificationResult {
            before = before == null ? new LinkedHashMap<>() : new LinkedHashMap<>(before);
            after = after == null ? new LinkedHashMap<>() : new LinkedHashMap<>(after);
        }

        public static VerificationResult healthy(String summary,
                                                 Map<String, Object> before,
                                                 Map<String, Object> after) {
            return new VerificationResult(HEALTHY, summary, before, after, Instant.now());
        }

        public static VerificationResult unhealthy(String summary,
                                                   Map<String, Object> before,
                                                   Map<String, Object> after) {
            return new VerificationResult(UNHEALTHY, summary, before, after, Instant.now());
        }

        public static VerificationResult unknown(String summary) {
            return new VerificationResult(UNKNOWN, summary, Map.of(), Map.of(), Instant.now());
        }

        public boolean isHealthy() {
            return HEALTHY.equals(status);
        }
    }
}
