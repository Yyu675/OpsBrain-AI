package com.devops.agent.application.impl;

import cn.dev33.satoken.stp.StpUtil;

import com.devops.agent.application.runtime.CostQuotaManager;
import com.devops.agent.application.runtime.CostQuotaManager.ModelType;
import com.devops.agent.application.context.ContextBudgetManager;
import com.devops.agent.application.runtime.AgentState;
import com.devops.agent.application.runtime.AgentStateManager;
import com.devops.agent.application.runtime.AgentStateTransition;
import com.devops.agent.application.DevOpsAgentService;
import com.devops.agent.application.router.DevOpsAgentEngine;
import com.devops.agent.application.router.DevOpsIntentRouter;
import com.devops.agent.common.context.TraceContext;
import com.devops.agent.domain.rag.AgentKnowledgeScopeHolder;
import com.devops.agent.domain.rag.KnowledgeScope;
import com.devops.agent.domain.rag.KnowledgeScopeResolver;
import com.devops.agent.common.exception.SecurityGuardException;
import com.devops.agent.common.guard.SecurityInputGuard;
import com.devops.agent.application.memory.AgentMemoryManager;
import com.devops.agent.application.runtime.SagaCompensationManager;
import com.devops.agent.application.runtime.ToolRuntimeManager;
import com.devops.agent.domain.biz.service.AgentLogService;
import com.devops.agent.domain.biz.service.TicketService;
import com.devops.agent.application.runtime.TicketDraftParser;
import com.devops.agent.domain.tools.TicketDraft;
import com.devops.agent.domain.tools.ToolExecutionRecord;
import com.devops.agent.domain.tools.ToolExecutionState;
import com.devops.agent.domain.tools.ToolFailureType;
import com.devops.agent.domain.tools.ToolMeta;
import com.devops.agent.domain.tools.ToolRiskLevel;
import com.devops.agent.infrastructure.persistence.repo.ToolExecutionRepository;
import org.springframework.beans.factory.annotation.Value;
import com.devops.agent.infrastructure.cache.SemanticCacheService;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.tool.ToolExecution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutionException;
import com.devops.agent.infrastructure.concurrent.ManagedExecutors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Agent 编排调度服务实现
 * <p>
 * 职责: 编排完整问答流程 - 安全门卫 → 缓存 → 路由 → 流式引擎 → 记账
 * </p>
 * <p>
 * 流式实现（A2 方案）：基于 LangChain4j 原生 TokenStream
 * <ul>
 *   <li>onPartialResponse → token 事件（真流式，逐块推送）</li>
 *   <li>onToolExecuted → tool_status 事件 + 收集 toolResults</li>
 *   <li>onCompleteResponse → 写缓存 + 异步记账 + complete 事件</li>
 *   <li>onError → error 事件</li>
 * </ul>
 *
 * @author OpsBrain AI
 * @since 2026-07-15
 */
@Service
public class DevOpsAgentServiceImpl implements DevOpsAgentService {

    private static final Logger log = LoggerFactory.getLogger(DevOpsAgentServiceImpl.class);

    private final SecurityInputGuard securityGuard;
    private final SemanticCacheService cacheService;
    private final DevOpsIntentRouter intentRouter;
    private final AgentLogService logService;
    private final ObjectMapper objectMapper;
    private final ContextBudgetManager budgetManager;
    private final AgentStateManager stateManager;
    private final CostQuotaManager quotaManager;
    private final TicketService ticketService;
    private final AgentMemoryManager memoryManager;
    private final ToolExecutionRepository toolExecRepo;
    private final ToolRuntimeManager toolRuntimeManager;
    private final SagaCompensationManager sagaManager;
    private final TicketDraftParser draftParser;
    /** LangChain4j 对话记忆提供器（P0-1 多轮记忆）；缓存命中时需手动写入窗口（R-Gap1） */
    private final ChatMemoryProvider chatMemoryProvider;

    /**
     * 高风险工单是否需人工审批（P1-3）
     * <p>
     * L1 阶段默认关闭以保持现有体验；L3 引入审批工作流后置为 true。
     * 关闭时 HIGH 优先级工单仍会记录审批标记，仅不阻断写入。
     * </p>
     */
    @Value("${devops.ai.approval.high-priority-ticket:false}")
    private boolean approvalRequired;

