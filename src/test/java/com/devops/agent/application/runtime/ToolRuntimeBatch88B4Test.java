package com.devops.agent.application.runtime;

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
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 批 88-B4 并发修复专项测试（审计阶段补齐）。
 *
 * <h3>覆盖的三个此前零测试的修复</h3>
 * <ol>
 *   <li><b>失败释放幂等锁</b>：工具执行失败后 {@code releaseIdempotencyLock}
 *       用 Lua CAS（值仍为 PROCESSING 才删）释放锁——否则一次失败冻结该工具 24h，
 *       期间同参数调用全部抛「正在执行中」；</li>
 *   <li><b>超时中断后台任务</b>：{@code future.cancel(true)} 必须真中断底层计算——
 *       否则慢工具与退避重试副本并行执行，副作用堆积；</li>
 *   <li><b>Saga 补偿 CAS 抢占</b>：见 {@link SagaBatch88B4CasTest}（独立文件，
 *       因为补偿逻辑挂在 {@code SagaCompensationManager} 上）。</li>
 * </ol>
 *
 * <p>测试手法与 {@link ToolRuntimeManagerTest} 一致：不用 Spring 上下文，
 * {@link ReflectionTestUtils} 塞 mock。</p>
 */
@DisplayName("批88-B4：幂等锁失败释放 / 超时中断")
class ToolRuntimeBatch88B4Test {

    private ToolRuntimeManager manager;
    private StringRedisTemplate redisTemplate;
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOps = mock(ValueOperations.class);
    private ToolExecutionRepository toolExecRepo;

    @BeforeEach
    void setUp() {
        manager = new ToolRuntimeManager();
        redisTemplate = mock(StringRedisTemplate.class);
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

    static class StubTool {
        /** 工具体被调用次数（幂等重跑测试用：失败后重调应再次进入） */
        final java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        RuntimeException toThrow;

        @ToolMeta(name = "b4.idem", description = "幂等写桩",
                riskLevel = ToolRiskLevel.CONTROLLED_WRITE,
                idempotent = true, idempotencyKey = "#arg",
                maxRetries = 0, timeoutMs = 30000)
        public String idempotentWrite(String arg) {
            calls.incrementAndGet();
            if (toThrow != null) throw toThrow;
            return "created:" + arg;
        }

        /** 慢桩：被中断时置位标志——证明 cancel(true) 真的打断了底层线程 */
        final AtomicBoolean interrupted = new AtomicBoolean(false);
        /** 慢桩已开始执行的标志——cancel(true) 只对「运行中」的任务发中断，
         *  任务若还在 commonPool 排队就被取消，工具体从未运行，标志不会置位（非实现缺陷） */
        final AtomicBoolean started = new AtomicBoolean(false);

        /** 只读桩：idempotent 缺省 false——验证非幂等工具失败不触碰释放锁 */
        @ToolMeta(name = "b4.readonly", description = "只读桩",
                riskLevel = ToolRiskLevel.READ_ONLY, maxRetries = 0, timeoutMs = 30000)
        public String readOnly(String arg) {
            calls.incrementAndGet();
            if (toThrow != null) throw toThrow;
            return "ok:" + arg;
        }

        @ToolMeta(name = "b4.slow", description = "超时桩",
                riskLevel = ToolRiskLevel.READ_ONLY, maxRetries = 0, timeoutMs = 80)
        public String slow(String arg) {
            started.set(true);
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                interrupted.set(true);
                Thread.currentThread().interrupt();
            }
            return "never";
        }
    }

    private static Method m(String name) throws Exception {
        return StubTool.class.getMethod(name, String.class);
    }

    // ==================================================================

    @Nested
    @DisplayName("失败释放幂等锁")
    class ReleaseIdempotencyLock {

