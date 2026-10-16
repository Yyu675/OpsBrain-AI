package com.devops.agent.common.context;

import org.slf4j.MDC;

import java.util.Map;
import java.util.UUID;

/**
 * 全链路追踪上下文（A5 修复）。
 *
 * <h3>唯一真相原则</h3>
 * 本类是全项目 traceId 的<b>唯一来源</b>。禁止在任何其他地方自行生成 traceId。
 * <p>
 * 修复前的问题：项目里同时存在<b>三套</b>互不相干的 traceId：
 * <ol>
 *   <li>{@code ApiResponse.generateTraceId()} —— {@code nanoTime()} 取 8 位十六进制，
 *       <b>且每次调用都重新生成</b>。同一请求的成功响应、错误响应、日志三处各不相同，
 *       traceId 字段实际是装饰品，无法用于任何排障；</li>
 *   <li>{@code DevOpsChatController.generateTraceId()} —— 32 位 UUID（P2-27 已修）；</li>
 *   <li>本类的 ThreadLocal —— 只被 Agent 链路少量使用，<b>且从未写入 MDC</b>，
 *       所以日志里根本打不出 traceId。</li>
 * </ol>
 * 现统一为：请求入口 {@code TraceIdFilter} 调用 {@link #begin(String)} 写入 MDC，
 * 全链路（含日志 pattern 的 {@code %X{traceId}}）从 MDC 读取。
 *
 * <h3>为什么用 MDC 而不是自建 ThreadLocal</h3>
 * MDC 本身就是基于 ThreadLocal 的，但它额外被 logback/log4j2 的日志 pattern
 * 原生支持——只有放进 MDC，{@code %X{traceId}} 才能自动打印在每一行日志上。
 * 自建 ThreadLocal 需要在每条日志手工拼接 traceId，既啰嗦又必然遗漏。
 *
 * <h3>⚠️ 跨线程传递</h3>
 * MDC 基于 ThreadLocal，<b>不会自动跨线程传播</b>。本项目有大量切线程的场景：
 * {@code CompletableFuture.runAsync(sessionExecutor)}、SSE 回调、
 * 模型 HTTP 回调线程、各类 {@code @Scheduled} / 自建线程池。
 * 这些地方必须用 {@link #capture()} + {@link #restore(Map)} 显式搬运，
 * 或用 {@link #wrap(Runnable)} 包装任务。漏了不会报错，只是日志里 traceId 变空，
 * 排障时链路断掉——这类问题极难事后发现，所以务必在提交前自查。
 *
 * @author OpsBrain AI
 * @since 2026-07-17
 */
public final class TraceContext {

    /** MDC 键名。与 logback pattern 中的 {@code %X{traceId}} 必须一致。 */
    public static final String TRACE_ID = "traceId";

    /** HTTP 请求/响应头名称。网关或上游若已生成 traceId，透传复用以串联跨服务链路。 */
    public static final String HEADER = "X-Request-Id";

    private TraceContext() {
    }

    /**
     * 开启追踪上下文。
     *
     * @param incoming 上游传入的 traceId（可为 null/空，此时自行生成）
     * @return 最终生效的 traceId
     */
    public static String begin(String incoming) {
        String id = normalize(incoming);
        MDC.put(TRACE_ID, id);
        return id;
    }

    /** 生成一个新的 traceId 并写入上下文 */
    public static String begin() {
        return begin(null);
    }

    /**
     * 获取当前 traceId。
     * <p>可能返回 null —— 例如在未经 Filter 的线程（定时任务、异步回调）中。
     * 调用方需自行兜底，不要直接拼进字符串。</p>
     */
    public static String getTraceId() {
        return MDC.get(TRACE_ID);
    }

    /**
     * 获取当前 traceId，无则生成一个并写入。
     * <p>供定时任务等无 HTTP 入口的场景使用，保证日志始终可关联。</p>
     */
    public static String getOrCreate() {
        String id = MDC.get(TRACE_ID);
        if (id == null || id.isBlank()) {
            return begin(null);
        }
        return id;
    }

    /**
     * 兼容旧调用点：显式设置 traceId。
     * <p>等价于 {@link #begin(String)}，保留此方法避免大范围改动既有代码。</p>
     */
    public static void setTraceId(String traceId) {
        begin(traceId);
    }

    /** 清除上下文。<b>必须在 finally 中调用</b>，否则线程复用会串号。 */
    public static void clear() {
        MDC.remove(TRACE_ID);
    }

    // ==================== 跨线程传递 ====================

    /**
     * 捕获当前线程的 MDC 快照，供异步任务在目标线程 {@link #restore(Map)}。
     *
     * @return MDC 快照，可能为 null
     */
    public static Map<String, String> capture() {
        return MDC.getCopyOfContextMap();
    }

    /** 在目标线程恢复 MDC 快照 */
    public static void restore(Map<String, String> snapshot) {
        if (snapshot == null) {
            MDC.clear();
        } else {
            MDC.setContextMap(snapshot);
        }
    }

    /**
     * 包装 Runnable，使其在目标线程自动携带当前 MDC 并在结束后清理。
     * <p>用法：{@code executor.submit(TraceContext.wrap(() -> ...))}</p>
     * <p>这是跨线程传递 traceId 最不容易出错的方式，优先使用它
     * 而非手工 capture/restore。</p>
     */
    public static Runnable wrap(Runnable task) {
        Map<String, String> snapshot = capture();
        return () -> {
            Map<String, String> previous = capture();
            restore(snapshot);
            try {
                task.run();
            } finally {
                restore(previous);
            }
        };
    }

    // ==================== 内部 ====================

    /**
     * 规范化 traceId。
     * <p>对上游传入值做长度与字符集约束：traceId 会进日志、进响应头、
     * 可能进数据库审计字段，若原样信任外部输入，攻击者可注入换行符
     * <b>伪造日志行</b>（log forging），或用超长字符串撑爆日志与存储。</p>
     */
    private static String normalize(String incoming) {
        if (incoming == null || incoming.isBlank()) {
            return newId();
        }
        String trimmed = incoming.trim();
        if (trimmed.length() > 64) {
            trimmed = trimmed.substring(0, 64);
        }
        // 只允许字母数字与短横线/下划线，其余一律判为不可信，改用自生成 ID
        if (!trimmed.matches("[A-Za-z0-9_-]+")) {
            return newId();
        }
        return trimmed;
    }

    /** 32 位无分隔 UUID。与 DevOpsChatController 既有格式保持一致。 */
    private static String newId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
