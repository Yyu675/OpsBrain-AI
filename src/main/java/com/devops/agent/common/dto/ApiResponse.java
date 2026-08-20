package com.devops.agent.common.dto;

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
        response.traceId = generateTraceId();
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
        response.traceId = generateTraceId();
        response.timestamp = System.currentTimeMillis();
        return response;
    }

    /**
     * 失败响应(带 traceId)
     */
    public static <T> ApiResponse<T> error(int code, String message, String traceId) {
        ApiResponse<T> response = new ApiResponse<>();
        response.code = code;
        response.message = message;
        response.data = null;
        response.traceId = traceId;
        response.timestamp = System.currentTimeMillis();
        return response;
    }

    /**
     * 生成 8 位追踪 ID
     */
    private static String generateTraceId() {
        return Long.toHexString(System.nanoTime()).substring(0, 8);
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
