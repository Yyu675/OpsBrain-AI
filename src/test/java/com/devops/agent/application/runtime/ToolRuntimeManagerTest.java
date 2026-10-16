package com.devops.agent.application.runtime;

import com.devops.agent.domain.tools.ToolFailureType;
import com.devops.agent.domain.tools.ToolMeta;
import com.devops.agent.domain.tools.ToolParameterValidator;
import com.devops.agent.domain.tools.ToolRiskLevel;
import com.devops.agent.infrastructure.persistence.repo.ToolExecutionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ToolRuntimeManager} 单元测试。
 *
 * <h3>为什么它是 Service 层里最该补的一个</h3>
 * 它是 AI 调用任何工具的<b>唯一入口</b>，一个方法里串了五道治理：
 * 幂等 → 熔断 → 超时 → 重试 → 失败登记。这些逻辑的共同特点是
 * <b>正常路径下完全看不出来</b>——只有在下游抖动、超时、重复调用时才生效，
 * 而那正是没人盯着的时候。
 *
 * <h3>三条最要紧的不变式</h3>
 * <ol>
 *   <li><b>参数错误绝不重试</b>。{@code IllegalArgumentException} →
 *       {@code PARAMETER_ERROR}（{@code retryable=false}）。
 *       重试一个参数写错的调用毫无意义，而如果这个工具有副作用，
 *       重试三次就是把同一个错误动作做了三遍；</li>
 *   <li><b>异常必须循环解包到根因</b>。反射抛 {@code InvocationTargetException}，
 *       {@code CompletableFuture} 再包一层 {@code RuntimeException}。
 *       只解一层的话 {@code fromException} 只能看到包装层、判成 UNKNOWN 不可重试——
 *       <b>结果是超时和连接失败这些本该重试的异常永远不重试</b>。
 *       这是 6.8「根因必须解包」契约，此前真实踩过；</li>
 *   <li><b>幂等命中必须短路</b>。命中后直接返回缓存结果，
 *       不能再执行一次工具——对有副作用的工具，那就是重复建单。</li>
 * </ol>
 *
 * <h3>测试手法：不用 Spring 上下文</h3>
 * 本类是 {@code @Autowired} 字段注入（没有构造器），
 * 因此用 {@link ReflectionTestUtils} 直接塞 mock。
 * 好处是测试跑得快且不依赖数据库；代价是必须自己保证塞的字段名与实现一致——
 * 少塞一个会在运行时 NPE，而不是编译期报错。
 */
@DisplayName("工具运行时治理（幂等 / 熔断 / 超时 / 重试）")
class ToolRuntimeManagerTest {

    private ToolRuntimeManager manager;
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private ToolExecutionRepository toolExecRepo;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        manager = new ToolRuntimeManager();
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        toolExecRepo = mock(ToolExecutionRepository.class);
        when(toolExecRepo.nextStepSeq(anyString())).thenReturn(1);

