package com.devops.agent.application;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Agent 编排调度服务接口
 * <p>
 * 职责: 系统大脑 - 安全门卫 → 缓存拦截 → 路由分流 → 调起引擎 → 异步记账
 * </p>
 *
 * @author OpsBrain AI
 * @since 2026-07-15
 */
public interface DevOpsAgentService {

    /**
     * 处理流式对话（单轮，无会话记忆）
     * <p>
     * 兼容保留。等价于 {@code handleStreamChat(query, traceId, null, emitter)}，
     * 此时 sessionId 退化为 traceId，即每次请求都是独立会话，无多轮记忆。
     * </p>
     *
     * @param query   用户提问
     * @param traceId 追踪 ID
     * @param emitter SSE 推送器
     */
    void handleStreamChat(String query, String traceId, SseEmitter emitter);

    /**
     * 处理流式对话（带会话记忆）
     * <p>
     * 编排顺序:
     * <ol>
     *   <li>AgentMemoryManager.loadContext() - 加载三层记忆（热+温）</li>
     *   <li>ContextBudgetManager.allocate() - 上下文预算分配与裁剪</li>
     *   <li>SecurityInputGuard.check() - 安全拦截（含注入防护）</li>
     *   <li>SemanticCacheService.tryHitCache() - 缓存命中检查</li>
     *   <li>DevOpsIntentRouter.routeEngine() - 智能路由分流</li>
     *   <li>CostQuotaManager.preCheck() - 成本配额预检</li>
     *   <li>DevOpsAgentEngine.chat() - 启动 ReAct Agent（TokenStream 原生流式）</li>
     *   <li>SemanticCacheService.putCache() - 写入缓存</li>
     *   <li>AgentMemoryManager.recordCompletedTurn() - 记忆落库（热+温）</li>
     *   <li>AgentLogService.saveLog() - 审计记账</li>
     * </ol>
     * </p>
     *
     * @param query     用户提问
     * @param traceId   本次请求追踪 ID（每请求唯一）
     * @param sessionId 会话 ID（多轮对话共享，为空时退化为 traceId）
     * @param emitter   SSE 推送器
     */
    void handleStreamChat(String query, String traceId, String sessionId, SseEmitter emitter);

    /**
     * 取消指定 traceId 的流式执行（P2-25）
     * <p>
     * 触发时机：SSE 连接断开、超时、错误时由 Controller 回调调用。
     * 设置取消标记后，{@code streamAgent} 中的 {@code done.join()} 轮询循环
     * 检测到标记即退出，不再阻塞等待模型流结束。
     * </p>
     *
     * @param traceId 要取消的追踪 ID
     */
    void cancelStream(String traceId);
}
