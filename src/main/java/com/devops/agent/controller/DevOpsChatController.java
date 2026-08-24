package com.devops.agent.controller;

import com.devops.agent.application.DevOpsAgentService;
import com.devops.agent.domain.biz.service.AgentLogService;
import com.devops.agent.infrastructure.cache.SlidingWindowRateLimiter;
import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import org.springframework.beans.factory.annotation.Value;

import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 对话接入与流式推送控制器
 * <p>
 * 职责: 接收提问,创建 SSE 连接,将 Agent 执行结果流式推送给前端
 * </p>
 * <p>
 * 双端点设计（2026-08-08 修复）：
 * <ul>
 *   <li>{@code POST /stream}：推荐。查询走请求体，不受 URL 长度限制，
 *       支持粘贴长日志/堆栈（运维核心场景）</li>
 *   <li>{@code GET /stream}：兼容保留。查询走 URL 参数，
 *       中文约 300 字即触达 Tomcat 8KB 请求头上限返回 400</li>
 * </ul>
 *
 * @author OpsBrain AI
 * @since 2026-07-15
 */
@RestController
@RequestMapping("/api/v1/chat")
public class DevOpsChatController {

    private static final Logger log = LoggerFactory.getLogger(DevOpsChatController.class);

    private final DevOpsAgentService agentService;
    private final ObjectMapper objectMapper;
    private final AgentLogService agentLogService;

    /**
     * SSE 连接总超时（毫秒）。
     * <p><b>A2 修复</b>：原为硬编码 60000L，比 reasoner 模型的总超时
     * （{@code alibaba.timeout * 2} = 120s）还短，导致复杂推理场景
     * 必然在 60s 被判超时，而后端模型仍在计费运行。
     * 现由配置驱动，默认 150s，满足「MVC async > SSE > 模型」层级。</p>
     */
    @Value("${devops.ai.sse.timeout-ms:150000}")
    private long sseTimeoutMs;

    /** SSE 心跳间隔（毫秒），用于穿透中间代理的空闲超时 */
    @Value("${devops.ai.sse.heartbeat-interval-ms:15000}")
    private long sseHeartbeatMs;

    /** C4：单用户对话限流阈值，<=0 关闭 */
    @Value("${devops.ai.chat.rate-limit:20}")
    private int chatRateLimit;

    /** C4：对话限流窗口（毫秒） */
    @Value("${devops.ai.chat.rate-window-ms:60000}")
    private long chatRateWindowMs;