        @Test
        @DisplayName("工具失败后锁被释放：同参数重调重新执行而非「正在执行中」")
        void failureReleasesLockSoRetryCanRerun() throws Exception {
            StubTool tool = new StubTool();
            tool.toThrow = new RuntimeException("下游抖动");
            String key = "devops:tool:idempotent:b4.idem:TK-1";

            // 首次：抢锁成功（NX）
            when(valueOps.setIfAbsent(eq(key), eq("PROCESSING"), any(Duration.class)))
                    .thenReturn(true, true);
            // 首次执行前缓存无值
            when(valueOps.get(key)).thenReturn(null);
            // 失败释放后：第二次调用的 get 仍返回 null（锁已被 Lua 删除），
            // 于是第二次 setIfAbsent 抢锁成功 → 工具体再次执行
            when(redisTemplate.execute(any(RedisScript.class), any(List.class), any(Object[].class)))
                    .thenReturn(1L);

            // 第一次：失败（抛出）
            assertThatThrownBy(() -> manager.executeTool("b4.idem", tool, m("idempotentWrite"),
                    new Object[]{"TK-1"}))
                    .isInstanceOf(Exception.class);
            assertThat(tool.calls.get()).isEqualTo(1);

            // 验证失败路径走了 Lua CAS 释放锁（脚本调用发生，且值为 PROCESSING 才删）
            verify(redisTemplate).execute(any(RedisScript.class), eq(List.of(key)), eq("PROCESSING"));

            // 第二次：锁已释放 → 重新执行（若锁未释放，这里会抛 IllegalStateException「正在执行中」）
            tool.toThrow = null;
            Object result = manager.executeTool("b4.idem", tool, m("idempotentWrite"),
                    new Object[]{"TK-1"});
            assertThat(result).isEqualTo("created:TK-1");
            assertThat(tool.calls.get()).isEqualTo(2);
        }

        @Test
        @DisplayName("只读工具（无幂等键）失败不触碰释放锁")
        void nonIdempotentFailureSkipsRelease() throws Exception {
            StubTool tool = new StubTool();
            tool.toThrow = new RuntimeException("boom");

            // 用真实只读方法（注解 idempotent 缺省 false）：无幂等键
            assertThatThrownBy(() -> manager.executeTool("b4.readonly", tool, m("readOnly"),
                    new Object[]{"x"}))
                    .isInstanceOf(Exception.class);

            // 无幂等键（idempotent=false 时 checkIdempotency 返回 null），
            // releaseIdempotencyLock 对 null key 直接返回，不触发 Lua
            verify(redisTemplate, never()).execute(any(RedisScript.class), any(List.class), any(Object[].class));
        }

        @Test
        @DisplayName("释放锁失败（Redis 故障）只告警，不掩盖主异常")
        void releaseFailureDoesNotMaskOriginalError() {
            StubTool tool = new StubTool();
            tool.toThrow = new RuntimeException("原始业务异常");
            String key = "devops:tool:idempotent:b4.idem:TK-1";

            when(valueOps.setIfAbsent(eq(key), eq("PROCESSING"), any(Duration.class))).thenReturn(true);
            when(valueOps.get(key)).thenReturn(null);
            // Lua 释放本身抛异常（Redis 故障）
            when(redisTemplate.execute(any(RedisScript.class), any(List.class), any(Object[].class)))
                    .thenThrow(new IllegalStateException("Redis 不可用"));

            // 主异常必须原样抛出——释放失败只 log.warn，不得吞掉/替换
            assertThatThrownBy(() -> manager.executeTool("b4.idem", tool, m("idempotentWrite"),
                    new Object[]{"TK-1"}))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("原始业务异常");
        }
    }

    @Nested
    @DisplayName("超时中断后台任务")
    class TimeoutInterrupt {

        @Test
        @DisplayName("超时后 cancel(true) 真中断底层线程：工具体感知 InterruptedException")
        void timeoutInterruptsUnderlyingTask() throws Exception {
            StubTool tool = new StubTool();

            // 异步驱动：executeTool 阻塞至超时（80ms），无法在调用前同步等「已开始」。
            // 单开线程执行，主线程等 started 置位后再等执行结果——
            // 保证 cancel 发生时任务一定在运行中（排除 commonPool 排队被取消的假阴性）。
            java.util.concurrent.CompletableFuture<Object> exec =
                    java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                        try {
                            return manager.executeTool("b4.slow", tool, m("slow"), new Object[]{"x"});
                        } catch (Exception e) {
                            return e;
                        }
                    });

            // 等工具体真正开始（最多 3s）
            long deadline = System.currentTimeMillis() + 3000;
            while (!tool.started.get() && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }

            // 等执行结束（超时返回异常）
            Object outcome = exec.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(outcome).isInstanceOf(Exception.class);

            // cancel(true) 的中断应已打进 sleep
            for (int i = 0; i < 20 && !tool.interrupted.get(); i++) {
                Thread.sleep(50);
            }
            assertThat(tool.started.get())
                    .as("工具体应已开始执行（否则测的是排队取消而非运行中断）")
                    .isTrue();
            assertThat(tool.interrupted.get())
                    .as("cancel(true) 应中断底层 sleep，工具体应感知 InterruptedException")
                    .isTrue();
        }
    }
}
