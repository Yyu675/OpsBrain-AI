package com.devops.agent.common.audit;

import cn.dev33.satoken.stp.StpUtil;
import com.devops.agent.common.context.TraceContext;
import com.devops.agent.common.web.ClientIpResolver;
import com.devops.agent.infrastructure.persistence.repo.OperationAuditRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 通用写操作审计拦截器（C5）。
 *
 * <h3>为什么需要</h3>
 * 修复前审计是「点状」的：只有 AI 对话（{@code sys_agent_call_log}）与
 * 工单域（{@code sys_ticket_activity}）有记录，知识库删除、分类调整、
 * 审批通过、告警确认全部无痕。
 * <p>
 * 这在 L1 是缺陷，到 L3/L4 就是合规红线——AI 将自动执行自愈动作，
 * 「谁在什么时候改了什么」必须可追溯，否则事故无法定责与复盘。
 *
 * <h3>只拦写操作</h3>
 * 读操作量级比写高一到两个数量级，全量记录会让审计表迅速变成最大的表，
 * 而其中 99% 没有审计价值。GET/HEAD/OPTIONS 一律跳过。
 *
 * <h3>异步落库</h3>
 * 审计写库不应延长用户请求。用<b>有界队列 + CallerRuns</b> 的小线程池：
 * <ul>
 *   <li>有界——审计突增时不会无限堆积拖垮内存（无界队列是经典 OOM 来源）；</li>
 *   <li>CallerRuns——队列满时退化为同步写，<b>宁可慢一点也不丢审计</b>。
 *       这与限流/缓存的 fail-open 取舍相反：那些丢了只影响体验，
 *       审计丢了就是证据缺失。</li>
 * </ul>
 *
 * @author OpsBrain AI
 * @since 2026-08-24
 */
@Slf4j
@Component
public class OperationAuditInterceptor implements HandlerInterceptor {

    private static final String ATTR_START = "audit.startTime";

    /** 读方法不审计（量大且无审计价值） */
    private static final Set<String> READ_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    /** 高频且无价值的路径，避免污染审计表 */
    private static final Set<String> SKIP_PREFIXES = Set.of(
            "/api/v1/health",
            "/api/v1/alerts/webhook"   // 告警推送量极大，且已有 sys_alert 表留痕
    );

    @Value("${devops.audit.enabled:true}")
    private boolean auditEnabled;

    private final OperationAuditRepository repository;

    private final ClientIpResolver clientIpResolver;

    /**
     * 审计写入线程池：2 线程 + 有界队列 500 + CallerRuns。
     * <p>队列满时由调用线程直接执行（同步写），形成天然背压，
     * 既不丢审计也不会无限堆积。</p>
     */
    private final ExecutorService auditExecutor = new ThreadPoolExecutor(
            2, 2, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
            new java.util.concurrent.LinkedBlockingQueue<>(500),
            r -> {
                Thread t = new Thread(r, "operation-audit");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.CallerRunsPolicy());

    public OperationAuditInterceptor(OperationAuditRepository repository,
                                     ClientIpResolver clientIpResolver) {
        this.repository = repository;
        this.clientIpResolver = clientIpResolver;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        request.setAttribute(ATTR_START, System.currentTimeMillis());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        if (!auditEnabled || !shouldAudit(request)) {
            return;
        }
        try {
            Object start = request.getAttribute(ATTR_START);
            int durationMs = start instanceof Long s ? (int) (System.currentTimeMillis() - s) : 0;

            String path = pathWithoutContext(request);
            int status = response.getStatus();

            // 业务成败：HTTP 2xx 只说明请求到达了，业务可能仍失败
            // （本项目统一 ApiResponse 包装，code!=0 时 HTTP 常常仍是 200）。
            // 拦截器读不到响应体，故仅以状态码 + 异常判定，
            // 精确的 bizCode 由业务侧显式埋点补充。
            boolean success = ex == null && status >= 200 && status < 400;

            // MDC 在 afterCompletion 仍在同一请求线程，可安全读取
            String traceId = TraceContext.getTraceId();

            OperationAuditRecord record = new OperationAuditRecord(
                    traceId,
                    resolveActorId(),
                    null,
                    AuditActionRegistry.resolve(request.getMethod(), path),
                    null,
                    extractTargetId(path),
                    request.getMethod(),
                    truncate(path, 255),
                    status,
                    success,
                    null,
                    null,
                    ex != null ? truncate(ex.getClass().getSimpleName() + ": " + ex.getMessage(), 512) : null,
                    clientIp(request),
                    truncate(request.getHeader("User-Agent"), 255),
                    durationMs,
                    LocalDateTime.now());

            auditExecutor.execute(() -> repository.save(record));
        } catch (Exception e) {
            // 审计自身异常绝不能影响响应——此时响应已提交，抛出只会污染日志
            log.error("❌ [Audit] 构造审计记录失败", e);
        }
    }

    // ==================== 内部 ====================

    private boolean shouldAudit(HttpServletRequest request) {
        if (READ_METHODS.contains(request.getMethod())) {
            return false;
        }
        String path = pathWithoutContext(request);
        for (String skip : SKIP_PREFIXES) {
            if (path.startsWith(skip)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 去掉 context-path（{@code /ai}），使审计里的路径与代码中的
     * {@code @RequestMapping} 一致，便于对照与登记 action。
     */
    private String pathWithoutContext(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String ctx = request.getContextPath();
        if (ctx != null && !ctx.isEmpty() && uri.startsWith(ctx)) {
            return uri.substring(ctx.length());
        }
        return uri;
    }

    private String resolveActorId() {
        try {
            if (StpUtil.isLogin()) {
                return StpUtil.getLoginIdAsString();
            }
        } catch (Exception ignore) {
            // 未登录或非请求上下文
        }
        return "ANONYMOUS";
    }

    /** 取路径最后一个可变段作为 targetId（如 /docs/42 → 42） */
    private String extractTargetId(String path) {
        String[] parts = path.split("/");
        for (int i = parts.length - 1; i >= 0; i--) {
            String p = parts[i];
            if (!p.isEmpty() && !AuditActionRegistry.normalize("/" + p).equals("/" + p)) {
                return truncate(p, 64);
            }
        }
        return null;
    }

    /**
     * 来源 IP，委托给 {@link ClientIpResolver}。
     *
     * <p>原实现无条件取 X-Forwarded-For 的第一段——那是客户端可伪造的部分，
     * 意味着<b>审计表里的来源 IP 可被写成任意值</b>。
     * 审计恰恰是最不能被污染的数据：它存在的意义就是事后能查，
     * 记录一个伪造的内网地址会把追溯直接引向无辜的机器。</p>
     */
    private String clientIp(HttpServletRequest request) {
        return truncate(clientIpResolver.resolve(request), 45);
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
