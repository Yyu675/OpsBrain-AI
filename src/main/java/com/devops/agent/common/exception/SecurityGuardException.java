package com.devops.agent.common.exception;

/**
 * 安全门卫拦截异常 - Prompt 注入攻击被拦截时抛出
 *
 * @author OpsBrain AI
 * @since 2026-07-15
 */
public class SecurityGuardException extends RuntimeException {

    private final int code;

    public SecurityGuardException(String message) {
        super(message);
        this.code = 40301; // 输入安全拦截
    }

    public SecurityGuardException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    /**
     * 面向用户的泛化安全消息（P2-38）
     * <p>
     * 前端的 SSE error 事件只允许下发两类信息：攻击类型（如实告知）与固定业务话术。
     * 原始 message 可能包含命中模式名（如注入特征原文），内部细节仅保留在日志与审计中。
     * </p>
     */
    public String getUserMessage() {
        return switch (code) {
            case 40001 -> "输入不合法，请检查后重新提问";
            case 40003 -> "检测到提示词注入攻击，请求已拦截";
            case 40301 -> "该操作存在安全风险，已被拦截";
            default -> "请求包含安全风险，已被拦截";
        };
    }
}
