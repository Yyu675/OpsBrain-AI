package com.devops.agent.common.exception;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import com.devops.agent.common.dto.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理器
 * <p>
 * 职责: 统一捕获未处理的异常,返回标准错误 JSON 格式(非 SSE 接口)
 * SSE 接口的异常由 Controller 层转为 error 事件
 * </p>
 *
 * @author OpsBrain AI
 * @since 2026-07-15
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 处理安全门卫拦截异常
     */
    @ExceptionHandler(SecurityGuardException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ApiResponse<Void> handleSecurityGuardException(SecurityGuardException ex) {
        log.warn("🚫 [GlobalException] 安全拦截: {}", ex.getMessage());
        return ApiResponse.error(ex.getCode(), ex.getMessage());
    }

    /**
     * 处理 Sa-Token 未登录异常（方向三鉴权）
     * <p>
     * SaInterceptor 校验未登录时抛 {@link NotLoginException}（RuntimeException 子类）。
     * 必须单独处理——否则会被下方 {@code handleRuntimeException} 当作 500 内部错误，
     * 而登录失效应是 401，前端据此跳登录页。业务码 40101 与 AuthController 一致。
     * </p>
     */
    @ExceptionHandler(NotLoginException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ApiResponse<Void> handleNotLoginException(NotLoginException ex) {
        log.debug("🔒 [GlobalException] 未登录/登录失效: type={}", ex.getType());
        return ApiResponse.error(40101, "未登录或登录已失效，请重新登录");
    }

    /**
     * 处理 Sa-Token 角色不足（方向 D：审批端点限 ADMIN）
     * <p>已登录但角色不够 → 403（区别于 401 未登录）。业务码 40103。</p>
     */
    @ExceptionHandler(NotRoleException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ApiResponse<Void> handleNotRoleException(NotRoleException ex) {
        log.warn("🚫 [GlobalException] 角色不足: 需要角色={}", ex.getRole());
        return ApiResponse.error(40103, "权限不足：该操作需要「" + ex.getRole() + "」角色");
    }

    /**
     * 处理 Sa-Token 权限码不足（方向 F 细粒度权限启用后生效）
     */
    @ExceptionHandler(NotPermissionException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ApiResponse<Void> handleNotPermissionException(NotPermissionException ex) {
        log.warn("🚫 [GlobalException] 权限不足: 需要权限={}", ex.getPermission());
        return ApiResponse.error(40103, "权限不足：缺少「" + ex.getPermission() + "」权限");
    }

    /**
     * 处理参数校验异常
     */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleIllegalArgumentException(IllegalArgumentException ex) {
        log.warn("⚠️ [GlobalException] 参数校验失败: {}", ex.getMessage());
        return ApiResponse.error(40001, "参数校验失败: " + ex.getMessage());
    }

    /**
     * 处理未找到匹配处理器/静态资源的请求（Spring Boot 3.x 用 NoResourceFoundException
     * 替代旧版 NoHandlerFoundException）。
     * <p>
     * 典型场景：访问 {@code /actuator/health} 等未注册端点。此前该异常被下方
     * {@code @ExceptionHandler(Exception.class)} catch-all 捕获并返回 50001（HTTP 500），
     * 误导排查者以为是服务内部异常。按 REST 语义应返回 404。
     * </p>
     */
    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiResponse<Void> handleNoResourceFoundException(NoResourceFoundException ex) {
        log.debug("🔍 [GlobalException] 资源未找到: {}", ex.getResourcePath());
        return ApiResponse.error(40400, "资源未找到: " + ex.getResourcePath());
    }

    /**
     * 处理业务异常(通用)
     */
    @ExceptionHandler(RuntimeException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Void> handleRuntimeException(RuntimeException ex) {
        log.error("❌ [GlobalException] 运行时异常: ", ex);
        return ApiResponse.error(50001, "服务内部异常: " + ex.getMessage());
    }

    /**
     * 处理所有未捕获的异常
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Void> handleException(Exception ex) {
        log.error("💥 [GlobalException] 未知异常: ", ex);
        return ApiResponse.error(50001, "服务内部异常,请联系管理员");
    }
}
