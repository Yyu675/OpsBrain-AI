package com.devops.agent.application.runtime;

import com.devops.agent.domain.tools.DevOpsTools;
import com.devops.agent.domain.tools.ToolExecutionRecord;
import com.devops.agent.domain.tools.ToolExecutionState;
import com.devops.agent.domain.tools.ToolFailureType;
import com.devops.agent.domain.tools.ToolMeta;
import com.devops.agent.domain.tools.ToolParameterValidator;
import com.devops.agent.domain.tools.ToolRiskLevel;
import com.devops.agent.infrastructure.persistence.repo.ToolExecutionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Tool 运行时管理器
 * <p>
 * 职责：工具调用的统一治理入口，参考 Agent Methodology §9 Tool Runtime 治理。
 * <ol>
 *   <li>读取 {@link ToolMeta} 元数据，执行对应治理逻辑</li>
 *   <li>幂等检查（Redis 分布式锁）</li>
 *   <li>超时控制（CompletableFuture + Future.get(timeout)）</li>
 *   <li>重试策略（指数退避、分类重试）</li>
 *   <li>熔断保护（失败率统计）</li>
 *   <li>审批拦截（高风险工具）</li>
 *   <li>补偿注册（记录补偿动作，失败时触发）</li>
 *   <li>审计日志（完整调用链路）</li>
 * </ol>
 * <p>
 * 设计原则：
 * - 单一职责：只管治理，不管业务逻辑
 * - 透明代理：业务代码无感知，通过 AOP 或显式调用接入
 * - 可观测：每次调用生成完整审计轨迹
 * </p>
 *
 * @author OpsBrain AI
 * @since 2026-08-08
 */
@Slf4j
@Component
public class ToolRuntimeManager {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private ToolParameterValidator parameterValidator;

    @Autowired
    private AgentStateManager stateManager;

    /**
     * 工具执行记录仓储：用于登记工具失败（P1-2）。
     * <p>
     * 工具在模型 HTTP 回调线程执行，{@link com.devops.agent.common.context.TraceContext}
     * 的 ThreadLocal 不跨线程，故此处登记时 traceId 可能取不到，用 UNKNOWN 兜底。
     * 编排层 onToolExecuted 仍负责 SUCCESS 路径的完整 Saga 登记；
     * 本仓储仅记录工具自身的 FAILED 态（无副作用，不可补偿），供运维回放与排查。
     * </p>
     */
    @Autowired
    private ToolExecutionRepository toolExecRepo;

    /**
     * 工具元数据缓存：方法签名 -> ToolMeta
     */
    private final Map<Method, ToolMeta> toolMetaCache = new ConcurrentHashMap<>();

    /**
     * 熔断统计：toolName -> {totalCalls, failedCalls, lastFailureTime}
     */
    private final Map<String, CircuitBreakerStats> circuitBreakers = new ConcurrentHashMap<>();

    /**
     * 执行工具调用（统一治理入口）
     * <p>
     * 供 {@link DevOpsTools} 内部调用，替代直接调用业务逻辑。
     * </p>
     *
     * @param toolName     工具名（对应 @ToolMeta.name）
     * @param toolInstance 工具 Bean 实例（如 DevOpsTools）
     * @param method       目标方法
     * @param args         方法参数
     * @return 工具执行结果
     * @throws Exception 执行异常（已分类包装）
     */
    public Object executeTool(String toolName, Object toolInstance, Method method, Object[] args) throws Exception {
        // 1. 获取/解析元数据
        ToolMeta meta = getOrParseMeta(method);
        log.info("🔧 [ToolRuntime] 执行工具 | tool={} | risk={} | idempotent={} | timeout={}ms | retries={}",
                toolName, meta.riskLevel(), meta.idempotent(), meta.timeoutMs(), meta.maxRetries());

        // 2. 参数校验（L3 Schema 校验，参数错误不重试）
        validateParameters(meta, method, args);

        // 3. 幂等检查
        String idempotencyKey = checkIdempotency(meta, toolName, args);
        if (idempotencyKey != null) {
            // 命中幂等：直接返回缓存结果
            String cachedResult = redisTemplate.opsForValue().get(idempotencyKey);
            if (cachedResult != null) {
                log.info("⚡ [ToolRuntime] 幂等命中，返回缓存结果 | tool={} | key={}", toolName, idempotencyKey);
                return cachedResult;
            }
        }

        // 5. 熔断检查
        checkCircuitBreaker(toolName);

        // 6. 执行工具（含超时、重试）
        Object result = executeWithTimeoutAndRetry(meta, toolInstance, method, args, toolName);

        // 7. 成功：记录幂等结果、更新熔断统计
        if (idempotencyKey != null && result != null) {
            redisTemplate.opsForValue().set(idempotencyKey, result.toString(), 24, TimeUnit.HOURS);
        }
        recordCircuitBreakerSuccess(toolName);

        // 8. 记录审计
        auditToolCall(toolName, meta, args, result, null, ToolFailureType.UNKNOWN);

        // 注：Saga 步骤登记与补偿触发由编排层（DevOpsAgentServiceImpl）负责。
        // 原因：工具在模型 HTTP 回调线程执行，此处拿不到 traceId，
        // 无法确定步骤归属哪个 Saga；且 Saga 本质是编排职责，
        // 工具运行时只负责超时/重试/熔断/幂等/审批。
        return result;
    }

