package com.devops.agent.domain.tools;

/**
 * Tool 失败分类标准化
 * <p>
 * 参考 Agent Methodology §9.2：不同错误要有不同处理，不要一个 catch all。
 * 运行时由 {@link ToolRuntimeManager} 根据异常类型自动分类，决定重试/补偿/告警策略。
 * </p>
 *
 * @author OpsBrain AI
 * @since 2026-08-08
 */
public enum ToolFailureType {

    /**
     * 参数错误 - 入参不符合 Schema，不应重试，需模型自愈修正参数
     * 对应：{@link ToolParameterValidator} 校验失败抛出 IllegalArgumentException
     */
    PARAMETER_ERROR("参数错误", false, false, "模型需自愈修正参数"),

    /**
     * 权限错误 - 无权限调用该工具或操作资源，不应重试
     * 对应：SecurityException、403 响应
     */
    PERMISSION_DENIED("权限错误", false, false, "需人工授权或角色配置"),

    /**
     * 超时错误 - 工具执行超时，可重试（幂等工具）
     * 对应：SocketTimeoutException、TimeoutException
     */
    TIMEOUT("超时错误", true, true, "幂等工具可重试，非幂等需人工确认"),

    /**
     * 限流错误 - 下游服务限流（429），可指数退避重试
     * 对应：RateLimitException、HTTP 429
     */
    RATE_LIMITED("限流错误", true, true, "指数退避重试"),

    /**
     * 服务不可用 - 下游服务挂了（5xx、连接拒绝），可重试
     * 对应：ConnectException、HTTP 5xx
     */
    SERVICE_UNAVAILABLE("服务不可用", true, true, "熔断后重试"),

    /**
     * 空结果错误 - 工具正常执行但返回空结果，业务语义上可能是失败
     * 对应：检索无命中、查询无数据
     */
    EMPTY_RESULT("空结果", false, false, "业务层决定是否降级或转人工"),

    /**
     * 部分成功错误 - 多步骤工具链中部分步骤成功、部分失败
     * 最危险：系统处于"半残状态"，必须触发补偿
     * 对应：创建记录成功但通知发送失败、执行脚本成功但状态回写失败
     */
    PARTIAL_SUCCESS("部分成功", false, true, "必须触发 Saga 补偿，逆序回滚已成功步骤"),

    /**
     * 补偿失败错误 - 补偿动作本身也失败了
     * 最高优先级：标记 MANUAL_INTERVENTION_REQUIRED，触发人工接管告警
     * 对应：回滚脚本报错、删除补偿记录失败
     */
    COMPENSATION_FAILED("补偿失败", false, false, "立即触发人工接管告警，标记 MANUAL_INTERVENTION_REQUIRED"),

    /**
     * 未知错误 - 兜底分类，不应重试
     */
    UNKNOWN("未知错误", false, false, "记录完整堆栈，人工排查");

    private final String displayName;
    private final boolean retryable;      // 是否可重试
    private final boolean needsCompensation; // 是否需补偿
    private final String handlingHint;    // 处理建议

    ToolFailureType(String displayName, boolean retryable, boolean needsCompensation, String handlingHint) {
        this.displayName = displayName;
        this.retryable = retryable;
        this.needsCompensation = needsCompensation;
        this.handlingHint = handlingHint;
    }

    public String getDisplayName() { return displayName; }
    public boolean isRetryable() { return retryable; }
    public boolean needsCompensation() { return needsCompensation; }
    public String getHandlingHint() { return handlingHint; }

    /**
     * 从异常自动推断失败类型
     */
    public static ToolFailureType fromException(Throwable throwable) {
        if (throwable == null) return UNKNOWN;

        String msg = throwable.getMessage() != null ? throwable.getMessage().toLowerCase() : "";
        String className = throwable.getClass().getSimpleName().toLowerCase();

        // 参数错误优先判断（IllegalArgumentException 通常是参数校验失败）
        if (throwable instanceof IllegalArgumentException) return PARAMETER_ERROR;

        // 权限错误
        if (throwable instanceof SecurityException
                || msg.contains("403") || msg.contains("forbidden") || msg.contains("unauthorized")
                || msg.contains("permission") || msg.contains("access denied")) {
            return PERMISSION_DENIED;
        }

        // 超时错误
        if (throwable instanceof java.util.concurrent.TimeoutException
                || throwable instanceof java.net.SocketTimeoutException
                || className.contains("timeout")
                || msg.contains("timeout")) {
            return TIMEOUT;
        }

        // 限流错误
        if (msg.contains("429") || msg.contains("rate limit") || msg.contains("too many requests")
                || msg.contains("throttle")) {
            return RATE_LIMITED;
        }

        // 服务不可用
        if (throwable instanceof java.net.ConnectException
                || msg.contains("connection refused") || msg.contains("connection reset")
                || msg.contains("503") || msg.contains("502") || msg.contains("504")
                || msg.contains("service unavailable") || msg.contains("unavailable")) {
            return SERVICE_UNAVAILABLE;
        }

        // 补偿失败（在补偿动作中捕获的异常）
        if (msg.contains("compensation") || msg.contains("rollback") || msg.contains("补偿") || msg.contains("回滚")) {
            return COMPENSATION_FAILED;
        }

        return UNKNOWN;
    }
}