package com.devops.agent.infrastructure.concurrent;

import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 统一的线程池工厂（F4）。
 *
 * <h3>解决什么问题</h3>
 * 项目里有 6 处各自 {@code Executors.newXxx()} 自建线程池，存在三个共性问题：
 *
 * <ol>
 *   <li><b>无界队列</b>——{@code Executors.newFixedThreadPool} 内部用的是
 *       无参 {@code LinkedBlockingQueue}，容量是 {@code Integer.MAX_VALUE}。
 *       任务生产快于消费时会无限堆积直到 OOM，而且<b>堆积期间毫无征兆</b>：
 *       线程池指标看起来一切正常，直到内存耗尽。这是 Java 线程池最经典的坑，
 *       《阿里巴巴 Java 开发手册》明令禁止用 {@code Executors} 创建线程池
 *       正是这个原因。</li>
 *   <li><b>线程名不可读</b>——默认名是 {@code pool-3-thread-1}，
 *       线上 jstack 时根本分不清哪个池属于哪个业务。</li>
 *   <li><b>无优雅停机</b>——4 个池没有 {@code @PreDestroy}。重新部署时
 *       队列里待写的审计、待发的通知<b>被静默丢弃</b>。</li>
 * </ol>
 *
 * <h3>拒绝策略的选择依据</h3>
 * 队列满时怎么办，取决于「丢掉这个任务的代价」，不能一刀切：
 * <ul>
 *   <li>{@link #forCriticalWrites} 用 <b>CallerRuns</b>——退化为同步执行，
 *       形成天然背压。适用于审计这类<b>丢了就是证据缺失</b>的场景，
 *       宁可让请求慢一点。</li>
 *   <li>{@link #forBestEffort} 用 <b>Discard + 告警</b>——直接丢弃并记日志。
 *       适用于缓存预热、通知推送这类<b>丢了只影响体验</b>的场景；
 *       这里若用 CallerRuns 反而会让主链路被非关键任务拖慢。</li>
 * </ul>
 * 这个取舍与项目里限流/缓存的 fail-open、审计的 fail-safe 是同一套逻辑：
 * <b>按失败代价决定降级方向</b>。
 *
 * @author OpsBrain AI
 * @since 2026-08-24
 */
@Slf4j
public final class ManagedExecutors {

    private ManagedExecutors() {
    }

    /** 带业务名的线程工厂，便于 jstack / 火焰图定位 */
    public static java.util.concurrent.ThreadFactory namedFactory(String name, boolean daemon) {
        AtomicInteger seq = new AtomicInteger(1);
        return r -> {
            Thread t = new Thread(r, name + "-" + seq.getAndIncrement());
            t.setDaemon(daemon);
            // 未捕获异常若不处理，线程会静默死亡，池子逐渐"缩水"却无人知晓
            t.setUncaughtExceptionHandler((thread, ex) ->
                    log.error("💥 [Executor] 线程未捕获异常 | thread={}", thread.getName(), ex));
            return t;
        };
    }

    /**
     * 关键写入池：队列满时<b>由调用线程执行</b>（背压），绝不丢任务。
     * <p>用于审计等「丢失即证据缺失」的场景。</p>
     */
    public static ExecutorService forCriticalWrites(String name, int threads, int queueCapacity) {
        return build(name, threads, queueCapacity, new ThreadPoolExecutor.CallerRunsPolicy());
    }

    /**
     * 尽力而为池：队列满时<b>丢弃并告警</b>。
     * <p>用于缓存写入、通知推送等「丢了只影响体验」的场景——
     * 这类任务不该拖慢主链路。</p>
     */
    public static ExecutorService forBestEffort(String name, int threads, int queueCapacity) {
        RejectedExecutionHandler discardWithWarn = (r, executor) ->
                log.warn("⚠️ [Executor] 队列已满，丢弃任务 | pool={} | queueSize={} | 若持续出现请调大容量或排查消费瓶颈",
                        name, executor.getQueue().size());
        return build(name, threads, queueCapacity, discardWithWarn);
    }

    /** 单线程调度池（清扫、心跳等周期任务） */
    public static ScheduledExecutorService forScheduling(String name) {
        ScheduledThreadPoolExecutor ex = new ScheduledThreadPoolExecutor(1, namedFactory(name, true));
        // 取消的周期任务若不移除，会一直占着队列
        ex.setRemoveOnCancelPolicy(true);
        return ex;
    }

    private static ExecutorService build(String name, int threads, int queueCapacity,
                                         RejectedExecutionHandler handler) {
        return new ThreadPoolExecutor(
                threads, threads,
                0L, TimeUnit.MILLISECONDS,
                // 有界队列——这是与 Executors.newFixedThreadPool 的关键区别
                new LinkedBlockingQueue<>(queueCapacity),
                namedFactory(name, true),
                handler);
    }

    /**
     * 优雅停机：先停止收新任务，给在途任务留出时间，超时才强制中断。
     *
     * <p>不这样做的话，重新部署时队列里待写的审计、待发的通知会被静默丢弃。
     * 两段式等待是必要的：{@code shutdownNow()} 只是发中断信号，
     * 仍需再等一轮确认线程真的退出了。</p>
     *
     * @param timeoutSeconds 每阶段最长等待秒数
     */
    public static void shutdownGracefully(ExecutorService executor, String name, int timeoutSeconds) {
        if (executor == null) {
            return;
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(timeoutSeconds, TimeUnit.SECONDS)) {
                List<Runnable> dropped = executor.shutdownNow();
                log.warn("⚠️ [Executor] {} 优雅停机超时，强制中断 | 丢弃任务数={}", name, dropped.size());
                if (!executor.awaitTermination(timeoutSeconds, TimeUnit.SECONDS)) {
                    log.error("❌ [Executor] {} 强制中断后仍未终止", name);
                }
            } else {
                log.info("✅ [Executor] {} 已优雅停机", name);
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            // 必须恢复中断标志：吞掉它会让上层的中断协议失效
            Thread.currentThread().interrupt();
        }
    }
}