    /**
     * SSE 心跳调度器。
     * <p>单线程守护足够：心跳任务本身极轻（写一个注释帧），
     * 且每个连接只注册一个周期任务。</p>
     */
    private final ScheduledExecutorService heartbeatScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "sse-heartbeat");
                t.setDaemon(true);
                return t;
            });

    private final SlidingWindowRateLimiter rateLimiter;

    public DevOpsChatController(DevOpsAgentService agentService, ObjectMapper objectMapper,
                                AgentLogService agentLogService,
                                SlidingWindowRateLimiter rateLimiter) {
        this.agentService = agentService;
        this.objectMapper = objectMapper;
        this.agentLogService = agentLogService;
        this.rateLimiter = rateLimiter;
    }

    /**
     * 解析限流主体。
     * <p>优先 userId——按 IP 限流会让同一出口 NAT 后的所有同事共享额度，
     * 一个人刷爆全组都用不了，这在企业内网是常态而非例外。</p>
     */
    private String resolveRateLimitIdentity() {
        try {
            if (StpUtil.isLogin()) {
                return "u:" + StpUtil.getLoginIdAsString();
            }
        } catch (Exception ignore) {
            // 非请求上下文或 Sa-Token 未就绪，退化到 IP
        }
        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs != null) {
            return "ip:" + attrs.getRequest().getRemoteAddr();
        }
        return "unknown";
    }

    @PreDestroy
    public void shutdown() {
        heartbeatScheduler.shutdownNow();
    }

    /**
     * 流式对话接口
     * <p>
     * SSE 事件契约:
     * - start: 连接建立 (traceId, routerModel)
     * - tool_status: 工具执行状态 (toolName, status, message)
     * - token: 每个字符/词 (text)
     * - complete: 结束 (traceId, latencyMs, isCached, costRmb, citations[])
     * - error: 异常 (traceId, code, message)
     * </p>
     *
     * @param query    用户提问
     * @param response HTTP 响应对象(用于设置防缓冲头)
     * @return SSE Emitter
     */
    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(@RequestParam("query") String query,
                                 @RequestParam(value = "sessionId", required = false) String sessionId,
                                 HttpServletResponse response) {
        return doStream(query, sessionId, response, "GET");
    }

    /**
     * 流式对话接口（POST，推荐）
     * <p>
     * 查询走请求体，规避 URL 长度限制。运维场景常需粘贴长日志/堆栈，
     * GET 方式中文约 300 字即触达 Tomcat 8KB 请求头上限返回 400。
     * </p>
     * <p>SSE 事件契约与 GET 完全一致。</p>
     *
     * @param request  请求体 {"query": "..."}
     * @param response HTTP 响应对象（用于设置防缓冲头）
     * @return SSE Emitter
     */
    @PostMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChatPost(@RequestBody ChatRequest request, HttpServletResponse response) {
        return doStream(
                request != null ? request.query() : null,
                request != null ? request.sessionId() : null,
                response, "POST");
    }

    /**
     * 流式对话统一处理（GET/POST 共用）
     *
     * @param sessionId 会话 ID，为空时退化为单轮（无跨轮记忆）
     */
    private SseEmitter doStream(String query, String sessionId, HttpServletResponse response, String method) {
        // 生成追踪 ID
        String traceId = generateTraceId();

        // 创建 SSE Emitter。超时由配置驱动（A2），必须 > 最慢模型总超时。
        SseEmitter emitter = new SseEmitter(sseTimeoutMs);

        // 设置防缓冲响应头(防止 Nginx/代理缓冲导致延迟)
        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("X-Accel-Buffering", "no");
        response.setCharacterEncoding("UTF-8");

        // 入参兜底：空查询直接返回错误事件，不进入 Agent 链路
        // P2-29：空查询绕过 Agent 层，但 6.6 审计铁律要求任何终止路径落 audit，
        // 此处直接记 REJECTED_SECURITY 审计日志
        if (query == null || query.isBlank()) {
            log.warn("⚠️ [ChatController] 空查询 | traceId={} | method={}", traceId, method);
            recordAudit(traceId, query, "输入为空", "REJECTED_SECURITY");
            sendErrorEvent(emitter, traceId, 40001, "输入不能为空");
            emitter.complete();
            return emitter;
        }

        // ========== C4 限流 ==========
        // 本端点是全项目最贵的一个：一次请求 = 一次真实 LLM 调用，
        // 耗时数十秒、按 token 计费、并占用一个异步线程直到结束。
        // 无限流时，一段循环脚本就能同时打爆额度、连接池与 Tomcat 异步容量。
        //
        // 限流主体优先用 userId：按 IP 会让同一办公网出口的所有同事共享额度，
        // 一个人刷爆全组都用不了。取不到登录态（理论上不会——本端点需鉴权）
        // 才退化到 IP。
        String rateLimitId = resolveRateLimitIdentity();
        if (!rateLimiter.tryAcquire("chat", rateLimitId, chatRateLimit, chatRateWindowMs)) {
            log.warn("🚫 [ChatController] 触发对话限流 | traceId={} | id={} | limit={}/{}ms",
                    traceId, rateLimitId, chatRateLimit, chatRateWindowMs);
            recordAudit(traceId, query, "触发限流", "REJECTED_RATE_LIMIT");
            // 用 SSE error 事件而非 HTTP 429：响应此刻已是 text/event-stream，
            // 前端走的是 SSE 解析器，改用状态码它收不到可读提示，只会看到连接异常。
            sendErrorEvent(emitter, traceId, 42901,
                    "提问过于频繁，请稍后再试（每 " + (chatRateWindowMs / 1000) + " 秒最多 "
                            + chatRateLimit + " 次）");
            emitter.complete();
            return emitter;
        }

        log.info("📨 [ChatController] 收到请求 | traceId={} | sessionId={} | method={} | len={} | query={}",
                traceId, sessionId != null ? sessionId : "<单轮>", method, query.length(),
                query.substring(0, Math.min(50, query.length())));

        // ========== SSE 心跳（A2）==========
        // 中间代理（Nginx proxy_read_timeout 默认 60s）在长时间无数据时会主动断连。
        // reasoner 模型「首 token 前」的思考期很容易超过 60s，届时连接已被代理掐断，
        // 用户看到的是「莫名其妙断线」而非超时提示。
        // 注释帧（以 ":" 开头）是 SSE 规范定义的保活手段，浏览器 EventSource 与
        // fetch-event-source 都会忽略它，不触发任何前端事件，无副作用。
        //
        // 用 AtomicReference 持有句柄：心跳任务自身需要在发送失败时取消自己，
        // 而 lambda 内无法引用尚未初始化完成的局部变量。
        final AtomicReference<ScheduledFuture<?>> heartbeatRef = new AtomicReference<>();
        heartbeatRef.set(heartbeatScheduler.scheduleWithFixedDelay(() -> {
            try {
                emitter.send(SseEmitter.event().comment("heartbeat"));
            } catch (Exception e) {
                // 连接已关闭（客户端断开/已 complete）。取消心跳，避免任务空转堆积。
                // 这里不调 cancelStream：连接关闭会由 onError/onCompletion 回调处理，
                // 重复取消会重建取消标记造成残留（P2-23 教训）。
                log.debug("💓 [ChatController] 心跳发送失败，连接已关闭，停止心跳 | traceId={}", traceId);
                cancelHeartbeat(heartbeatRef);
            }
        }, sseHeartbeatMs, sseHeartbeatMs, TimeUnit.MILLISECONDS));

        // 注册超时和错误回调
        // P2-25：SSE 断开时通知 Agent 服务取消流式执行，防止后端继续跑完模型流并写库建单。
        // traceId 已由闭包捕获，无需 ThreadLocal。
        emitter.onTimeout(() -> {
            log.warn("⏰ [ChatController] SSE 超时 | traceId={} | timeoutMs={}", traceId, sseTimeoutMs);
            cancelHeartbeat(heartbeatRef);
            agentService.cancelStream(traceId);
            try {
                sendErrorEvent(emitter, traceId, 50002, "连接超时");
                emitter.complete();
            } catch (Exception e) {
                log.error("❌ [ChatController] 超时回调异常", e);
            }
        });

        emitter.onError((ex) -> {
            log.error("❌ [ChatController] SSE 异常 | traceId={} | detail={}", traceId, ex.getMessage(), ex);
            cancelHeartbeat(heartbeatRef);
            agentService.cancelStream(traceId);
            try {
                sendErrorEvent(emitter, traceId, 50001, "连接异常，请稍后重试或联系管理员");
                emitter.complete();
            } catch (Exception e) {
                log.error("❌ [ChatController] 错误回调异常", e);
            }
        });

        emitter.onCompletion(() -> {
            log.info("✅ [ChatController] SSE 完成 | traceId={}", traceId);
            // 心跳必须在此取消：正常完成是最主要的终止路径，
            // 漏取消会让调度器里堆积永不结束的任务（每次对话泄漏一个）。
            cancelHeartbeat(heartbeatRef);
            // 正常完成不取消流：取消标记已由 streamAgent 轮询循环 finally 清理，
            // 此处再调 cancelStream 会重建标记导致残留（P2-23 教训）。
            // 取消只在超时/错误（流可能仍在跑）时发出。
        });

        // 交给 Agent 服务异步处理（带会话记忆）
        agentService.handleStreamChat(query, traceId, sessionId, emitter);

        return emitter;
    }

    /**
     * 对话请求体
     *
     * @param query     用户提问（1~1500 字，由 SecurityInputGuard 校验）
     * @param sessionId 会话 ID（多轮对话共享，为空时退化为单轮无记忆）
     */
    public record ChatRequest(String query, String sessionId) {
    }

    /**
     * 取消心跳任务（幂等）。
     * <p>所有终止路径（complete / timeout / error / 发送失败）都必须调用，
     * 否则调度器里会堆积永不结束的周期任务——每次对话泄漏一个，
     * 与本次修复的 ChatMemoryStore 泄漏是同类问题。</p>
     */
    private void cancelHeartbeat(AtomicReference<ScheduledFuture<?>> ref) {
        ScheduledFuture<?> f = ref.getAndSet(null);
        if (f != null) {
            f.cancel(false);
        }
    }

    /**
     * 记录审计日志（异步，不阻塞 SSE 主流程）
     * <p>P2-29：空查询/非法入参在 Controller 层直接拒绝时，Agent 层不参与，
     * 审计由本方法补记——遵守 6.6 审计铁律（任何终止路径必须落
     * {@code sys_agent_call_log}，operation_type 用 REJECTED_* 前缀）。</p>
     */
    private void recordAudit(String traceId, String query, String answer, String operationType) {
        try {
            agentLogService.saveLog(traceId, query, answer, "NONE", false, 0, 0.0, "[]",
                    operationType, "[]", "SYSTEM");
        } catch (Exception e) {
            log.error("❌ [ChatController] 审计记账失败 | traceId={} | opType={}", traceId, operationType, e);
        }
    }

    /**
     * 发送错误事件
     */
    private void sendErrorEvent(SseEmitter emitter, String traceId, int code, String message) {
        Map<String, Object> data = new HashMap<>();
        data.put("traceId", traceId);
        data.put("code", code);
        data.put("message", message);
        sendEvent(emitter, "error", data);
    }

    /**
     * 发送 SSE 事件
     */
    private void sendEvent(SseEmitter emitter, String eventName, Map<String, Object> data) {
        try {
            String json = objectMapper.writeValueAsString(data);
            emitter.send(SseEmitter.event().name(eventName).data(json));
        } catch (IOException e) {
            log.error("❌ [SSE] 事件发送失败 | event={}", eventName, e);
        }
    }

    /**
     * 生成追踪 ID (32位十六进制，UUID 无分隔符)
     * <p>
     * P2-27：原用 nanoTime().substring(0,8)，高并发下碰撞概率高，
     * 导致 traceId/sagaId/配额 key 撞车。改为 UUID 无分隔符形式。
     * </p>
     */
    private String generateTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
