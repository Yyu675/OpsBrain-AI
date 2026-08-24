package com.devops.agent.common.context;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TraceContext} 行为测试。
 *
 * <p>保护三个契约：
 * <ol>
 *   <li><b>同一请求内 traceId 稳定</b>——修复前 {@code ApiResponse} 每次调用现算，
 *       导致响应与日志对不上，traceId 形同虚设；</li>
 *   <li><b>写入 MDC</b>——否则日志 pattern 的 {@code %X{traceId}} 打不出来；</li>
 *   <li><b>拒绝不可信的上游输入</b>——traceId 会进日志，含换行的输入可伪造日志行。</li>
 * </ol>
 */
class TraceContextTest {

    @AfterEach
    void tearDown() {
        TraceContext.clear();
    }

    @Test
    @DisplayName("begin 后 traceId 稳定，多次读取返回同一值")
    void traceIdIsStableWithinRequest() {
        String id = TraceContext.begin(null);

        assertNotNull(id);
        assertEquals(id, TraceContext.getTraceId());
        assertEquals(id, TraceContext.getTraceId(), "同一请求内多次读取必须一致");
    }

    @Test
    @DisplayName("begin 会写入 MDC，日志 pattern 才能打印 traceId")
    void writesToMdc() {
        String id = TraceContext.begin(null);

        assertEquals(id, MDC.get(TraceContext.TRACE_ID));
    }

    @Test
    @DisplayName("clear 后 MDC 不残留，避免线程复用串号")
    void clearRemovesFromMdc() {
        TraceContext.begin(null);

        TraceContext.clear();

        assertNull(MDC.get(TraceContext.TRACE_ID));
        assertNull(TraceContext.getTraceId());
    }

    @Test
    @DisplayName("透传上游合法 traceId，用于跨服务链路串联")
    void reusesValidUpstreamId() {
        String id = TraceContext.begin("upstream-Trace_123");

        assertEquals("upstream-Trace_123", id);
    }

    @Test
    @DisplayName("含换行的上游输入被拒绝，防止伪造日志行")
    void rejectsLogForgingInput() {
        String id = TraceContext.begin("abc\r\n2026-01-01 ERROR 伪造的日志行");

        assertNotEquals("abc", id);
        assertTrue(id.matches("[A-Za-z0-9]+"), "应回退为自生成 ID，且不含任何换行");
    }

    @Test
    @DisplayName("超长上游输入被截断到 64 字符，防止撑爆日志与存储")
    void truncatesOverlongInput() {
        String id = TraceContext.begin("A".repeat(200));

        assertEquals(64, id.length());
    }

    @Test
    @DisplayName("空输入自动生成 32 位 ID")
    void generatesIdWhenAbsent() {
        assertEquals(32, TraceContext.begin(null).length());
        TraceContext.clear();
        assertEquals(32, TraceContext.begin("   ").length());
    }

    @Test
    @DisplayName("wrap 把 traceId 带到子线程——这是异步链路不断链的关键")
    void wrapPropagatesToAnotherThread() throws Exception {
        String id = TraceContext.begin(null);
        AtomicReference<String> seen = new AtomicReference<>();
        ExecutorService pool = Executors.newSingleThreadExecutor();

        try {
            pool.submit(TraceContext.wrap(() -> seen.set(TraceContext.getTraceId()))).get();
        } finally {
            pool.shutdown();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }

        assertEquals(id, seen.get(), "子线程必须看到父线程的 traceId");
    }

    @Test
    @DisplayName("未经 wrap 的子线程看不到 traceId（说明 wrap 是必需的，不是可选装饰）")
    void plainRunnableDoesNotPropagate() throws Exception {
        TraceContext.begin(null);
        AtomicReference<String> seen = new AtomicReference<>("sentinel");
        ExecutorService pool = Executors.newSingleThreadExecutor();

        try {
            pool.submit(() -> seen.set(TraceContext.getTraceId())).get();
        } finally {
            pool.shutdown();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }

        assertNull(seen.get(), "裸 Runnable 不传播 MDC —— 所以跨线程必须显式 wrap");
    }

    @Test
    @DisplayName("wrap 执行完会恢复子线程原有上下文，不污染线程池")
    void wrapRestoresPreviousContext() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            // 先让池内线程持有自己的 traceId
            pool.submit(() -> TraceContext.begin("pre-existing")).get();

            TraceContext.begin("outer-id");
            pool.submit(TraceContext.wrap(() -> { /* 借用 outer-id */ })).get();

            AtomicReference<String> after = new AtomicReference<>();
            pool.submit(() -> after.set(TraceContext.getTraceId())).get();

            assertEquals("pre-existing", after.get(), "wrap 结束应还原，避免污染复用线程");
        } finally {
            pool.shutdown();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }
    }
}
