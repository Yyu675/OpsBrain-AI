package com.devops.agent.infrastructure.llm;

/**
 * LLM 应用侧限流拒绝（S0-3）。
 *
 * <h3>为什么单独定义类型</h3>
 * 「应用主动限流」与「云侧 429」必须可区分：前者是本应用自己的保护动作
 * （调 resilience4j 配置即可恢复），后者是供应商在拒绝（要降载或升配）。
 * 混在一个笼统异常里，排障时会去错地方。单独类型让日志与告警一眼分流。
 *
 * @author OpsBrain AI
 * @since 2026-09-07（S0-3）
 */
public class LlmRateLimitedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public LlmRateLimitedException(String message, Throwable cause) {
        super(message, cause);
    }

    public LlmRateLimitedException(String message) {
        super(message);
    }
}
