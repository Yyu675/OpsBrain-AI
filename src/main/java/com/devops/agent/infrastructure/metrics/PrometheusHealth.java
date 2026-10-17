package com.devops.agent.infrastructure.metrics;

/** 固定结构的 Prometheus 连通性探测结果；latencyMs/error 在不同状态下可为空。 */
public record PrometheusHealth(
        String baseUrl,
        boolean enabled,
        boolean reachable,
        Long latencyMs,
        String error
) {
}