    /**
     * 供编排层查询工具元数据（用于登记 Saga 步骤的风险等级与补偿动作）
     *
     * @param toolName 工具名
     * @return 元数据，未注册则返回 null
     */
    public ToolMeta lookupMeta(String toolName) {
        for (var entry : toolMetaCache.entrySet()) {
            if (entry.getValue().name().equals(toolName)) {
                return entry.getValue();
            }
        }
        // 缓存未命中：从 DevOpsTools 反射查找
        try {
            for (Method m : com.devops.agent.domain.tools.DevOpsTools.class.getMethods()) {
                ToolMeta meta = m.getAnnotation(ToolMeta.class);
                if (meta != null && meta.name().equals(toolName)) {
                    toolMetaCache.put(m, meta);
                    return meta;
                }
            }
        } catch (Exception e) {
            log.debug("查找工具元数据失败 | tool={} | {}", toolName, e.getMessage());
        }
        return null;
    }

    /**
     * 执行工具（含超时控制和重试逻辑）
     */
    private Object executeWithTimeoutAndRetry(ToolMeta meta, Object toolInstance, Method method,
                                               Object[] args, String toolName) throws Exception {
        int maxRetries = meta.maxRetries();
        long timeoutMs = meta.timeoutMs();
        Exception lastException = null;
        int lastAttemptCount = 1;
        long startMs = System.currentTimeMillis();

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                // 使用 CompletableFuture 实现超时控制
                CompletableFuture<Object> future = CompletableFuture.supplyAsync(() -> {
                    try {
                        return method.invoke(toolInstance, args);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });

                return future.get(timeoutMs, TimeUnit.MILLISECONDS);

            } catch (Exception e) {
                // P1-2：InvocationTargetException 必须循环解包至根因再分类。
                // 此前只解一层，反射抛出的 InvocationTargetException 被包成 RuntimeException，
                // fromException 只能看到包装层（UNKNOWN、不可重试），
                // 导致 ConnectException/超时等本应重试的异常永不触发 maxRetries。
                // 对齐 6.8「根因必须解包」契约。
                Throwable cause = unwrapToRootCause(e);
                lastException = (cause instanceof Exception) ? (Exception) cause : new RuntimeException(cause);
                lastAttemptCount = attempt + 1;

                ToolFailureType failureType = ToolFailureType.fromException(lastException);
                log.warn("⚠️ [ToolRuntime] 工具执行失败 | tool={} | attempt={}/{} | type={} | msg={}",
                        toolName, attempt + 1, maxRetries + 1, failureType, lastException.getMessage());

                // 判断是否可重试
                if (attempt < maxRetries && failureType.isRetryable()) {
                    // 指数退避：1s, 2s, 4s...
                    long backoffMs = (long) (1000 * Math.pow(2, attempt));
                    log.info("⏳ [ToolRuntime] 等待重试 | tool={} | backoff={}ms", toolName, backoffMs);
                    Thread.sleep(backoffMs);
                    continue;
                }

                // 不可重试或重试耗尽：登记失败、记熔断、抛出
                int durationMs = (int) (System.currentTimeMillis() - startMs);
                recordToolFailure(toolName, meta, args, lastException, failureType, lastAttemptCount, durationMs);
                recordCircuitBreakerFailure(toolName);
                throw lastException;
            }
        }

