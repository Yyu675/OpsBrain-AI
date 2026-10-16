package com.devops.agent.infrastructure.metrics;

/**
 * Prometheus 集成未启用（L2，S0-3 熔断适配）。
 *
 * <h3>为什么从 {@link MetricsUnavailableException} 分出一个子类</h3>
 * 「集成被配置关掉」是<b>配置状态</b>，不是<b>数据源健康度</b>：
 * 熔断器窗口若把这种即时失败也计数，一个被刻意禁用的实例会被误判为
 * 「连续失败」而打开熔断，健康页显示的「熔断中」就成了误导。
 * 因此本异常被 {@code PrometheusClient} 上 {@code @CircuitBreaker} 的
 * {@code ignoreExceptions} 排除在统计之外。
 *
 * <p>继承而非并列新建：{@code GlobalExceptionHandler} 已按父类做业务码映射，
 * 继承复用同一条映射链，调用方与前端<b>零感知</b>。</p>
 *
 * @author OpsBrain AI
 * @since 2026-09-07（S0-3）
 */
public class MetricsIntegrationDisabledException extends MetricsUnavailableException {

    private static final long serialVersionUID = 1L;

    public MetricsIntegrationDisabledException(String message) {
        super(message);
    }
}
