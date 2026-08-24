package com.devops.agent.infrastructure.concurrent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ManagedExecutors} 行为测试。
 *
 * <p>保护的契约：<b>队列必须有界</b>，且拒绝策略与业务语义匹配。
 * 修复前项目里 4 个池用 {@code Executors.newFixedThreadPool}，
 * 内部是容量 {@code Integer.MAX_VALUE} 的队列——任务堆积时会无声涨到 OOM，
 * 期间线程池指标看起来一切正常。这类问题一旦发生极难定位，
 * 只能靠测试在源头锁死。</p>
 */
class ManagedExecutorsTest {

    @Test
    @DisplayName("队列必须有界——这是与 Executors.newFixedThreadPool 的关键区别")
    void queueIsBounded() {
        ExecutorService ex = ManagedExecutors.forCriticalWrites("test-bounded", 1, 10);
        try {
            assertTrue(ex instanceof ThreadPoolExecutor);
            ThreadPoolExecutor tpe = (ThreadPoolExecutor) ex;

            // remainingCapacity 为 Integer.MAX_VALUE 即说明是无界队列
            assertEquals(10, tpe.getQueue().remainingCapacity(),
                    "队列容量必须是显式指定值，无界队列会在堆积时静默 OOM");
        } finally {
            ex.shutdownNow();
        }
    }

    @Test
    @DisplayName("关键写入池：队列满时由调用线程执行，绝不丢任务")
    void criticalWritesNeverDropTasks() throws Exception {
        // 1 线程 + 容量 1：极易触发拒绝，便于确定性验证
        ExecutorService ex = ManagedExecutors.forCriticalWrites("test-critical", 1, 1);
        CountDownLatch block = new CountDownLatch(1);
        AtomicInteger done = new AtomicInteger();

        try {
            // 占住唯一的工作线程
            ex.execute(() -> {
                try {
                    block.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                done.incrementAndGet();
            });

            // 这些必然触发拒绝策略；CallerRuns 会在当前线程同步跑完
            for (int i = 0; i < 5; i++) {
                ex.execute(done::incrementAndGet);
            }

            block.countDown();
            ex.shutdown();
            assertTrue(ex.awaitTermination(5, TimeUnit.SECONDS));

            assertEquals(6, done.get(),
                    "审计类任务丢失即证据缺失，必须全部执行（超出部分同步跑）");
        } finally {
            ex.shutdownNow();
        }
    }

    @Test
    @DisplayName("尽力而为池：队列满时丢弃，不阻塞调用方")
    void bestEffortDropsInsteadOfBlocking() throws Exception {
        ExecutorService ex = ManagedExecutors.forBestEffort("test-besteffort", 1, 1);
        CountDownLatch block = new CountDownLatch(1);
        AtomicInteger done = new AtomicInteger();

        try {
            ex.execute(() -> {
                try {
                    block.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                done.incrementAndGet();
            });

            long start = System.currentTimeMillis();
            for (int i = 0; i < 20; i++) {
                ex.execute(done::incrementAndGet);
            }
            long elapsed = System.currentTimeMillis() - start;

            // 关键断言：提交没有被阻塞。若误用 CallerRuns，
            // 这里会同步等待被 block 的任务，耗时显著上升
            assertTrue(elapsed < 1000,
                    "缓存/通知类任务不该拖慢主链路，提交必须立即返回（实际 " + elapsed + "ms）");

            block.countDown();
            ex.shutdown();
            ex.awaitTermination(5, TimeUnit.SECONDS);

            assertTrue(done.get() < 21, "队列满时应有任务被丢弃，而非全部执行");
        } finally {
            ex.shutdownNow();
        }
    }

    @Test
    @DisplayName("线程名带业务前缀，便于 jstack 定位")
    void threadsAreNamed() throws Exception {
        ExecutorService ex = ManagedExecutors.forCriticalWrites("my-biz-pool", 1, 5);
        try {
            java.util.concurrent.CompletableFuture<String> name = new java.util.concurrent.CompletableFuture<>();
            ex.execute(() -> name.complete(Thread.currentThread().getName()));

            assertTrue(name.get(3, TimeUnit.SECONDS).startsWith("my-biz-pool-"),
                    "默认的 pool-3-thread-1 在线上根本分不清属于哪个业务");
        } finally {
            ex.shutdownNow();
        }
    }

    @Test
    @DisplayName("优雅停机会等待在途任务完成")
    void gracefulShutdownWaitsForInflight() throws Exception {
        ExecutorService ex = ManagedExecutors.forCriticalWrites("test-shutdown", 1, 10);
        AtomicInteger done = new AtomicInteger();

        ex.execute(() -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            done.incrementAndGet();
        });

        ManagedExecutors.shutdownGracefully(ex, "test-shutdown", 5);

        assertEquals(1, done.get(), "重新部署时在途的审计/通知不应被静默丢弃");
        assertTrue(ex.isTerminated());
    }

    @Test
    @DisplayName("调度池取消任务后不残留（RemoveOnCancel）")
    void schedulerRemovesCancelledTasks() {
        ScheduledExecutorService ex = ManagedExecutors.forScheduling("test-sched");
        try {
            var future = ex.scheduleWithFixedDelay(() -> { }, 1, 1, TimeUnit.HOURS);
            future.cancel(false);

            assertEquals(0, ((ThreadPoolExecutor) ex).getQueue().size(),
                    "取消的周期任务若不移除会一直占着队列");
        } finally {
            ex.shutdownNow();
        }
    }

    @Test
    @DisplayName("对 null 执行器停机不抛异常")
    void shutdownNullIsSafe() {
        ManagedExecutors.shutdownGracefully(null, "none", 1);
    }
}