    /**
     * 会话主流程执行器（P1-3）。
     * <p>
     * 采用 JDK 21 虚拟线程（{@code ThreadPerTask} 模式，每会话独占一个轻量线程）。
     * 会话主流程含 {@code done.join()} 阻塞等待流式回调（单轮可达数十秒），
     * 若沿用固定 4 线程池，4 并发即占满全部 OS 线程，后续会话排队——
     * 与"支持 100+ 并发"目标直接冲突。虚拟线程下 {@code join} 只挂起虚拟线程，
     * 不占用平台线程，可承载大量并发阻塞会话。
     * </p>
     */
    private final Executor sessionExecutor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory());

    /**
     * 审计记账执行器（P1-3）。
     * <p>
     * 固定 2 线程，专用于 {@link #recordLogAsync} 落 {@code sys_agent_call_log}。
     * 审计任务瞬时量大（每结束/失败请求 1 条）但单条快速完成，2 线程足够；
     * 与会话执行器物理隔离，避免记账高峰挤占会话线程导致 SSE 延迟。
     * </p>
     */
    // F4：改用有界队列。Executors.newFixedThreadPool 内部是无参
    // LinkedBlockingQueue（容量 Integer.MAX_VALUE），审计任务堆积时会
    // 无声无息地涨到 OOM——线程池指标一切正常，直到内存耗尽。
    // 审计属「丢了就是证据缺失」，故队列满时用 CallerRuns 退化为同步写，
    // 宁可让这次请求慢一点，也不丢记账。
    private final ExecutorService auditExecutor =
            ManagedExecutors.forCriticalWrites("agent-audit", 2, 1000);

    /**
     * 取消标记表（P2-25）。
     * <p>
     * key = traceId，value = 取消标记。Controller 的 SSE 断连/超时/错误回调
     * 调用 {@link #cancelStream} 置位；流式回调与轮询循环检测到标记后
     * 提前退出。标记在流结束后由 finally 清理，避免无界增长（P2-23 教训）。
     * </p>
     */
    private final ConcurrentHashMap<String, AtomicBoolean> cancelFlags = new ConcurrentHashMap<>();

    /** 审批服务（方向 D）：高危工单落审批单，替代此前的「丢弃」 */
    private final com.devops.agent.domain.approval.ApprovalService approvalService;

    /** C1：知识可见范围解析器。必须在请求线程调用（依赖 Sa-Token ThreadLocal） */
    private final KnowledgeScopeResolver knowledgeScopeResolver;

    public DevOpsAgentServiceImpl(SecurityInputGuard securityGuard,
                                   SemanticCacheService cacheService,
                                   DevOpsIntentRouter intentRouter,
                                   AgentLogService logService,
                                   ObjectMapper objectMapper,
                                   ContextBudgetManager budgetManager,
                                   AgentStateManager stateManager,
                                   CostQuotaManager quotaManager,
                                   TicketService ticketService,
                                   AgentMemoryManager memoryManager,
                                   ToolExecutionRepository toolExecRepo,
                                   ToolRuntimeManager toolRuntimeManager,
                                   SagaCompensationManager sagaManager,
                                   TicketDraftParser draftParser,
                                   com.devops.agent.domain.approval.ApprovalService approvalService,
                                   ChatMemoryProvider chatMemoryProvider,
                                   KnowledgeScopeResolver knowledgeScopeResolver) {
        this.securityGuard = securityGuard;
        this.cacheService = cacheService;
        this.intentRouter = intentRouter;
        this.logService = logService;
        this.objectMapper = objectMapper;
        this.budgetManager = budgetManager;
        this.stateManager = stateManager;
        this.quotaManager = quotaManager;
        this.ticketService = ticketService;
        this.memoryManager = memoryManager;
        this.toolExecRepo = toolExecRepo;
        this.toolRuntimeManager = toolRuntimeManager;
        this.sagaManager = sagaManager;
        this.draftParser = draftParser;
        this.approvalService = approvalService;
        this.chatMemoryProvider = chatMemoryProvider;
        this.knowledgeScopeResolver = knowledgeScopeResolver;
    }

    /**
     * 优雅停机（F4）：重新部署时把队列里待写的审计落完再退出。
     * <p>此前无 @PreDestroy，重启会静默丢弃在途记账。</p>
     * <p>sessionExecutor 是虚拟线程 per-task 执行器，无队列可积压，
     * 且 SSE 连接本身会被容器关闭，无需额外处理。</p>
     */
    @jakarta.annotation.PreDestroy
    public void shutdownExecutors() {
        ManagedExecutors.shutdownGracefully(auditExecutor, "agent-audit", 5);
    }

    @Override
    public void handleStreamChat(String query, String traceId, SseEmitter emitter) {
        // 无会话 ID 时退化为单轮：sessionId = traceId，不产生跨轮记忆
        handleStreamChat(query, traceId, traceId, emitter);
    }

    @Override
    public void handleStreamChat(String query, String traceId, String sessionIdParam, SseEmitter emitter) {
        long startTime = System.currentTimeMillis();
        // 会话 ID 兜底：为空则退化为 traceId（单轮模式）
        final String sessionId = (sessionIdParam != null && !sessionIdParam.isBlank())
                ? sessionIdParam : traceId;

        // 配额 key 必须在此解析——当前仍是 Controller 请求线程，Sa-Token 登录上下文（ThreadLocal）可用。
        // 下方 runAsync 一旦切到 sessionExecutor 虚拟线程，StpUtil.isLogin() 就取不到登录态了
        // （与 6.6 traceId 跨线程同源）。解析为 final 供异步闭包捕获，优先 userId 回退 sessionId。
        final String quotaKey = resolveQuotaKey(sessionId);

        // C1：知识可见范围同样必须在请求线程解析——Sa-Token 登录态是 ThreadLocal，
        // 切到 sessionExecutor 虚拟线程后就取不到了（与 quotaKey 同源约束）。
        final KnowledgeScope knowledgeScope = knowledgeScopeResolver.resolveCurrent();

        CompletableFuture.runAsync(() -> {
            try {
                // 设置 traceId 到 ThreadLocal(供工单创建时使用)
                TraceContext.setTraceId(traceId);
                // 把可见范围放进本线程，供工具检索取用。
                // 注意：工具多数时候跑在模型 HTTP 回调线程，取不到本值，
                // 届时会退化为「仅 PUBLIC」——这是刻意的保守失败方向。
                AgentKnowledgeScopeHolder.set(knowledgeScope);

                // 初始化会话状态（getOrCreateSession 已置初始态 NEW，无需再迁移到 NEW）
                stateManager.getOrCreateSession(traceId, sessionId);

                // ========== 步骤 0: 加载记忆 + 上下文预算预检 (P1-1 + MVP-1) ==========
                log.debug("🔍 [Step 0/7] 加载记忆与预算预检 | traceId={}", traceId);

                // 三层记忆：热记忆最近对话 + 温记忆关键事实
                var memCtx = memoryManager.loadContext(sessionId);
                List<String> history = new ArrayList<>(memCtx.recentHistory());
                if (memCtx.keyFactsText() != null) {
                    // 关键事实置于历史最前，作为长期记忆锚点
                    history.add(0, memCtx.keyFactsText());
                }

                // 预算分配：必选项 + 历史（按预算裁剪）
                var budgetCheck = budgetManager.allocate(query, history, List.of(), List.of());
                if (!budgetCheck.isWithinBudget()) {
                    log.warn("⚠️ [Budget] 查询超出预算 | traceId={} | reason={}", traceId, budgetCheck.getDegradationReason());
                    transitionOrWarn(traceId, AgentState.FAILED,
                            AgentStateTransition.TriggerType.FAILED, "预算超限: " + budgetCheck.getDegradationReason());
                    recordLogAsync(traceId, query, "预算超限: " + budgetCheck.getDegradationReason(),
                            "none", false, (int) (System.currentTimeMillis() - startTime), 0.0, "[]",
                            "REJECTED_BUDGET", "[]", "SYSTEM");
                    sendStartEvent(emitter, traceId, "budget_exceeded");
                    sendErrorEvent(emitter, traceId, 40006, "问题过长，超出模型上下文窗口限制，请精简问题后重试");
                    emitter.complete();
                    return;
                }
                log.debug("📊 [Budget] 预检通过 | used={}/{} tokens", budgetCheck.getUsedTokens(), budgetCheck.getTotalBudget());

                // ========== 步骤 1: 安全门卫拦截 ==========
                log.debug("🔍 [Step 1/7] 安全门卫检查 | traceId={}", traceId);
                securityGuard.check(query);
                transitionOrWarn(traceId, AgentState.CONTEXT_PREPARED,
                        AgentStateTransition.TriggerType.SECURITY_PASSED, "安全检查通过");

                // ========== 步骤 2: 语义缓存检查 ==========
                log.debug("🔍 [Step 2/7] 语义缓存检查 | traceId={}", traceId);
                // C2：缓存按权限域隔离。不带 scope 的话，高权限用户问出的答案
                // 会被低权限用户用一个语义相近的问题命中，绕过全部权限检查。
                String cachedAnswer = cacheService.tryHitCache(query, knowledgeScope.cacheScopeKey());

                if (cachedAnswer != null) {
                    log.info("⚡ [CacheHit] 命中缓存 | traceId={}", traceId);
                    transitionOrWarn(traceId, AgentState.SUCCESS,
                            AgentStateTransition.TriggerType.CACHE_HIT, "语义缓存命中");
                    // 记账（缓存命中）
                    recordLogAsync(traceId, query, cachedAnswer, "cache", true,
                            (int)(System.currentTimeMillis() - startTime), 0.0, "[]",
                            "CACHE_HIT", "[]", "SYSTEM");
                    // 记忆：缓存命中同样计入对话历史，保证多轮连贯
                    memoryManager.recordUserTurn(sessionId, query);
                    memoryManager.recordCompletedTurn(sessionId, traceId, query, cachedAnswer,
                            List.of(), 0, 0.0, AgentState.SUCCESS.name());

                    // R-Gap1：缓存命中时消息不进 LangChain4j 对话窗口（engine.chat() 被跳过），
                    // 连续命中会让模型失去多轮上下文。这里手动把本轮 Q/A 写入窗口，
                    // 使下一轮 engine.chat() 仍能看到完整的对话历史。
                    // 窗口实例与引擎共享 InMemoryChatMemoryStore（按 memoryId=sessionId 持久化），
                    // 手动 add 的内容对后续引擎调用可见。失败仅降级，不阻断缓存链路。
                    try {
                        ChatMemory mem = chatMemoryProvider.get(sessionId);
                        mem.add(UserMessage.from(query));
                        mem.add(AiMessage.from(cachedAnswer));
                    } catch (Exception e) {
                        log.warn("⚠️ [Memory] 缓存命中写入对话窗口失败 | traceId={} | {}", traceId, e.getMessage());
                    }

                    sendStartEvent(emitter, traceId, "cache");
                    simulateTypingEffect(emitter, cachedAnswer, traceId);
                    sendCompleteEvent(emitter, traceId, System.currentTimeMillis() - startTime,
                            true, 0.0, List.of(), List.of());
                    emitter.complete();
                    return;
                }

                // ========== 步骤 3: 智能路由分流 ==========
                log.debug("🔍 [Step 3/7] 智能路由分流 | traceId={}", traceId);
                // P2-18：一次路由决策同时返回引擎与模型名，避免重复正则匹配
                var routing = intentRouter.route(query);
                String routedModel = routing.modelName();
                DevOpsAgentEngine engine = routing.engine();
                // 注：路由不改变状态（仍在 CONTEXT_PREPARED），仅记录路由决策供审计

                // ========== 步骤 3.5: 成本/Token 配额预检 (MVP-7) ==========
                log.debug("🔍 [Step 3.5/7] 成本配额预检 | traceId={}", traceId);
                int estimatedTokens = budgetManager.estimateTokensPublic(query) + 1500; // 输入估算 + 输出预留
                CostQuotaManager.ModelType modelType = routedModel.contains("reasoner") || routedModel.contains("max") ?
                        CostQuotaManager.ModelType.REASONER : CostQuotaManager.ModelType.TURBO;
                // quotaKey 已在方法入口（请求线程）解析为 final，此处直接用（闭包捕获）
                var quotaCheck = quotaManager.preCheck(quotaKey, estimatedTokens, modelType);
                if (!quotaCheck.isAllowed()) {
                    log.warn("⚠️ [Quota] 配额超限 | traceId={} | reason={} | detail={}", traceId, quotaCheck.getReason(), quotaCheck.getDetail());
                    transitionOrWarn(traceId, AgentState.FAILED,
                            AgentStateTransition.TriggerType.FAILED, "配额超限: " + quotaCheck.getReason());
                    recordLogAsync(traceId, query, quotaCheck.getReason() + " | " + quotaCheck.getDetail(),
                            routedModel, false, (int) (System.currentTimeMillis() - startTime), 0.0, "[]",
                            "REJECTED_QUOTA", "[]", "SYSTEM");
                    sendStartEvent(emitter, traceId, "quota_exceeded");
                    sendErrorEvent(emitter, traceId, 40005, "请求超出配额限制: " + quotaCheck.getReason() + "，请稍后重试或联系管理员");
                    emitter.complete();
                    return;
                }
                log.debug("💰 [Quota] 预检通过 | estimatedTokens={} | model={}", estimatedTokens, modelType);

                // 发送 start 事件
                sendStartEvent(emitter, traceId, routedModel);

                // 记忆：用户提问入热记忆（在引擎执行前，保证异常时提问不丢）
                memoryManager.recordUserTurn(sessionId, query);

                // ========== 步骤 4: 流式执行 Agent 引擎 ==========
                log.debug("🔍 [Step 4/7] 流式执行 Agent 引擎 | model={} | traceId={}", routedModel, traceId);
                streamAgent(engine, query, traceId, sessionId, routedModel, startTime, emitter, modelType, quotaKey,
                        knowledgeScope);

            } catch (SecurityGuardException e) {
                log.warn("🚫 [SecurityGuard] 拦截 | traceId={} | reason={}", traceId, e.getMessage());
                transitionOrWarn(traceId, AgentState.FAILED,
                        AgentStateTransition.TriggerType.FAILED, "安全拦截: " + e.getMessage());
                // 安全拦截必须留痕：记录攻击特征供红队复盘与规则调优
                recordLogAsync(traceId, query, "[" + e.getCode() + "] " + e.getMessage(),
                        "none", false, (int) (System.currentTimeMillis() - startTime), 0.0, "[]",
                        "REJECTED_SECURITY", "[]", "SYSTEM");
                sendErrorEvent(emitter, traceId, e.getCode(), e.getUserMessage());
                emitter.complete();

            } catch (Exception e) {
                log.error("❌ [AgentService] 异常 | traceId={}", traceId, e);
                // 审计里必须带上**异常类型**，不能只写 getMessage()。
                // NPE 这类异常 message 恒为 null，只记消息的话审计表里
                // 只剩一句「系统异常: null」——等于什么都没记，
                // 而客户端拿到的又是兜底的 50001，两头都看不出原因。
                String detail = e.getClass().getSimpleName()
                        + (e.getMessage() != null ? ": " + e.getMessage() : "");
                // 带上首个业务栈帧：异常类型本身往往不足以定位
                // （ConcurrentModificationException 可能来自任何一处共享集合遍历）。
                // 只取 com.devops 包内的第一帧，避免把框架栈全塞进审计字段。
                // 取前 4 帧且**不限包名**。
                //
                // 只取 com.devops 帧看起来更"干净"，但异常发生在第三方库内部时
                // 就只能看到「调用它的那一行」——本项目为此多花了两轮 CI：
                // 先报 streamAgent:681，修掉一处后变成 streamAgent:467，
                // 两者都只是调用点。放开包名限制后一次就看到了真正的
                // AbstractGuardrailService.hasInputGuardrails（LangChain4j 框架缺陷）。
                //
                // 4 帧是权衡：足够穿透一层框架封装，又不至于把整个栈塞进审计字段。
                StringBuilder frames = new StringBuilder();
                StackTraceElement[] st = e.getStackTrace();
                for (int i = 0; i < Math.min(4, st.length); i++) {
                    StackTraceElement f = st[i];
                    String cls = f.getClassName();
                    frames.append(" @").append(cls.substring(cls.lastIndexOf('.') + 1))
                            .append('.').append(f.getMethodName())
                            .append(':').append(f.getLineNumber());
                }
                detail += frames;
                transitionOrWarn(traceId, AgentState.FAILED,
                        AgentStateTransition.TriggerType.SYSTEM_ERROR, "系统异常: " + detail);
                recordLogAsync(traceId, query, "系统异常: " + detail,
                        "none", false, (int) (System.currentTimeMillis() - startTime), 0.0, "[]",
                        "FAILED_SYSTEM", "[]", "SYSTEM");
                sendErrorEvent(emitter, traceId, 50001, "服务内部异常，请稍后重试或联系管理员");
                emitter.complete();
            } finally {
                // 清理 ThreadLocal 防止内存泄漏与跨请求串号
                TraceContext.clear();
                AgentKnowledgeScopeHolder.clear();
            }
        }, sessionExecutor);
    }

    /**
     * 订阅 TokenStream，将流式事件桥接为 SSE 事件
     * <p>
     * 在当前异步线程内阻塞等待流结束（CompletableFuture），
     * 保证缓存写入/记账在 complete 之后执行。
     * <p>
     * 状态机集成 (MVP-2)：关键节点记录状态迁移，支持审计/回放/断点恢复
     */
    /**
     * 解析配额 key：优先真实登录用户 userId（方向三 Sa-Token 鉴权），未登录回退 sessionId。
     * <p>
     * 必须在请求线程调用——Sa-Token 从当前请求上下文（ThreadLocal）读登录态，
     * 流式回调线程读不到。故 handleStreamChat 在请求线程算好后传入 streamAgent 闭包捕获。
     * </p>
     * <p>
     * "user:" 前缀避免与 sessionId 空间碰撞：登录用户配额按 userId 跨会话累积，
     * 未登录（理论上不会到这——/chat/stream 已鉴权）回退 sessionId 防 NPE。
     * </p>
     */
    private String resolveQuotaKey(String sessionId) {
        try {
            if (StpUtil.isLogin()) {
                return "user:" + StpUtil.getLoginIdAsLong();
            }
        } catch (Exception ignore) {
            // 非请求上下文或 Sa-Token 未就绪：回退 sessionId
        }
        return sessionId;
    }

    /**
     * @param knowledgeScope 请求线程解析出的可见范围。<b>必须由调用方传入</b>——
     *                       本方法的回调运行在模型 HTTP 线程，Sa-Token 的
     *                       ThreadLocal 早已丢失，在这里现取只会得到 null，
     *                       进而让语义缓存退化成不分权限域的共享缓存
     *                       （高权限用户的答案会被低权限用户命中）。
     */
    private void streamAgent(DevOpsAgentEngine engine, String query, String traceId,
                             String sessionId, String routedModel, long startTime,
                             SseEmitter emitter, CostQuotaManager.ModelType modelType, String quotaKey,
                             KnowledgeScope knowledgeScope) {
        // 注：会话与 CONTEXT_PREPARED 迁移已由 handleStreamChat 完成，此处不重复

        // 收集流式过程中的状态（工具结果、完整答案、引用出处）
        //
        // ⚠️ 必须用线程安全容器：这三者由**不同线程**读写。
        //
        //   写：onPartialResponse / onToolExecuted 跑在模型的 HTTP 回调线程；
        //   读：onCompleteResponse 里 toolResults.stream()、citations.stream()
        //       以及 answerBuilder.toString()。
        //
        // LangChain4j 不保证这些回调在同一条线程上，也不保证 onToolExecuted
        // 一定在 onCompleteResponse 之前**完全**结束——多工具场景下尤其如此。
        // 用普通 ArrayList 时，遍历与 add 撞车会抛 ConcurrentModificationException，
        // 被外层兜底 catch 吞成一句「50001 服务内部异常」，
        // 用户看到的是「AI 挂了」，而日志里连出事的集合都指不出来。
        //
        // 这个缺陷单请求下几乎撞不到（回调间隔远大于执行时间），
        // 只有并发或工具较多时才暴露——正是 SSE 并发集成测试抓出来的那一个。
        //
        // StringBuilder → StringBuffer：后者方法级 synchronized。
        // token 逐字追加与最终 toString() 同样跨线程。
        StringBuffer answerBuilder = new StringBuffer();
        List<Map<String, Object>> toolResults = new CopyOnWriteArrayList<>();
        List<String> citations = new CopyOnWriteArrayList<>();
        // P2-17：匹配工具结果中的引用标记 【来源：文档标题 - 章节】
        Pattern citationPattern = Pattern.compile("【来源：[^】]+】");
        CompletableFuture<Void> done = new CompletableFuture<>();

        // P0-1 多轮记忆注入：@MemoryId 绑定 sessionId，LangChain4j 自动按会话隔离对话历史
        TokenStream tokenStream = engine.chat(sessionId, query);

        // 状态：证据就绪（进入引擎，检索将在工具内完成）
        // P1-1：此处仍在 sessionExecutor 线程（streamAgent 由 handleStreamChat 同步调用），
        // TraceContext 可用，但为统一显式传 traceId 避免依赖线程上下文。
        transitionOrWarn(traceId, AgentState.EVIDENCE_READY,
                AgentStateTransition.TriggerType.RETRIEVAL_COMPLETED, "进入 Agent 引擎");

        tokenStream
                .onPartialResponse(partial -> {
                    // P2-25：取消后不再推送 token，避免断开后空耗前端
                    if (isCancelled(traceId)) return;
                    answerBuilder.append(partial);
                    sendTokenEvent(emitter, partial);
                })
                .onToolExecuted((ToolExecution toolExecution) -> {
                    String toolName = toolExecution.request().name();
                    String rawResult = toolExecution.result();
                    log.info("🔧 [Tool] 执行完成 | tool={} | traceId={}", toolName, traceId);

                    // P2-25：取消后不再写库建单，只记录日志避免留下用户不知情的工单
                    if (isCancelled(traceId)) {
                        log.warn("🛑 [Tool] 工具已取消，跳过写库 | tool={} | traceId={}", toolName, traceId);
                        return;
                    }

                    // 状态：工具执行中。
                    //
                    // ⚠️ 必须在 writeTicketFromDraft 之前迁移。
                    // 这两次迁移原本写在整个回调的末尾，排在写库之后——
                    // 而 writeTicketFromDraft 内部遇到高风险工单会迁往 WAITING_APPROVAL。
                    // 于是真实顺序变成「先 WAITING_APPROVAL、后 TOOLS_RUNNING」，
                    // 后者从 WAITING_APPROVAL 出发不合法，被静默丢弃；
                    // 更糟的是 WAITING_APPROVAL 本身也是从 EVIDENCE_READY 发起的（同样非法），
                    // 结果「需要审批」这件事根本没能进入状态机（P1-2）。
                    //
                    // 按真实发生顺序记录，才是状态机存在的意义：
                    // 工具开始执行 → 工具返回 → （写库，可能转审批）。
                    transitionOrWarn(traceId, AgentState.TOOLS_RUNNING,
                            AgentStateTransition.TriggerType.TOOL_STARTED, "工具调用：" + toolName);
                    transitionOrWarn(traceId, AgentState.TOOLS_COMPLETED,
                            AgentStateTransition.TriggerType.TOOL_COMPLETED, "工具返回：" + toolName);

                    // P1-3 Single Writer：从工具结果解析草稿，编排层统一落库
                    String displayResult;
                    String businessKey = null;
                    TicketDraft draft = null;
                    if ("createDevOpsTicket".equals(toolName)) {
                        draft = draftParser.parse(rawResult);
                        if (draft != null) {
                            WriteOutcome outcome = writeTicketFromDraft(draft, traceId, sessionId);
                            businessKey = outcome.ticketId();
                            displayResult = outcome.message();
                        } else {
                            // 草稿解析失败，剥离标记后保留原始文本
                            displayResult = draftParser.stripMarker(rawResult);
                        }
                    } else {
                        // 只读工具：直接使用原始结果
                        displayResult = rawResult;

                        // R-Gap2：从检索结果中提取引用出处 【来源：文档标题 - 章节】
                        // 前端 complete 事件的 citations 字段据此填充，使溯源信息可在对话记录中展示。
                        // 匹配到的标记去重后累积到 citations 列表，最终传给 sendCompleteEvent。
                        // 没有匹配时保持空列表，不会报错或降级。
                        Matcher matcher = citationPattern.matcher(rawResult);
                        while (matcher.find()) {
                            String citation = matcher.group();
                            if (!citations.contains(citation)) {
                                citations.add(citation);
                            }
                        }
                    }

                    // 发送 tool_status 事件（1.1.0 仅支持执行后回调，status=success）
                    // P2-35：tool_status 放在写操作之后，状态反映真实落库结果。
                    // 非草稿工具（只读）无写操作，直接标记 success。
                    String toolStatus = (draft != null && businessKey == null) ? "error" : "success";
                    sendToolStatusEvent(emitter, toolName, toolStatus, "工具执行完成：" + toolName);

                    // 收集工具结果（供前端识别工单创建等）
                    Object payload = buildToolResultPayload(toolName, displayResult, businessKey);
                    Map<String, Object> tr = new HashMap<>();
                    tr.put("toolName", toolName);
                    tr.put("result", payload);
                    toolResults.add(tr);
                })
                .onCompleteResponse(response -> {
                    try {
                        // 状态：草稿就绪
                        transitionOrWarn(traceId, AgentState.DRAFT_READY,
                                AgentStateTransition.TriggerType.DRAFT_GENERATED, "模型生成回答草稿");

                        String finalAnswer = answerBuilder.toString();

                        // 步骤 5: 写入语义缓存
                        log.debug("🔍 [Step 5/6] 写入语义缓存 | traceId={}", traceId);
                        // 写缓存必须带 scope，且与上面 tryHitCache 用同一个 key 口径。
                        // 读带 scope 而写不带，会把答案存进「无域」桶里，
                        // 下一个任意权限的用户都能命中——等于绕过全部权限检查。
                        cacheService.putCache(query, finalAnswer, knowledgeScope.cacheScopeKey());

                        // 步骤 6: 异步记账（MVP-4 审计增强 + MVP-7 成本记录）
                        log.debug("🔍 [Step 6/6] 异步记账 | traceId={}", traceId);
                        int latencyMs = (int) (System.currentTimeMillis() - startTime);

                        // 收集工具调用产生的资源标识
                        String affectedResources = toolResults.stream()
                                .map(tr -> tr.get("result"))
                                .filter(r -> r instanceof Map)
                                .map(r -> ((Map<?, ?>) r).get("ticketId"))
                                .filter(java.util.Objects::nonNull)
                                .map(Object::toString)
                                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
                        if (affectedResources.equals("[]")) affectedResources = "[]";

                        // MVP-7: 记录实际 Token 消耗与成本（用于配额扣减与审计回填）
                        // 估算：输入 tokens + 输出 tokens(约 finalAnswer.length/1.5)
                        int actualTokens = budgetManager.estimateTokensPublic(query) + budgetManager.estimateTokensPublic(finalAnswer);
                        double actualCost = (modelType == CostQuotaManager.ModelType.REASONER) ?
                                (actualTokens / 1000.0) * 0.02 : (actualTokens / 1000.0) * 0.002;
                        // P1-5：配额扣减 key 与预检保持一致（sessionId），跨轮累积
                        quotaManager.recordUsage(quotaKey, actualTokens, actualCost, modelType);

                        // R-Gap2：citations 序列化为 JSON 数组字符串供审计日志存储
                        String citationsJson = citations.isEmpty() ? "[]" :
                                "[" + citations.stream()
                                        .map(c -> "\"" + c.replace("\"", "\\\"") + "\"")
                                        .collect(java.util.stream.Collectors.joining(",")) + "]";

                        // P1-5：审计与 complete 事件回填真实 cost，不再硬编码 0.0
                        recordLogAsync(traceId, query, finalAnswer, routedModel, false, latencyMs, actualCost, citationsJson,
                                "CHAT", affectedResources, "SYSTEM");

                        // 状态：成功终态
                        transitionOrWarn(traceId, AgentState.SUCCESS,
                                AgentStateTransition.TriggerType.SUCCESS, "流程正常结束");

                        // P1-1 记忆：AI 回答入热记忆 + 关键事实蒸馏入温记忆
                        memoryManager.recordCompletedTurn(sessionId, traceId, query, finalAnswer,
                                toolResults, actualTokens, actualCost, AgentState.SUCCESS.name());

                        // 发送 complete 事件（P1-5：回填真实成本，前端可展示本次对话花费）
                        // R-Gap2：citations 从工具结果中提取的引用出处列表，前端据此展示溯源信息
                        sendCompleteEvent(emitter, traceId, latencyMs, false, actualCost, citations, toolResults);
                        emitter.complete();
                    } finally {
                        done.complete(null);
                    }
                })
                .onError(error -> {
                    try {
                        log.error("❌ [AgentStream] 流式执行异常 | traceId={}", traceId, error);

                        // ⚠️ 此处刻意不立即迁往 FAILED。
                        //
                        // 原实现在这里就把状态打成 FAILED，而 FAILED 是终态、拒绝一切迁出。
                        // 紧接着下面的 triggerSagaCompensation 在补偿失败时要迁往
                        // MANUAL_ESCALATED，那次迁移必然被拒并静默丢弃——
                        // 于是「Saga 回滚失败、有脏数据残留、需要人工清理」这个
                        // 最需要被看见的信号，在状态机里完全不存在，只剩一行 error 日志（P1-2）。
                        //
                        // 正确的时序是：先走完补偿流程（补偿本身可能改写状态为
                        // COMPENSATING / MANUAL_ESCALATED），再由补偿结果决定终态。
                        // 见下方 triggerSagaCompensation 调用处。

                        // 记账：保留已生成的部分回答，便于定位截断位置
                        String partial = answerBuilder.toString();
                        String errMsg = error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName();

                        // P1-5：失败路径也估算已发生的成本（按已生成部分），不再硬编码 0.0。
                        // 即便失败，已消耗的输入+输出 token 仍需扣减配额并落审计。
                        int failedLatencyMs = (int) (System.currentTimeMillis() - startTime);
                        int failedTokens = budgetManager.estimateTokensPublic(query)
                                + budgetManager.estimateTokensPublic(partial);
                        double failedCost = (modelType == CostQuotaManager.ModelType.REASONER) ?
                                (failedTokens / 1000.0) * 0.02 : (failedTokens / 1000.0) * 0.002;
                        // 失败也要扣减已发生的配额（key 与预检一致 = sessionId）
                        quotaManager.recordUsage(quotaKey, failedTokens, failedCost, modelType);

                        recordLogAsync(traceId, query,
                                (partial.isEmpty() ? "" : partial + "\n---\n") + "流式异常: " + errMsg,
                                routedModel, false, failedLatencyMs, failedCost, "[]",
                                "FAILED_STREAM", "[]", "SYSTEM");

                        // P1-1 记忆：失败也要蒸馏。用户提问里的错误码/配置/版本是高价值诊断信息，
                        // 丢弃会导致下一轮重复追问。已执行的工具结果同样保留。
                        memoryManager.recordCompletedTurn(sessionId, traceId, query,
                                partial.isEmpty() ? "（本轮未生成回答：" + errMsg + "）" : partial,
                                toolResults, 0, 0.0, AgentState.FAILED.name());

                        // P1-2 Saga：流式失败时逆序回滚已成功的写操作，避免半残状态。
                        // 典型场景：工单已建但模型后续生成失败，此时工单应作废。
                        // 补偿内部会把状态推进到 COMPENSATING，失败时再推进到 MANUAL_ESCALATED。
                        triggerSagaCompensation(traceId, "流式执行失败: " + errMsg);

                        // 状态：失败终态。
                        // 放在补偿之后，让补偿有机会先落下自己的状态轨迹。
                        // 若补偿已把会话升级为 MANUAL_ESCALATED（需人工清理脏数据），
                        // 就不再覆盖成 FAILED——「有残留待人工处理」比「失败了」信息量大得多，
                        // 且 MANUAL_ESCALATED 后续还要能走到 CLOSED 归档。
                        AgentState afterCompensation = stateManager.getCurrentState(traceId);
                        if (afterCompensation != AgentState.MANUAL_ESCALATED) {
                            transitionOrWarn(traceId, AgentState.FAILED,
                                    AgentStateTransition.TriggerType.SYSTEM_ERROR,
                                    "异常：" + error.getMessage());
                        }

                        sendErrorEvent(emitter, traceId, 50001, "Agent 执行失败，请稍后重试");
                        emitter.complete();
                    } finally {
                        done.completeExceptionally(error);
                    }
                })
                .start();

        // P2-25：取消感知的轮询等待。
        // 不能用 done.join()——它阻塞到模型流自然结束，用户已断开时 onToolExecuted 仍会写库建单。
        // 轮询每 200ms 检查一次取消标记，检测到即提前退出（模型流仍在跑，但回调内的取消检查点
        // 会阻止后续 token 推送与工单写库）。
        // 注意：此处只 WARN 不记账——onCompleteResponse/onError 仍在模型线程独立触发，
        // 由它们各自完成 CHAT/FAILED_STREAM 审计（6.6 铁律），轮询循环再记会重复记账。
        try {
            while (!done.isDone()) {
                if (isCancelled(traceId)) {
                    log.warn("🛑 [AgentStream] 流式被用户取消 | traceId={}", traceId);
                    break;
                }
                try {
                    done.get(200, TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    // 正常轮询间隔，继续检查取消标记
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("⚠️ [AgentStream] 轮询等待被中断 | traceId={}", traceId);
        } catch (ExecutionException e) {
            log.warn("⚠️ [AgentStream] 流式执行异常 | traceId={} | {}",
                    traceId, e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
        } finally {
            // P2-23 教训：标记用完即清，避免无界增长
            if (cancelFlags.remove(traceId) != null && !done.isDone()) {
                log.debug("🧹 [CancelStream] 取消标记已清理 | traceId={}", traceId);
            }
        }
    }

    /**
     * 写入结果
     *
     * @param ticketId 工单号，未创建则为 null
     * @param message  面向模型/前端的结果文本
     */
    private record WriteOutcome(String ticketId, String message) {
    }

    /**
     * 从草稿写入工单（P1-3 Single Writer 唯一写入点）
     * <p>
     * 执行顺序及其理由：
     * <ol>
     *   <li><b>审批检查</b>——在写库<i>之前</i>拦截高风险操作。
     *       此前工具直接写库，只能事后作废，脏数据已产生</li>
     *   <li><b>登记 Saga 步骤（PENDING）</b>——先登记再写库。
     *       若反序，写库成功而登记失败会留下无补偿记录的孤儿工单</li>
     *   <li><b>落库</b>——traceId 原生可用，无需事后回填</li>
     *   <li><b>更新步骤为 SUCCESS 并记录 businessKey</b>——补偿动作的入参</li>
     * </ol>
     *
     * @param draft     工单草稿
     * @param traceId   追踪 ID（同时作为 sagaId）
     * @param sessionId 会话 ID
     * @return 写入结果
     */
    private WriteOutcome writeTicketFromDraft(TicketDraft draft, String traceId, String sessionId) {
        final String toolName = "createDevOpsTicket";

        // ---- 步骤 1：写前审批检查 ----
        if (draft.needsApproval() && approvalRequired) {
            log.warn("🛑 [SingleWriter] 高风险工单需审批，转入审批队列 | title={} | traceId={}",
                    draft.title(), traceId);
            transitionOrWarn(traceId, AgentState.WAITING_APPROVAL,
                    AgentStateTransition.TriggerType.APPROVAL_REQUIRED, "HIGH 优先级工单待审批: " + draft.title());

            // 登记为 SKIPPED：未写库，无需补偿
            recordSagaStepWithState(traceId, sessionId, toolName,
                    "待审批未写入: " + draft.title(), null, ToolExecutionState.SUCCESS);

            // 方向 D：落审批单（而非丢弃）。payload 存 TicketDraft JSON，
            // 批准后 ApprovalOrchestrator.replayCreateTicket 据此重放建单。
            // 序列化失败则不谎称已入队——记 ERROR 并如实告知模型提交失败。
            Long approvalId = null;
            try {
                String payload = objectMapper.writeValueAsString(draft);
                approvalId = approvalService.submit(
                        "CREATE_TICKET", toolName, "CONTROLLED_WRITE",
                        "创建高优工单: " + draft.title(),
                        payload, "AI", traceId, sessionId);
            } catch (Exception e) {
                log.error("❌ [SingleWriter] 审批单提交失败，工单未创建 | title={} | traceId={} | {}",
                        draft.title(), traceId, e.getMessage(), e);
                return new WriteOutcome(null, String.format("""
                        ⚠️ 工单提交失败（审批单创建异常）。

                        标题: %s

                        请告知用户：系统暂时无法提交审批，请稍后重试。不要声称工单已创建或已进入审批。""",
                        draft.title()));
            }

            return new WriteOutcome(null, String.format("""
                    ⏸️ 工单已提交审批（审批单 #%d）。

                    标题: %s
                    优先级: %s（高风险，需人工审批）

                    请告知用户：该工单因优先级高已进入审批队列（单号 #%d），
                    管理员审批通过后会自动创建。请勿声称工单已创建成功。""",
                    approvalId, draft.title(), draft.priority(), approvalId));
        }

        // ---- 步骤 2：先登记 Saga 步骤（PENDING）----
        Long stepId = recordSagaStepWithState(traceId, sessionId, toolName,
                null, null, ToolExecutionState.PENDING);

        // ---- 步骤 3：落库（唯一写入点，traceId 原生可用）----
        String ticketId;
        try {
            ticketId = ticketService.saveTicket(
                    draft.title(),
                    draft.priority(),
                    draft.module(),
                    draft.description(),
                    null,       // stackTrace 暂无
                    traceId);   // 原生传入，不再事后回填
        } catch (Exception e) {
            log.error("❌ [SingleWriter] 工单写入失败 | title={} | traceId={} | {}",
                    draft.title(), traceId, e.getMessage(), e);

            if (stepId != null) {
                toolExecRepo.updateResult(stepId, ToolExecutionState.FAILED, null,
                        ToolFailureType.fromException(e), truncate(e.getMessage(), 500),
                        null, null, 1);
            }
            // P2-38：不向前端暴露内部异常详情，使用安全的泛化消息
            return new WriteOutcome(null, String.format("""
                    ❌ 工单创建失败：%s

                    请如实告知用户创建失败，不要声称成功，并建议稍后重试或联系管理员。""",
                    e.getMessage()));
        }

        // ---- 步骤 4：更新步骤为 SUCCESS，写入 businessKey 供补偿使用 ----
        if (stepId != null) {
            toolExecRepo.updateResult(stepId, ToolExecutionState.SUCCESS,
                    "工单创建成功 " + ticketId, null, null, traceId, null, 1);
        }

        log.info("✅ [SingleWriter] 工单已由编排层写入 | ticketId={} | traceId={}", ticketId, traceId);

        return new WriteOutcome(ticketId, String.format("""
                ✅ 工单创建成功。

                标题: %s
                优先级: %s
                模块: %s

                ⚠️ 工单号已由系统展示给用户，你无需提供工单号。
                请仅告知用户工单已创建，二级运维团队将在 30 分钟内响应。""",
                draft.title(), draft.priority(), draft.module()));
    }

    /**
     * 以指定状态登记 Saga 步骤
     *
     * @return 步骤主键，登记失败返回 null
     */
    private Long recordSagaStepWithState(String traceId, String sessionId, String toolName,
                                         String result, String businessKey,
                                         ToolExecutionState state) {
        try {
            ToolMeta meta = toolRuntimeManager.lookupMeta(toolName);

            ToolExecutionRecord rec = new ToolExecutionRecord();
            rec.setTraceId(traceId);
            rec.setSessionId(sessionId);
            rec.setSagaId(traceId);
            rec.setStepSeq(toolExecRepo.nextStepSeq(traceId));
            rec.setToolName(toolName);
            rec.setState(state);
            rec.setToolResult(truncate(result, 2000));
            rec.setBusinessKey(businessKey);

            if (meta != null) {
                rec.setRiskLevel(meta.riskLevel());
                // 可补偿：声明了补偿动作且为写操作。businessKey 在写库成功后补上，
                // 故此处不以其非空作为判定条件。
                rec.setCompensable(!meta.compensationAction().isBlank()
                        && meta.riskLevel() != ToolRiskLevel.READ_ONLY);
                rec.setCompensationAction(meta.compensationAction().isBlank() ? null : meta.compensationAction());
            } else {
                rec.setRiskLevel(ToolRiskLevel.READ_ONLY);
                rec.setCompensable(false);
            }

            Long id = toolExecRepo.insert(rec);
            if (id != null) {
                log.debug("📋 [Saga] 步骤已登记 | sagaId={} | step={} | tool={} | state={} | compensable={}",
                        traceId, rec.getStepSeq(), toolName, state, rec.getCompensable());
            }
            return id;
        } catch (Exception e) {
            log.error("🚨 [Saga] 步骤登记失败，该步骤将失去补偿能力 | tool={} | traceId={} | {}",
                    toolName, traceId, e.getMessage());
            return null;
        }
    }

    /**
     * 登记已完成的 Saga 步骤（只读工具用）
     * <p>
     * 写操作走 {@link #writeTicketFromDraft}，其内部以
     * {@code PENDING → SUCCESS} 两阶段登记以保证时序正确。
     * </p>
     *
     * @param traceId     追踪 ID，同时作为 sagaId
     * @param sessionId   会话 ID
     * @param toolName    工具名
     * @param result      执行结果
     * @param businessKey 业务标识
     */
    private void recordSagaStep(String traceId, String sessionId, String toolName,
                                String result, String businessKey) {
        recordSagaStepWithState(traceId, sessionId, toolName, result, businessKey,
                ToolExecutionState.SUCCESS);
    }

    /**
     * 迁移状态，失败时告警而非静默吞掉
     * <p>
     * {@code AgentStateManager.transition} 在迁移非法或会话不存在时返回 {@code null}。
     * 编排层此前 <b>14 处调用无一检查返回值</b>，导致状态机里任何一条边写错，
     * 表现形式都不是报错，而是「会话轨迹悄悄少了一段」——
     * 而这恰恰是状态机唯一的存在意义。
     * </p>
     * <p>
     * 本方法把这条静默路径变成显式告警。<b>刻意不抛异常</b>：
     * 状态记录是可观测性设施，不是业务前置条件，
     * 为了记一条轨迹而让用户的对话请求失败是本末倒置。
     * 但它必须<b>吵</b>——日志里带上当前状态与目标状态，
     * 让「哪条边缺了」在第一次发生时就能被定位，而不是靠事后逐行重放代码去推。
     * </p>
     *
     * @return true 表示迁移成功落地
     */
    private boolean transitionOrWarn(String traceId, AgentState toState,
                                     AgentStateTransition.TriggerType trigger, String detail) {
        AgentStateTransition t = stateManager.transition(
                traceId, toState, trigger, detail, "SYSTEM", null);
        if (t == null) {
            AgentState current = stateManager.getCurrentState(traceId);
            log.error("🚨 [StateMachine] 状态迁移未落地，会话轨迹将缺失一段 | traceId={} | "
                            + "current={} | target={} | trigger={} | detail={} | "
                            + "原因：{}",
                    traceId, current, toState, trigger, detail,
                    current == null ? "会话不存在（可能已被空闲清理，或 traceId 传错）"
                            : "该迁移在状态机中非法，请检查 AgentState.canTransition");
            return false;
        }
        return true;
    }

    /**
     * 触发 Saga 补偿（流式失败时逆序回滚已成功的写操作）
     *
     * @param traceId 追踪 ID（= sagaId）
     * @param reason  失败原因
     */
    private void triggerSagaCompensation(String traceId, String reason) {
        try {
            var result = sagaManager.compensateSaga(traceId, reason);

            // 「本次补偿是否真的动了东西」——compensateSaga 在无待补偿步骤时返回 noop。
            //
            // 状态迁移必须挂在这个判断里面，不能无条件执行。onError 是所有流式失败的
            // 公共出口，绝大多数失败（模型超时、网络抖动、安全拦截后的异常）
            // 根本没发生过写操作，此时会话还停在 EVIDENCE_READY 之类的早期状态，
            // 而 COMPENSATING 的合法前驱只有 TOOLS_COMPLETED / DRAFT_READY
            // （写操作只可能发生在工具阶段）。
            //
            // 若无条件迁移，这些「无事可补偿」的常规失败每次都会撞非法迁移，
            // 被 transitionOrWarn 打成 ERROR 日志——本轮刚加的告警会立刻变成噪音，
            // 而告警一旦开始狼来了，真正的问题就再也没人看了。
            boolean compensationRan = result.compensatedCount() > 0 || result.failedCount() > 0;

            if (compensationRan) {
                // 「正在回滚」此前从未进入过状态机：唯一一次迁移是补偿失败时的
                // MANUAL_ESCALATED，而那条边要求 from 是 WAITING_APPROVAL/COMPENSATING，
                // 编排层却从没把状态设成过 COMPENSATING，于是必然被拒。
                // 补上这一步，后续的人工升级才有合法起点。
                transitionOrWarn(traceId, AgentState.COMPENSATING,
                        AgentStateTransition.TriggerType.COMPENSATION_STARTED, "开始 Saga 补偿: " + reason);

                // 补偿动作本身要留痕
                recordLogAsync(traceId, "[Saga 补偿]",
                        String.format("回滚成功 %d 步%s", result.compensatedCount(),
                                result.failedCount() > 0 ? "，失败 " + result.failedCount() + " 步（需人工介入）" : ""),
                        "none", false, 0, 0.0, "[]",
                        result.needsManualIntervention() ? "COMPENSATION_FAILED" : "COMPENSATED",
                        objectMapper.writeValueAsString(result.compensated()), "SYSTEM");
            }

            if (result.needsManualIntervention()) {
                // 补偿失败：脏数据残留，自动化无法收敛
                log.error("🚨🚨 [Saga] 补偿未完全成功，需人工介入 | traceId={} | 失败步骤={}",
                        traceId, result.failed());
                transitionOrWarn(traceId, AgentState.MANUAL_ESCALATED,
                        AgentStateTransition.TriggerType.MANUAL_TAKEOVER, "Saga 补偿失败，需人工清理: " + result.failed());
            }
        } catch (Exception e) {
            log.error("🚨 [Saga] 触发补偿时异常 | traceId={} | {}", traceId, e.getMessage(), e);
        }
    }

    /**
     * 取消指定 traceId 的流式执行（P2-25）
     * <p>
     * 设置取消标记后，{@link #streamAgent} 中的轮询循环检测到标记即退出，
     * {@code onPartialResponse} / {@code onToolExecuted} / {@code simulateTypingEffect}
     * 中的取消检查点检测到后也提前返回，不再阻塞等待模型流结束。
     * </p>
     */
    @Override
    public void cancelStream(String traceId) {
        if (traceId == null || traceId.isBlank()) return;
        AtomicBoolean flag = cancelFlags.computeIfAbsent(traceId, k -> new AtomicBoolean());
        if (flag.compareAndSet(false, true)) {
            log.info("🛑 [CancelStream] 取消标记已置位 | traceId={}", traceId);
        } else {
            log.debug("🛑 [CancelStream] 取消标记已存在（重复取消） | traceId={}", traceId);
        }
    }

    /**
     * 检查指定 traceId 是否已被取消
     */
    private boolean isCancelled(String traceId) {
        return cancelFlags.getOrDefault(traceId, new AtomicBoolean(false)).get();
    }

    /**
     * 截断字符串
     */
    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /**
     * 构造工具结果的前端载荷
     * <p>
     * 对 createDevOpsTicket：从返回文本提取工单号，封装成 {"ticketId": "TKT-..."} JSON，
     * 与前端 AIChatDrawer 的解析逻辑对齐；提取不到则回传原始文本。
     */
    private Object buildToolResultPayload(String toolName, String displayResult, String ticketId) {
        // P1-3：工单号由编排层写库后直接持有，不再从文本正则提取。
        // 正则提取依赖工具返回格式，格式一变即失效；且模型可能复述错误的号。
        if (ticketId != null && !ticketId.isBlank()) {
            Map<String, Object> payload = new HashMap<>();
            payload.put("ticketId", ticketId);
            payload.put("message", displayResult);
            return payload;
        }
        return displayResult;
    }

    /**
     * 模拟打字机效果（缓存命中时逐字推送）
     * <p>
     * P2-15：原实现逐字推送（每次 1 字符/20ms），SSE 事件数 ≈ 文本长度，
     * 长文本（500 字）产生 500 个 SSE 事件， emitter 与前端压力大。
     * 改为每批 3 字符/50ms，事件数减少 3 倍，视觉速度相近（60 vs 50 字符/秒）。
     * </p>
     */
    private void simulateTypingEffect(SseEmitter emitter, String text, String traceId) {
        try {
            char[] chars = text.toCharArray();
            for (int i = 0; i < chars.length; i += 3) {
                // P2-25：取消后停止模拟打字，避免断开后空耗线程
                if (isCancelled(traceId)) {
                    log.warn("🛑 [TypingEffect] 模拟打字被取消 | traceId={}", traceId);
                    break;
                }
                int end = Math.min(i + 3, chars.length);
                sendTokenEvent(emitter, new String(chars, i, end - i));
                Thread.sleep(50);
            }
        } catch (Exception e) {
            log.warn("⚠️ [TypingEffect] 推送中断 | traceId={}", traceId, e);
        }
    }

    /**
     * 异步记账（MVP-4 审计增强版）
     */
    private void recordLogAsync(String traceId, String query, String answer, String model,
                                 boolean isCached, int latencyMs, double cost, String citations,
                                 String operationType, String affectedResources, String operatorId) {
        // A5：审计线程池是独立线程，MDC 不会自动传播。用 TraceContext.wrap 搬运，
        // 否则「记账失败」这类日志将不带 traceId，恰恰是最需要关联排查的场景。
        CompletableFuture.runAsync(TraceContext.wrap(() -> {
            try {
                logService.saveLog(traceId, query, answer, model, isCached, latencyMs, cost, citations,
                        operationType, affectedResources, operatorId);
            } catch (Exception e) {
                log.error("❌ [AgentLog] 记账失败 | traceId={}", traceId, e);
            }
        }), auditExecutor);
    }

    /**
     * 异步记账（兼容旧调用）
     */
    private void recordLogAsync(String traceId, String query, String answer, String model,
                                 boolean isCached, int latencyMs, double cost, String citations) {
        recordLogAsync(traceId, query, answer, model, isCached, latencyMs, cost, citations,
                "CHAT", "[]", "SYSTEM");
    }

    // ==================== SSE 事件发送工具方法 ====================

    private void sendStartEvent(SseEmitter emitter, String traceId, String routerModel) {
        Map<String, Object> data = new HashMap<>();
        data.put("traceId", traceId);
        data.put("routerModel", routerModel);
        sendEvent(emitter, "start", data);
    }

    private void sendTokenEvent(SseEmitter emitter, String text) {
        Map<String, Object> data = new HashMap<>();
        data.put("text", text);
        sendEvent(emitter, "token", data);
    }

    private void sendToolStatusEvent(SseEmitter emitter, String toolName, String status, String message) {
        Map<String, Object> data = new HashMap<>();
        data.put("toolName", toolName);
        data.put("status", status);
        data.put("message", message);
        sendEvent(emitter, "tool_status", data);
    }

    private void sendCompleteEvent(SseEmitter emitter, String traceId, long latencyMs,
                                    boolean isCached, double costRmb,
                                    List<String> citations, List<Map<String, Object>> toolResults) {
        Map<String, Object> data = new HashMap<>();
        data.put("traceId", traceId);
        data.put("latencyMs", latencyMs);
        data.put("isCached", isCached);
        data.put("costRmb", costRmb);
        data.put("citations", citations);
        data.put("toolResults", toolResults);
        sendEvent(emitter, "complete", data);
    }

    private void sendErrorEvent(SseEmitter emitter, String traceId, int code, String message) {
        Map<String, Object> data = new HashMap<>();
        data.put("traceId", traceId);
        data.put("code", code);
        data.put("message", message);
        sendEvent(emitter, "error", data);
    }

    /**
     * 发送 SSE 事件
     * <p>
     * 统一用 ObjectMapper 序列化（自动完成 JSON 转义），
     * 不再手工 escapeJson，避免双重转义。
     */
    private void sendEvent(SseEmitter emitter, String eventName, Map<String, Object> data) {
        try {
            String json = objectMapper.writeValueAsString(data);
            emitter.send(SseEmitter.event().name(eventName).data(json));
        } catch (IOException e) {
            log.error("❌ [SSE] 事件发送失败 | event={}", eventName, e);
        }
    }
}