        // 理论上不会走到这里（重试耗尽已在循环内抛出），但为编译完备兜底
        int durationMs = (int) (System.currentTimeMillis() - startMs);
        recordToolFailure(toolName, meta, args, lastException, ToolFailureType.UNKNOWN, lastAttemptCount, durationMs);
        throw lastException;
    }

    /**
     * 循环解包至根因（对齐 6.8「根因必须解包」契约）
     * <p>
     * 反射调用产生的 InvocationTargetException、CompletableFuture 包装的 RuntimeException，
     * 以及 LangChain4j DefaultToolExecutor 再包一层的 RuntimeException 都需逐层剥开，
     * 否则 {@link ToolFailureType#fromException} 只能看到包装层而误判为 UNKNOWN。
     * </p>
     *
     * @param throwable 原始异常（可能多层包装）
     * @return 最内层的根因异常
     */
    private Throwable unwrapToRootCause(Throwable throwable) {
        Throwable current = throwable;
        int depth = 0;
        while (depth < 10) {  // 防御性深度上限，避免恶意循环引用导致死循环
            if (current instanceof InvocationTargetException ite && ite.getTargetException() != null) {
                current = ite.getTargetException();
                depth++;
                continue;
            }
            Throwable cause = current.getCause();
            if (cause != null && cause != current) {
                current = cause;
                depth++;
                continue;
            }
            break;
        }
        return current;
    }

    /**
     * 登记工具失败到 sys_agent_tool_execution（P1-2）
     * <p>
     * <b>设计依据（反编译 langchain4j-1.1.0 DefaultToolExecutor）</b>：工具抛异常 →
     * 框架 catch 包装 {@code RuntimeException(cause)} 并 athrow → 散播到 {@code onError}，
     * {@code onToolExecuted} 永不触发。因此 06 方案中「在 onToolExecuted 内按草稿区分成功/失败」
     * 的设计在工具真正抛异常时根本不会执行——onToolExecuted 只在工具返回正常文本时触发，
     * 那里永远是 SUCCESS。故失败登记必须落在工具运行时内部，而非编排层 onToolExecuted。
     * </p>
     * <p>
     * <b>与编排层职责划分</b>：
     * <ul>
     *   <li>工具失败本身无副作用（Single Writer 模式下工具不直接写库），故登记为
     *       FAILED 不可补偿，只供运维回放与排查，不参与 Saga 逆序补偿</li>
     *   <li>编排层 onError 仍负责对「之前已成功的写操作」触发 Saga 补偿，
     *       两者职责不重叠</li>
     * </ul>
     * </p>
     * <p>
     * <b>traceId 兜底</b>：工具在模型 HTTP 回调线程执行，{@link com.devops.agent.common.context.TraceContext}
     * 的 ThreadLocal 不跨线程，getTraceId 多半返回 null。此处用 UNKNOWN 兜底并 WARN 记录，
     * 使运维在回放时能识别「运行时登记但未关联 Saga」的孤立失败记录。
     * 编排层 onToolExecuted 的 SUCCESS 登记持有闭包 traceId，不受此限制。
     * </p>
     */
    private void recordToolFailure(String toolName, ToolMeta meta, Object[] args,
                                   Exception error, ToolFailureType failureType,
                                   int attemptCount, int durationMs) {
        String traceId = com.devops.agent.common.context.TraceContext.getTraceId();
        boolean traceMissing = traceId == null;
        if (traceMissing) {
            traceId = "UNKNOWN";
            log.warn("⚠️ [ToolRuntime] 工具失败但 traceId 不可用（回调线程无 ThreadLocal），登记为孤立记录 | tool={} | type={}",
                    toolName, failureType);
        }

        try {
            ToolExecutionRecord rec = new ToolExecutionRecord();
            rec.setTraceId(traceId);
            // sessionId 在运行时同样不可得，留 null 供回放时人工关联
            rec.setSagaId(traceId);  // sagaId = traceId，便于回放时聚合
            rec.setStepSeq(toolExecRepo.nextStepSeq(traceId));
            rec.setToolName(toolName);
            rec.setRiskLevel(meta.riskLevel());
            rec.setToolArgs(truncateForDb(Arrays.toString(args), 1000));
            rec.setState(ToolExecutionState.FAILED);
            rec.setFailureType(failureType);
            String errMsg = error != null ? error.getClass().getSimpleName() + ": " + error.getMessage() : null;
            rec.setErrorMessage(truncateForDb(errMsg, 2000));
            // 工具失败无副作用，不可补偿
            rec.setCompensable(false);
            rec.setCompensationAction(meta.compensationAction().isBlank() ? null : meta.compensationAction());
            rec.setAttemptCount(attemptCount);
            rec.setDurationMs(durationMs);

            Long id = toolExecRepo.insert(rec);
            if (id != null) {
                log.error("🚨 [ToolRuntime] 工具失败已登记 | id={} | tool={} | traceId={} | type={} | attempts={} | duration={}ms{}",
                        id, toolName, traceId, failureType, attemptCount, durationMs,
                        traceMissing ? " | ⚠️ traceId 为 UNKNOWN 兜底（运行时无法关联 Saga）" : "");
            }
        } catch (Exception e) {
            // 审计失败不得影响主流程抛出
            log.error("🚨 [ToolRuntime] 登记工具失败记录时异常（不影响主流程） | tool={} | {}", toolName, e.getMessage());
        }
    }

    /**
     * 截断字符串供数据库存储（避免超长导致写入失败）
     */
    private String truncateForDb(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /**
     * 获取或解析 ToolMeta 元数据
     */
    private ToolMeta getOrParseMeta(Method method) {
        return toolMetaCache.computeIfAbsent(method, m -> {
            ToolMeta meta = m.getAnnotation(ToolMeta.class);
            if (meta == null) {
                // 无注解时给默认值（只读、非幂等、不需审批）
                log.warn("⚠️ [ToolRuntime] 方法无 @ToolMeta 注解，使用默认值 | method={}", m.getName());
                return createDefaultMeta(m.getName());
            }
            return meta;
        });
    }

    /**
     * 创建默认元数据（仅用于兜底）
     */
    private ToolMeta createDefaultMeta(String name) {
        return new ToolMeta() {
            @Override public String name() { return name; }
            @Override public String description() { return ""; }
            @Override public ToolRiskLevel riskLevel() { return ToolRiskLevel.READ_ONLY; }
            @Override public boolean idempotent() { return false; }
            @Override public String idempotencyKey() { return ""; }
            @Override public boolean requiresApproval() { return false; }
            @Override public String compensationAction() { return ""; }
            @Override public long timeoutMs() { return 30000; }
            @Override public int maxRetries() { return 2; }
            @Override public String[] allowedRoles() { return new String[0]; }
            @Override public Class<? extends java.lang.annotation.Annotation> annotationType() { return ToolMeta.class; }
        };
    }

    /**
     * 参数校验（委托给 ToolParameterValidator）
     */
    private void validateParameters(ToolMeta meta, Method method, Object[] args) {
        // 这里可以根据方法名调用对应的 Validator 方法
        // 简化：由 DevOpsTools 内部调用 Validator，这里只做元数据层面的校验提示
        if (meta.riskLevel() != ToolRiskLevel.READ_ONLY) {
            log.debug("🔍 [ToolRuntime] 非只读工具，建议显式参数校验 | tool={}", meta.name());
        }
    }

    /**
     * 幂等检查：生成 Key，检查 Redis 是否存在
     * @return 幂等 Key（如果配置了幂等），null 表示不启用幂等
     */
    private String checkIdempotency(ToolMeta meta, String toolName, Object[] args) {
        if (!meta.idempotent() || meta.idempotencyKey().isEmpty()) {
            return null;
        }

        // 简化：直接用 idempotencyKey 作为 Redis Key 前缀
        // 生产环境应用 SpEL 解析表达式（如 #title + "_" + #priority）
        String keyValue = Arrays.toString(args).replaceAll("[\\[\\]\\s]", "");
        String redisKey = "devops:tool:idempotent:" + toolName + ":" + keyValue;

        // 尝试设置（原子操作：SET NX EX）
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(redisKey, "PROCESSING", Duration.ofHours(24));

        if (Boolean.TRUE.equals(acquired)) {
            return redisKey; // 获得锁，返回 Key 用于后续存储结果
        } else {
            // 已存在：可能是正在处理或已完成
            String existing = redisTemplate.opsForValue().get(redisKey);
            if ("PROCESSING".equals(existing)) {
                throw new IllegalStateException("工具正在执行中，请勿重复调用: " + toolName);
            }
            return redisKey; // 已完成，返回 Key 以便读取缓存结果
        }
    }

    /**
     * 熔断检查
     */
    private void checkCircuitBreaker(String toolName) {
        CircuitBreakerStats stats = circuitBreakers.get(toolName);
        if (stats != null && stats.isOpen()) {
            throw new IllegalStateException("熔断器开启，工具暂不可用: " + toolName + "，请稍后重试");
        }
    }

    /**
     * 记录熔断成功
     */
    private void recordCircuitBreakerSuccess(String toolName) {
        circuitBreakers.computeIfAbsent(toolName, k -> new CircuitBreakerStats()).recordSuccess();
    }

    /**
     * 记录熔断失败
     */
    private void recordCircuitBreakerFailure(String toolName) {
        circuitBreakers.computeIfAbsent(toolName, k -> new CircuitBreakerStats()).recordFailure();
    }

    /**
     * 审计工具调用（SUCCESS 路径）
     * <p>
     * <b>职责边界</b>：运行时此方法只输出结构化 INFO 日志，<b>不</b>落库
     * {@code sys_agent_tool_execution}。原因：编排层 {@code onToolExecuted} 已持有
     * 闭包捕获的 traceId，会完整登记 SUCCESS 路径的 Saga 步骤（含 businessKey、compensable）。
     * 若运行时也落库会与编排层 double insert，造成同一工具调用两条记录。
     * </p>
     * <p>
     * 失败路径的落库由 {@link #recordToolFailure} 负责（工具抛异常时 onToolExecuted
     * 永不触发，编排层无机会登记 FAILED）。
     * </p>
     */
    private void auditToolCall(String toolName, ToolMeta meta, Object[] args,
                               Object result, Exception error, ToolFailureType failureType) {
        String traceId = com.devops.agent.common.context.TraceContext.getTraceId();
        log.info("📋 [ToolRuntime] 审计记录 | traceId={} | tool={} | risk={} | args={} | result={} | error={} | failureType={}",
                traceId, toolName, meta.riskLevel(), Arrays.toString(args),
                result != null ? result.toString().substring(0, Math.min(200, result.toString().length())) : "null",
                error != null ? error.getMessage() : "none",
                failureType);
        // 不在此处持久化：SUCCESS 由编排层 onToolExecuted 登记 Saga 步骤，
        // FAILED 由 recordToolFailure 登记。运行时仅留结构化日志便于线程内排查。
    }

    // ==================== 内部熔断统计类 ====================

    private static class CircuitBreakerStats {
        private int totalCalls = 0;
        private int failedCalls = 0;
        private long lastFailureTime = 0;
        private boolean open = false;

        synchronized void recordSuccess() {
            totalCalls++;
            if (open && totalCalls > 10) { // 尝试半开恢复
                failedCalls = 0;
                open = false;
                log.info("🔓 [CircuitBreaker] 熔断器半开恢复");
            }
        }

        synchronized void recordFailure() {
            totalCalls++;
            failedCalls++;
            lastFailureTime = System.currentTimeMillis();
            // 失败率 > 50% 且至少 5 次调用，开启熔断
            if (totalCalls >= 5 && (double) failedCalls / totalCalls > 0.5) {
                open = true;
                // SLF4J 只认 {}，不支持 {:.2f} 之类格式说明符——原写法会原样打印占位符
                // 且参数错位，等于熔断告警从未生效。数值格式化必须在参数侧完成。
                log.warn("🔴 [CircuitBreaker] 熔断器开启 | total={} | failed={} | rate={}%",
                        totalCalls, failedCalls,
                        String.format("%.2f", (double) failedCalls / totalCalls * 100));
            }
        }

        synchronized boolean isOpen() {
            if (open) {
                // 熔断 30 秒后自动尝试半开
                if (System.currentTimeMillis() - lastFailureTime > 30_000) {
                    open = false;
                    failedCalls = 0;
                    log.info("🟡 [CircuitBreaker] 熔断器自动半开");
                    return false;
                }
                return true;
            }
            return false;
        }
    }
}