package com.devops.agent.common.error;

import org.springframework.http.HttpStatus;

/**
 * 业务错误码枚举（C7）。
 *
 * <h3>解决什么问题</h3>
 * 修复前错误码是<b>散落各处的魔法数字</b>：{@code 40009} / {@code 40021} /
 * {@code 40101} 直接硬编码在 {@code GlobalExceptionHandler} 与各 Service 里，
 * 前端 {@code toFriendlyError} 又各自硬编码同一批数字。没有单一真相，
 * 加一个码要改三处，漏一处就表现为「前端只显示一句无意义的兜底文案」。
 *
 * <h3>三个维度绑定在一起</h3>
 * 每个码同时携带：
 * <ul>
 *   <li><b>HTTP 状态</b>——避免同一个码在不同 handler 里映射成不同状态；</li>
 *   <li><b>重试语义</b>——见 {@link Retry}。这是从 new-api 借鉴的关键设计：
 *       让「能不能重试」成为错误自身的属性，而不是散落在调用点的 if-else；</li>
 *   <li><b>默认文案</b>——面向用户，不含内部细节。</li>
 * </ul>
 *
 * <h3>编码规则</h3>
 * 5 位数字 {@code AABCC}：前两位对齐 HTTP 语义（40=4xx 客户端，50=5xx 服务端），
 * 中间一位为子域，末两位递增。沿用项目既有的 40009/40021/40101 等，不做破坏性重编。
 *
 * <p><b>新增错误码时必须同步前端</b> {@code src/constants/bizCode.ts}，
 * 否则用户只会看到兜底文案。已在 AGENTS.md 登记为硬约束。</p>
 *
 * @author OpsBrain AI
 * @since 2026-08-24
 */
public enum BizError {

    // ==================== 4xx 客户端 ====================
    INVALID_PARAM(40001, HttpStatus.BAD_REQUEST, Retry.NEVER, "参数不合法"),
    PROMPT_INJECTION(40003, HttpStatus.FORBIDDEN, Retry.NEVER, "检测到提示词注入，请求已拦截"),
    BUDGET_EXCEEDED(40006, HttpStatus.BAD_REQUEST, Retry.NEVER, "问题过长，超出模型上下文窗口限制"),

    /**
     * 业务状态冲突：请求本身合法，但当前对象状态不允许该操作
     * （如对已作废工单改状态、对未发布文档执行回滚）。
     * <p>沿用项目既有的 40004——全部 27 处 Controller 都用它映射
     * {@code IllegalStateException}，已是事实标准。</p>
     */
    STATE_CONFLICT(40004, HttpStatus.CONFLICT, Retry.NEVER, "当前状态不允许该操作"),

    /** 乐观锁冲突：数据已被他人修改，需刷新后由用户决定是否重提交 */
    OPTIMISTIC_LOCK(40009, HttpStatus.CONFLICT, Retry.CLIENT, "数据已被他人修改，请刷新后重试"),

    ENDPOINT_DEPRECATED(40010, HttpStatus.GONE, Retry.NEVER, "端点已废弃"),
    DUPLICATE_CONTENT(40021, HttpStatus.CONFLICT, Retry.NEVER, "内容重复"),

    // 登录失败与「登录已失效」必须分开：前者用户还在登录页，应原地显示
    // 「用户名或密码错误」；后者是会话过期，前端要跳转登录页。
    // 合并成一个码会让登录页在密码输错时执行一次无意义的跳转，
    // 且把用户已填的用户名清空
    LOGIN_FAILED(40100, HttpStatus.UNAUTHORIZED, Retry.NEVER, "用户名或密码错误"),
    NOT_LOGIN(40101, HttpStatus.UNAUTHORIZED, Retry.NEVER, "未登录或登录已失效，请重新登录"),
    WEBHOOK_UNAUTHORIZED(40104, HttpStatus.UNAUTHORIZED, Retry.NEVER, "Webhook 鉴权失败"),
    NO_PERMISSION(40103, HttpStatus.FORBIDDEN, Retry.NEVER, "权限不足"),
    SECURITY_BLOCKED(40301, HttpStatus.FORBIDDEN, Retry.NEVER, "该操作存在安全风险，已被拦截"),

