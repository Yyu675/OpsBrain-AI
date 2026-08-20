package com.devops.agent.controller;

import com.devops.agent.application.DevOpsAgentService;
import com.devops.agent.domain.biz.service.AgentLogService;
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
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

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

    public DevOpsChatController(DevOpsAgentService agentService, ObjectMapper objectMapper,
                                AgentLogService agentLogService) {
        this.agentService = agentService;
        this.objectMapper = objectMapper;
        this.agentLogService = agentLogService;
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

        // 创建 SSE Emitter (60秒超时)
        SseEmitter emitter = new SseEmitter(60000L);

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

        log.info("📨 [ChatController] 收到请求 | traceId={} | sessionId={} | method={} | len={} | query={}",
                traceId, sessionId != null ? sessionId : "<单轮>", method, query.length(),
                query.substring(0, Math.min(50, query.length())));

        // 注册超时和错误回调
        // P2-25：SSE 断开时通知 Agent 服务取消流式执行，防止后端继续跑完模型流并写库建单。
        // traceId 已由闭包捕获，无需 ThreadLocal。
        emitter.onTimeout(() -> {
            log.warn("⏰ [ChatController] SSE 超时 | traceId={}", traceId);
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
            // 正常完成不取消：取消标记已由 streamAgent 轮询循环 finally 清理，
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
