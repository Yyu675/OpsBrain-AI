package com.devops.agent.infrastructure.logs;

/**
 * 日志数据源运行期不可用（不可达/超时/错误响应/熔断打开）。
 * 与部署形态（未启用）严格区分——采集器据此分流 FAILED 与 UNAVAILABLE。
 * 谱系与 metrics/MetricsUnavailableException 完全同构。
 */
public class LogsUnavailableException extends RuntimeException {

    public LogsUnavailableException(String message) {
        super(message);
    }

    public LogsUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
