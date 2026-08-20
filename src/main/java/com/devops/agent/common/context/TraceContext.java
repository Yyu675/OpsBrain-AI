package com.devops.agent.common.context;

/**
 * 会话追踪上下文
 * <p>
 * 通过 ThreadLocal 在同一请求线程内传递 traceId
 * 用于工单创建时关联原始会话
 * </p>
 *
 * @author OpsBrain AI
 * @since 2026-07-17
 */
public class TraceContext {

    private static final ThreadLocal<String> TRACE_ID_HOLDER = new ThreadLocal<>();

    /**
     * 设置当前线程的 traceId
     */
    public static void setTraceId(String traceId) {
        TRACE_ID_HOLDER.set(traceId);
    }

    /**
     * 获取当前线程的 traceId
     */
    public static String getTraceId() {
        return TRACE_ID_HOLDER.get();
    }

    /**
     * 清除当前线程的 traceId(防止内存泄漏)
     */
    public static void clear() {
        TRACE_ID_HOLDER.remove();
    }
}