        ReflectionTestUtils.setField(manager, "redisTemplate", redisTemplate);
        ReflectionTestUtils.setField(manager, "parameterValidator",
                mock(ToolParameterValidator.class));
        ReflectionTestUtils.setField(manager, "stateManager", mock(AgentStateManager.class));
        ReflectionTestUtils.setField(manager, "toolExecRepo", toolExecRepo);
    }

    // ==================== 被测工具桩 ====================

    /**
     * 用真实带 {@code @ToolMeta} 注解的方法做被测目标。
     * 不用 mock ToolMeta：注解值是 {@code executeTool} 全部治理决策的输入，
     * 手工造一个 mock 很容易与真实注解的默认值脱节。
     */
    static class StubTool {
        final AtomicInteger calls = new AtomicInteger();
        RuntimeException toThrow;
        long sleepMs;

        @ToolMeta(name = "stub.readonly", description = "只读桩",
                riskLevel = ToolRiskLevel.READ_ONLY, maxRetries = 2, timeoutMs = 30000)
        public String readOnly(String arg) {
            calls.incrementAndGet();
            if (sleepMs > 0) {
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (toThrow != null) throw toThrow;
            return "ok:" + arg;
        }

        @ToolMeta(name = "stub.idem", description = "幂等桩",
                riskLevel = ToolRiskLevel.CONTROLLED_WRITE,
                idempotent = true, idempotencyKey = "#arg",
                maxRetries = 0, timeoutMs = 30000)
        public String idempotentWrite(String arg) {
            calls.incrementAndGet();
            return "created:" + arg;
        }

        @ToolMeta(name = "stub.slow", description = "超时桩",
                riskLevel = ToolRiskLevel.READ_ONLY, maxRetries = 0, timeoutMs = 50)
        public String slow(String arg) {
            calls.incrementAndGet();
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "never";
        }

        @ToolMeta(name = "stub.noretry", description = "不重试桩",
                riskLevel = ToolRiskLevel.READ_ONLY, maxRetries = 3, timeoutMs = 30000)
        public String noRetry(String arg) {
            calls.incrementAndGet();
            throw new IllegalArgumentException("参数 arg 非法");
        }
    }

    private static Method m(String name) throws Exception {
        return StubTool.class.getMethod(name, String.class);
    }

    private Object run(String toolName, StubTool tool, String method, String arg) throws Exception {
        return manager.executeTool(toolName, tool, m(method), new Object[]{arg});
    }

    // ==================================================================

    @Nested
    @DisplayName("正常执行与元数据")
    class Basic {

        @Test
        @DisplayName("只读工具正常返回，且不写幂等缓存")
        void readOnlyExecutes() throws Exception {
            StubTool tool = new StubTool();

            Object result = run("stub.readonly", tool, "readOnly", "x");

            assertThat(result).isEqualTo("ok:x");
            assertThat(tool.calls.get()).isEqualTo(1);
            // 未声明 idempotent 的工具不该占用 Redis
            verify(valueOps, never()).set(anyString(), anyString(), anyLong(), any());
        }

        @Test
        @DisplayName("lookupMeta 能按工具名反查真实注解")
        void lookupMetaFindsRealAnnotation() {
            // 查项目里真实注册的工具（DevOpsTools 上的 @ToolMeta）
            ToolMeta meta = manager.lookupMeta("searchDevOpsKnowledge");

            assertThat(meta).as("应能反查到 DevOpsTools 上的工具元数据").isNotNull();
            assertThat(meta.name()).isEqualTo("searchDevOpsKnowledge");
        }

        @Test
        @DisplayName("lookupMeta 对未注册工具返回 null，不抛异常")
        void lookupMetaUnknownReturnsNull() {
            assertThat(manager.lookupMeta("nope.not.a.tool")).isNull();
        }
    }

    @Nested
    @DisplayName("幂等：命中必须短路")
    class Idempotency {

        @Test
        @DisplayName("首次调用抢到锁并写入结果缓存")
        void firstCallAcquiresAndCaches() throws Exception {
            StubTool tool = new StubTool();
            when(valueOps.setIfAbsent(anyString(), eq("PROCESSING"), any(Duration.class)))
                    .thenReturn(true);
            when(valueOps.get(anyString())).thenReturn(null);

            Object result = run("stub.idem", tool, "idempotentWrite", "TK-1");

            assertThat(result).isEqualTo("created:TK-1");
            assertThat(tool.calls.get()).isEqualTo(1);
            // 结果要落缓存，否则下次重复调用无从命中
            verify(valueOps).set(anyString(), eq("created:TK-1"), eq(24L),
                    eq(java.util.concurrent.TimeUnit.HOURS));
        }

        @Test
        @DisplayName("命中缓存直接返回，绝不再执行工具 —— 否则就是重复建单")
        void cacheHitShortCircuits() throws Exception {
            StubTool tool = new StubTool();
            when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                    .thenReturn(false);   // 已有人占用
            when(valueOps.get(anyString())).thenReturn("created:TK-1");

            Object result = run("stub.idem", tool, "idempotentWrite", "TK-1");

            assertThat(result).isEqualTo("created:TK-1");
            // 这一条是幂等的全部意义：工具体一次都不能被执行
            assertThat(tool.calls.get()).isZero();
        }

        @Test
        @DisplayName("幂等键随参数变化 —— 不同参数不该互相命中")
        void differentArgsUseDifferentKeys() throws Exception {
            StubTool tool = new StubTool();
            when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                    .thenReturn(true);
            when(valueOps.get(anyString())).thenReturn(null);

            run("stub.idem", tool, "idempotentWrite", "TK-1");
            run("stub.idem", tool, "idempotentWrite", "TK-2");

            org.mockito.ArgumentCaptor<String> keys =
                    org.mockito.ArgumentCaptor.forClass(String.class);
            verify(valueOps, org.mockito.Mockito.times(2))
                    .set(keys.capture(), anyString(), anyLong(), any());
            // 键相同的话，建 TK-2 时会命中 TK-1 的缓存，第二张单根本不会被创建
            assertThat(keys.getAllValues().get(0)).isNotEqualTo(keys.getAllValues().get(1));
        }
    }

    @Nested
    @DisplayName("重试：按失败类型分类，参数错误绝不重试")
    class Retry {

        @Test
        @DisplayName("IllegalArgumentException 只执行一次 —— 重试一个参数错误毫无意义")
        void parameterErrorNeverRetries() throws Exception {
            StubTool tool = new StubTool();

            assertThatThrownBy(() -> run("stub.noretry", tool, "noRetry", "bad"))
                    .isInstanceOf(IllegalArgumentException.class);

            // maxRetries=3，但 PARAMETER_ERROR 的 retryable=false。
            // 若这里变成 4 次，且工具有副作用，就是把同一个错误动作做了四遍
            assertThat(tool.calls.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("可重试异常会重试到 maxRetries 次")
        void retryableExceptionRetries() throws Exception {
            StubTool tool = new StubTool();
            // ConnectException 属 SERVICE_UNAVAILABLE，retryable=true
            tool.toThrow = new RuntimeException(new java.net.ConnectException("Connection refused"));

            assertThatThrownBy(() -> run("stub.readonly", tool, "readOnly", "x"))
                    .isInstanceOf(Exception.class);

            // maxRetries=2 → 首次 + 2 次重试 = 3
            assertThat(tool.calls.get()).isEqualTo(3);
        }

        @Test
        @DisplayName("异常循环解包到根因 —— 这是「本该重试的永不重试」那个 bug 的根源")
        void unwrapsToRootCause() throws Exception {
            StubTool tool = new StubTool();
            // 模拟多层包装：反射 InvocationTargetException + CompletableFuture RuntimeException
            tool.toThrow = new RuntimeException(
                    new RuntimeException(
                            new java.net.SocketTimeoutException("Read timed out")));

            assertThatThrownBy(() -> run("stub.readonly", tool, "readOnly", "x"))
                    .isInstanceOf(Exception.class);

            // 只解一层的话，fromException 看到的是外层 RuntimeException → UNKNOWN → 不重试，
            // 调用次数会是 1。解到根因才能识别出 TIMEOUT（retryable）并真正重试
            assertThat(tool.calls.get())
                    .as("必须解包到 SocketTimeoutException 才会重试").isEqualTo(3);
        }

        @Test
        @DisplayName("重试耗尽后把根因抛出，而不是包装层")
        void throwsRootCauseAfterExhaustion() throws Exception {
            StubTool tool = new StubTool();
            tool.toThrow = new RuntimeException(new IllegalStateException("下游拒绝"));

            assertThatThrownBy(() -> run("stub.readonly", tool, "readOnly", "x"))
                    // 抛包装层会让上游的异常处理也判错类型
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("下游拒绝");
        }

        @Test
        @DisplayName("失败会登记到 sys_agent_tool_execution 供运维回放")
        void failureIsRecorded() throws Exception {
            StubTool tool = new StubTool();
            tool.toThrow = new RuntimeException(new IllegalStateException("boom"));

            assertThatThrownBy(() -> run("stub.readonly", tool, "readOnly", "x"))
                    .isInstanceOf(Exception.class);

            // 不登记的话，工具失败在库里没有任何痕迹——
            // 运维只能从日志里捞，而日志是会滚动清理的
            verify(toolExecRepo).insert(any());
        }

        @Test
        @DisplayName("登记失败本身不能覆盖原始异常 —— 否则真正的错因就丢了")
        void recordFailureDoesNotMaskOriginalError() throws Exception {
            StubTool tool = new StubTool();
            tool.toThrow = new RuntimeException(new IllegalStateException("真正的错因"));
            when(toolExecRepo.insert(any())).thenThrow(new RuntimeException("登记也挂了"));

            assertThatThrownBy(() -> run("stub.readonly", tool, "readOnly", "x"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("真正的错因");
        }
    }

    @Nested
    @DisplayName("超时")
    class Timeout {

        @Test
        @DisplayName("超过 timeoutMs 抛出，不会无限等待")
        void slowToolTimesOut() throws Exception {
            StubTool tool = new StubTool();

            long start = System.currentTimeMillis();
            assertThatThrownBy(() -> run("stub.slow", tool, "slow", "x"))
                    .isInstanceOf(Exception.class);
            long cost = System.currentTimeMillis() - start;

            // timeoutMs=50，工具体睡 2000ms。没有超时控制的话这里会等满 2 秒，
            // 而 AI 对话链路上每多等一秒都是用户在盯着空白屏幕
            assertThat(cost).as("应在超时阈值附近返回，而不是等工具跑完").isLessThan(1500);
        }
    }

    @Nested
    @DisplayName("熔断：连续失败后快速失败")
    class CircuitBreaker {

        @Test
        @DisplayName("失败率过半且调用数达阈值后开启熔断，后续调用直接被拒")
        void opensAfterRepeatedFailures() throws Exception {
            StubTool tool = new StubTool();
            tool.toThrow = new RuntimeException(new IllegalStateException("boom"));

            // 触发 5 次失败（阈值：totalCalls>=5 且失败率>50%）
            for (int i = 0; i < 5; i++) {
                try {
                    run("stub.readonly", tool, "readOnly", "x");
                } catch (Exception ignored) {
                    // 预期失败
                }
            }

            int callsBefore = tool.calls.get();

            // 熔断开启后，下一次调用应被快速拒绝，工具体不再执行
            assertThatThrownBy(() -> run("stub.readonly", tool, "readOnly", "x"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("熔断器开启");

            assertThat(tool.calls.get())
                    .as("熔断开启后不该再打到下游").isEqualTo(callsBefore);
        }

        @Test
        @DisplayName("成功调用不会开启熔断")
        void successDoesNotTripBreaker() throws Exception {
            StubTool tool = new StubTool();

            for (int i = 0; i < 8; i++) {
                run("stub.readonly", tool, "readOnly", "x");
            }

            assertThat(tool.calls.get()).isEqualTo(8);
        }

        @Test
        @DisplayName("熔断按工具名隔离 —— 一个工具挂了不该拖垮其他工具")
        void breakerIsPerTool() throws Exception {
            StubTool failing = new StubTool();
            failing.toThrow = new RuntimeException(new IllegalStateException("boom"));
            for (int i = 0; i < 5; i++) {
                try {
                    run("tool.A", failing, "readOnly", "x");
                } catch (Exception ignored) {
                    // 预期失败
                }
            }

            // 另一个工具名不受影响
            StubTool healthy = new StubTool();
            assertThat(run("tool.B", healthy, "readOnly", "y")).isEqualTo("ok:y");
        }
    }
}
