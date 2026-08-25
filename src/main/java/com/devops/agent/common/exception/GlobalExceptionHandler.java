package com.devops.agent.common.exception;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import com.devops.agent.common.dto.ApiResponse;
import com.devops.agent.common.error.BizError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

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
        return ApiResponse.error(BizError.NOT_LOGIN.code(), BizError.NOT_LOGIN.defaultMessage());
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
        return ApiResponse.error(BizError.INVALID_PARAM.code(), ex.getMessage());
    }

    /**
     * 处理业务状态冲突（F2）。
     * <p>
     * 领域层用 {@link IllegalStateException} 表达「请求合法但当前状态不允许」，
     * 如对已作废工单改状态、对未发布文档回滚。
     * </p>
     * <p>
     * <b>为什么要有这个 handler</b>：此前 27 处 Controller 各自
     * {@code catch (IllegalStateException e) → error(40004, ...)}。
     * 映射虽一致，但靠 27 份拷贝维持一致性本身就是隐患——
     * 任何一处写错都无从发现。收敛到这里后是单一真相。
     * </p>
     * <p>
     * 消息直接透传：领域层抛出的状态冲突消息是<b>写给用户看的</b>
     * （如「非法状态流转：已关闭 → 待处理」），不含内部实现细节。
     * </p>
     */
    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiResponse<Void> handleIllegalStateException(IllegalStateException ex) {
        log.warn("⚠️ [GlobalException] 状态冲突: {}", ex.getMessage());
        return ApiResponse.error(BizError.STATE_CONFLICT.code(), ex.getMessage());
    }

    /**
     * 处理乐观锁冲突（F2）。
     * <p>此前只在个别 Controller 里单独 catch，未覆盖的端点会落到
     * {@code RuntimeException} 分支返回 500——把「他人已修改，请刷新」
     * 这种可恢复的业务冲突，误报成了服务器故障。</p>
     */
    /**
     * 指标数据源不可用 → 50020 / HTTP 503。
     *
     * <p>用 503 而非 500：这不是 OpsBrain 自身故障，而是它依赖的
     * Prometheus 不可达。503 的语义（服务暂时不可用）也让运维的
     * 监控告警能正确归类——把它算进 OpsBrain 的 5xx 错误率会误导排障方向。</p>
     */
    @ExceptionHandler(com.devops.agent.infrastructure.metrics.MetricsUnavailableException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public ApiResponse<Void> handleMetricsUnavailable(
            com.devops.agent.infrastructure.metrics.MetricsUnavailableException ex) {
        // warn 而非 error：数据源没起是常见的开发/部署态，不该淹没真正的异常
        log.warn("⚠️ [Metrics] 数据源不可用: {}", ex.getMessage());
        return ApiResponse.error(BizError.METRICS_UNAVAILABLE.code(), ex.getMessage());
    }

    @ExceptionHandler(OptimisticLockException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiResponse<Void> handleOptimisticLockException(OptimisticLockException ex) {
        log.warn("⚠️ [GlobalException] 乐观锁冲突: {}", ex.getMessage());
        return ApiResponse.error(BizError.OPTIMISTIC_LOCK.code(), ex.getMessage());
    }

    /**
     * 处理 Bean Validation 校验失败（F3 前置）。
     * <p>把字段级错误组织成 {@code 字段名: 提示} 的形式返回，
     * 前端可据此定位到具体表单项，而不是只显示一句笼统的「参数不合法」。</p>
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleMethodArgumentNotValid(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .collect(Collectors.joining("; "));
        log.warn("⚠️ [GlobalException] 请求体校验失败: {}", detail);
        return ApiResponse.error(BizError.INVALID_PARAM.code(), detail);
    }

    /**
     * 缺少必填请求参数 → 40001 / HTTP 400。
     *
     * <p><b>为什么必须显式声明</b>：本类底部有一个
     * {@code @ExceptionHandler(Exception.class)} 兜底处理器，而
     * {@code ExceptionHandlerExceptionResolver} 的优先级<b>高于</b> Spring 内建的
     * {@code DefaultHandlerExceptionResolver}。这意味着只要没在这里显式接管，
     * Spring 本来会正确映射成 400 的绑定类异常，会被兜底分支抢先捕获，
     * 变成 <b>HTTP 500「服务内部异常，请联系管理员」</b>。
     *
     * <p>后果是双向的：调用方拿到 5xx 会判定「服务端故障」而发起重试，
     * 但少传一个参数重试多少次都不会好；运维侧则会看到一条本不该存在的
     * 5xx 错误率毛刺，把排查方向引向服务器。</p>
     */
    @ExceptionHandler(org.springframework.web.bind.MissingServletRequestParameterException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleMissingParam(
            org.springframework.web.bind.MissingServletRequestParameterException ex) {
        log.warn("⚠️ [GlobalException] 缺少必填参数: {}", ex.getParameterName());
        return ApiResponse.error(BizError.INVALID_PARAM.code(),
                "缺少必填参数：" + ex.getParameterName());
    }

    /**
     * 请求参数类型不匹配 → 40001 / HTTP 400。
     *
     * <p>典型场景：{@code ?days=abc}、{@code /tickets/undefined}（前端拼路径时
     * 变量未取到值是很常见的），以及 {@code ?includeDisabled=yes} 这类布尔写错。
     * 同样因为兜底处理器的存在，此前这些都会返回 500。</p>
     *
     * <p>消息里带上<b>参数名</b>但<b>不带原值</b>：值来自用户输入，
     * 原样回显等于把一个反射型输出点开在错误消息里。参数名足够定位问题。</p>
     */
    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleTypeMismatch(
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException ex) {
        String expected = ex.getRequiredType() == null ? "预期类型" : ex.getRequiredType().getSimpleName();
        log.warn("⚠️ [GlobalException] 参数类型不匹配: name={} | expected={}", ex.getName(), expected);
        return ApiResponse.error(BizError.INVALID_PARAM.code(),
                "参数「" + ex.getName() + "」格式不正确，应为 " + expected);
    }

    /**
     * 请求体无法解析 → 40001 / HTTP 400。
     *
     * <p>JSON 语法错误、类型对不上（如给 {@code Integer} 字段传了字符串）、
     * 或请求体为空。这是<b>客户端</b>的问题，不是服务端故障。</p>
     *
     * <p>不透传 {@code ex.getMessage()}：Jackson 的原始消息里含
     * 完整的内部类名与字段路径（如
     * {@code com.devops.agent.controller.XxxController$XxxRequest["version"]}），
     * 等于把内部包结构公开出去。</p>
     */
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleUnreadableBody(
            org.springframework.http.converter.HttpMessageNotReadableException ex) {
        log.warn("⚠️ [GlobalException] 请求体无法解析: {}", ex.getMessage());
        return ApiResponse.error(BizError.INVALID_PARAM.code(), "请求体格式不正确或为空");
    }

    /**
     * 日期/时间字符串解析失败 → 40001 / HTTP 400。
     *
     * <p>{@link java.time.format.DateTimeParseException} 继承自
     * {@code RuntimeException}，不显式接管就会落到本类底部的
     * {@code handleRuntimeException}，返回 <b>HTTP 500「服务内部异常」</b>。
     *
     * <p>典型触发点是 Controller 里手工 {@code LocalDate.parse(req.dueDate())}
     * 这类调用——请求体里的日期字段没走 Spring 的类型绑定，
     * 而是在业务代码里解析，因此绕过了
     * {@code MethodArgumentTypeMismatchException} 那条已有的 400 通道。</p>
     *
     * <p>用户填了个 {@code 2026/08/25}（斜杠而非连字符）或
     * {@code 2026-8-5}（月份没补零），本该被告知「格式不对」，
     * 却看到「服务内部异常，请联系管理员」——他会去找管理员，
     * 而管理员在日志里看到的是一条 5xx，同样会往服务端故障的方向查。</p>
     *
     * <p>消息里给出期望格式而不是回显用户输入：
     * 告诉他「应为 yyyy-MM-dd」才是可执行的下一步。</p>
     */
    @ExceptionHandler(java.time.format.DateTimeParseException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleDateTimeParse(java.time.format.DateTimeParseException ex) {
        log.warn("⚠️ [GlobalException] 日期解析失败: {}", ex.getMessage());
        return ApiResponse.error(BizError.INVALID_PARAM.code(),
                "日期格式不正确，应为 yyyy-MM-dd（如 2026-08-25）");
    }

    /**
     * HTTP 方法不支持 → 405。
     *
     * <p>路由写错（把 PUT 发成 POST）时应明确告知，而不是报 500 让人以为服务挂了。
     * 响应头里的 {@code Allow} 由 Spring 在抛出前已按契约填好，
     * 这里只负责把响应体也变成本项目的统一结构。</p>
     */
    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    @ResponseStatus(HttpStatus.METHOD_NOT_ALLOWED)
    public ApiResponse<Void> handleMethodNotSupported(
            org.springframework.web.HttpRequestMethodNotSupportedException ex) {
        log.warn("⚠️ [GlobalException] 方法不支持: {}", ex.getMessage());
        return ApiResponse.error(BizError.INVALID_PARAM.code(),
                "该地址不支持 " + ex.getMethod() + " 方法");
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
        return ApiResponse.error(BizError.NOT_FOUND.code(), "资源未找到: " + ex.getResourcePath());
    }

    /**
     * 处理业务异常(通用)
     */
    @ExceptionHandler(RuntimeException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Void> handleRuntimeException(RuntimeException ex) {
        // 不把 ex.getMessage() 下发给用户：它可能含表名、SQL 片段、
        // 内部类名等实现细节，对攻击者是信息泄漏，对普通用户则毫无意义。
        // 完整堆栈进日志，用户凭响应里的 traceId 即可让运维定位到这一行。
        log.error("❌ [GlobalException] 运行时异常: ", ex);
        return ApiResponse.error(BizError.INTERNAL_ERROR.code(),
                BizError.INTERNAL_ERROR.defaultMessage() + "，请稍后重试或联系管理员");
    }

    /**
     * 处理所有未捕获的异常
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Void> handleException(Exception ex) {
        log.error("💥 [GlobalException] 未知异常: ", ex);
        return ApiResponse.error(BizError.INTERNAL_ERROR.code(), "服务内部异常，请联系管理员");
    }
}
