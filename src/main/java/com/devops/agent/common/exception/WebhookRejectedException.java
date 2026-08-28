package com.devops.agent.common.exception;

import com.devops.agent.common.dto.ApiCode;

/**
 * Webhook 请求被拒绝（鉴权失败或触发限流）。
 *
 * <p>独立于 {@link SecurityGuardException}：后者统一映射为 HTTP 403，
 * 而 webhook 调用方是 Alertmanager 这类<b>会按状态码决定重试行为</b>的机器客户端，
 * 需要区分「401 配置错误，改配置才有用」与「429 太快了，退避后重试」。
 * 用同一个 403 会让 Alertmanager 无法做出正确反应。</p>
 *
 * @author OpsBrain AI
 * @since 2026-08-24
 */
public class WebhookRejectedException extends RuntimeException {

    /** 业务码 */
    private final int code;

    /** 期望的 HTTP 状态码 */
    private final int httpStatus;

    /** 建议重试等待秒数；<= 0 表示不建议重试（如鉴权失败） */
    private final int retryAfterSeconds;

    private WebhookRejectedException(int code, int httpStatus, int retryAfterSeconds, String message) {
        super(message);
        this.code = code;
        this.httpStatus = httpStatus;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    /**
     * 鉴权失败 → 401。
     * <p>不建议重试：密钥不对时重试多少次都一样，属于配置问题，
     * 应当让它在 Alertmanager 侧显式报错以便被发现。</p>
     */
    public static WebhookRejectedException unauthorized() {
        return new WebhookRejectedException(ApiCode.WEBHOOK_UNAUTHORIZED, 401, 0, "Webhook 鉴权失败");
    }

    /**
     * 触发限流 → 429 + Retry-After。
     * <p>选择 429 而非「返回 200 静默丢弃」：对运维平台而言，
     * <b>悄悄丢掉告警比慢一点收到告警危险得多</b>。429 + Retry-After
     * 会让 Alertmanager 退避后重投，告警最终不丢。</p>
     */
    public static WebhookRejectedException rateLimited(int retryAfterSeconds) {
        return new WebhookRejectedException(ApiCode.RATE_LIMITED, 429, Math.max(1, retryAfterSeconds),
                "告警推送过于频繁，请退避后重试");
    }

    public int getCode() {
        return code;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public int getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