    NOT_FOUND(40400, HttpStatus.NOT_FOUND, Retry.NEVER, "资源未找到"),

    /** 限流：明确可退避后重试，前端据此展示倒计时而非报错 */
    RATE_LIMITED(42901, HttpStatus.TOO_MANY_REQUESTS, Retry.BACKOFF, "请求过于频繁，请稍后再试"),

    // ==================== 5xx 服务端自身 ====================
    INTERNAL_ERROR(50001, HttpStatus.INTERNAL_SERVER_ERROR, Retry.SAFE, "服务内部异常"),
    SSE_CONNECTION_ERROR(50002, HttpStatus.INTERNAL_SERVER_ERROR, Retry.SAFE, "连接异常，请稍后重试"),
    RAG_RETRIEVE_FAILED(50010, HttpStatus.INTERNAL_SERVER_ERROR, Retry.SAFE, "知识检索服务暂不可用"),

    /**
     * 指标数据源（Prometheus）不可用。
     * <p>Retry 是 NEVER 而非 SAFE：这是<b>环境问题</b>而非瞬时抖动，
     * 重试通常无效。前端应提示去检查数据源接入，
     * 而不是让用户对着「稍后重试」反复刷新。</p>
     */
    METRICS_UNAVAILABLE(50020, HttpStatus.SERVICE_UNAVAILABLE, Retry.NEVER, "监控数据源不可用"),

    // ==================== 5xx 上游模型 ====================
    /** 上游超时：可安全重试（未产生副作用） */
    LLM_TIMEOUT(50210, HttpStatus.GATEWAY_TIMEOUT, Retry.SAFE, "模型响应超时"),
    /** 上游限流：必须退避，立即重试只会继续被拒 */
    LLM_RATE_LIMITED(50211, HttpStatus.TOO_MANY_REQUESTS, Retry.BACKOFF, "模型服务繁忙"),
    /** 内容被安全策略拦截：重试无意义，换问法才有用 */
    LLM_CONTENT_BLOCKED(50212, HttpStatus.BAD_GATEWAY, Retry.NEVER, "内容被模型安全策略拦截");

    /**
     * 重试语义。
     * <p>让「能不能重试」成为错误自身的属性——调用点写
     * {@code if (err.retry().isRetryable())} 即可，
     * 不用再各自判断状态码或匹配异常消息字符串。
     * 项目里 {@code ToolFailureType} 已有同类雏形，本枚举把它提升为全局约定。</p>
     */
    public enum Retry {
        /** 不可重试：重试结果必然相同（参数错、权限不足、内容违规） */
        NEVER,
        /** 可安全重试：无副作用，适合自动退避重试（超时、上游抖动） */
        SAFE,
        /** 必须退避后重试：立即重试会继续被拒（限流） */
        BACKOFF,
        /** 需客户端介入：由用户决定（乐观锁冲突需先刷新看到最新数据） */
        CLIENT;

        /** 是否适合<b>自动</b>重试。CLIENT 不算——它需要人来决定 */
        public boolean isAutoRetryable() {
            return this == SAFE || this == BACKOFF;
        }
    }

    private final int code;
    private final HttpStatus httpStatus;
    private final Retry retry;
    private final String defaultMessage;

    BizError(int code, HttpStatus httpStatus, Retry retry, String defaultMessage) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.retry = retry;
        this.defaultMessage = defaultMessage;
    }

    public int code() {
        return code;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }

    public Retry retry() {
        return retry;
    }

    public String defaultMessage() {
        return defaultMessage;
    }

    /** 按数字码反查。未知码返回 null——调用方需自行兜底，不要静默当成成功 */
    public static BizError fromCode(int code) {
        for (BizError e : values()) {
            if (e.code == code) {
                return e;
            }
        }
        return null;
    }
}
