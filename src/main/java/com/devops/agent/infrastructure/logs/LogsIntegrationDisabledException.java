package com.devops.agent.infrastructure.logs;

/**
 * Loki 集成未启用（配置态，非运行故障）。
 * 列入 logs 熔断器 ignore-exceptions——部署形态不该吃掉失败预算
 * （与 metrics 侧 MetricsIntegrationDisabledException 同构）。
 */
public class LogsIntegrationDisabledException extends LogsUnavailableException {

    public LogsIntegrationDisabledException(String message) {
        super(message);
    }
}
