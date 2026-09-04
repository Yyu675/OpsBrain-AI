package com.devops.agent.infrastructure.metrics;

/**
 * 指标数据源不可用（L2）。
 *
 * <h3>为什么单独定义而不复用 RuntimeException</h3>
 * 它需要被 {@code GlobalExceptionHandler} 映射成一个<b>明确的业务码</b>，
 * 让前端能区分两种失败：
 * <ul>
 *   <li><b>本异常</b> —— 「Prometheus 连不上/超时」。这是环境问题，
 *       页面应提示「监控数据源不可用」并给出接入管理的入口，
 *       重试通常无效；</li>
 *   <li>其他 5xx —— OpsBrain 自身故障，重试可能有效。</li>
 * </ul>
 * 混为一谈会让用户对着「服务内部异常」反复刷新，
 * 而真正该做的是去看 Prometheus 是不是挂了。
 *
 * <p>刻意<b>不</b>继承业务异常体系里表示「请求非法」的那几类：
 * 数据源不可用不是用户的错，不该返回 4xx。</p>
 *
 * @author OpsBrain AI
 * @since 2026-08-25
 */
public class MetricsUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public MetricsUnavailableException(String message) {
        super(message);
    }

    public MetricsUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
