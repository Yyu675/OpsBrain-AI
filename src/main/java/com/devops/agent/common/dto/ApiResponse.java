package com.devops.agent.common.dto;

import com.devops.agent.common.context.TraceContext;

import java.io.Serializable;

/**
 * 统一 API 响应包装类(非流式接口)
 * <p>
 * 契约: 所有非 SSE 接口必须返回此结构,字段名定型,前后端联调冻结
 * </p>
 *
 * @author OpsBrain AI
 * @since 2026-07-15
 */
public class ApiResponse<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 响应码: 0=成功, 非0见错误码表
     */
    private int code;

    /**
     * 提示信息
     */
    private String message;

    /**
     * 业务数据
     */
    private T data;

    /**
     * 链路追踪 ID
     */
    private String traceId;

    /**
     * 服务器时间戳(毫秒)
     */
    private long timestamp;

    // ==================== 静态工厂方法 ====================

    /**
     * 成功响应
     */
    public static <T> ApiResponse<T> success(T data) {
        return success(data, "success");
    }

    /**
     * 成功响应(带消息)
     */
    public static <T> ApiResponse<T> success(T data, String message) {
        ApiResponse<T> response = new ApiResponse<>();
        response.code = 0;
        response.message = message;
        response.data = data;
        response.traceId = currentTraceId();
        response.timestamp = System.currentTimeMillis();
        return response;
    }

    /**
     * 失败响应
     */
    public static <T> ApiResponse<T> error(int code, String message) {
        return error(code, message, null);
    }

    /**
     * 失败响应（携带业务 data，如 40021 的 duplicateDocId 供前端跳转）
     */
    public static <T> ApiResponse<T> error(int code, String message, T data) {
        ApiResponse<T> response = new ApiResponse<>();
        response.code = code;
        response.message = message;
        response.data = data;
        response.traceId = currentTraceId();
        response.timestamp = System.currentTimeMillis();
        return response;
    }

    /**
     * 失败响应(显式指定 traceId)
     * <p>仅用于确实需要覆盖当前上下文 traceId 的特殊场景（如批量任务回执）。
     * 常规路径请用 {@link #error(int, String)}，它会自动取当前请求的 traceId。</p>
     */
    public static <T> ApiResponse<T> errorWithTrace(int code, String message, String traceId) {
        ApiResponse<T> response = new ApiResponse<>();
        response.code = code;
        response.message = message;
        response.data = null;
        response.traceId = traceId;
        response.timestamp = System.currentTimeMillis();
        return response;
    }

    /**
     * 取当前请求的 traceId（A5 修复）。
     * <p>
     * <b>此前的实现是一个真实缺陷</b>：
     * {@code Long.toHexString(System.nanoTime()).substring(0, 8)}
     * ——每次调用都<b>重新生成一个不同的值</b>。这导致同一请求的成功响应、
     * 错误响应与后端日志三处 traceId 互不相同，字段完全无法用于排障，
     * 且 8 位十六进制在高并发下碰撞概率不低。
     * </p>
     * <p>现统一从 {@link TraceContext}（MDC）读取，与日志、响应头
     * {@code X-Request-Id} 三者一致。</p>
     * <p>返回 null 是可接受的——例如在未经 Filter 的内部调用中。
     * 序列化时该字段直接缺省，不影响前端。</p>
     */
    private static String currentTraceId() {
        return TraceContext.getTraceId();
    }

    // ==================== Getters & Setters ====================

    public int getCode() {
        return code;
    }

    public void setCode(int code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }
}
