package com.devops.agent.infrastructure.concurrent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 线程池生命周期<b>契约</b>测试。
 *
 * <h3>守的是「新增线程池时不会再漏掉优雅停机」</h3>
 * {@link ManagedExecutors} 的类注释里写着它要解决的三个问题之一是
 * 「4 个池没有 {@code @PreDestroy}，重新部署时队列里待写的审计、
 * 待发的通知被静默丢弃」。
 *
 * <p>但工具类本身管不住调用方——本轮审查就发现
 * {@code OperationAuditInterceptor}、{@code DingTalkNotifier}、
 * {@code DocumentIndexer} 三处<b>要么没迁移到工厂、要么迁了却没配 @PreDestroy</b>。
 * 这类遗漏不会有任何报错：进程照常退出，只是队列里的东西没了。</p>
 *
 * <h3>为什么用源码扫描而不是 Spring 上下文</h3>
 * 起 {@code @SpringBootTest} 拿全部 Bean 再反射找线程池，看似更"真"，
 * 实则更脆：它依赖数据库/Redis 可用，且只能覆盖被容器实例化的类。
 * 而这条契约的本质是<b>一条编码约定</b>——「持有 ExecutorService 字段的类
 * 必须有 @PreDestroy」——用源码扫描表达最直接，也跑得最快。
 *
 * @author OpsBrain AI
 * @since 2026-08-27
 */
@DisplayName("线程池生命周期契约")
class ExecutorLifecycleContractTest {

    private static final Path MAIN = Path.of("src/main/java/com/devops/agent");

    /**
     * 豁免清单：确实持有执行器字段、但无需 @PreDestroy 的类。
     *
     * <p>每条都要写明理由——豁免本身是最容易被滥用的口子，
     * 「加进白名单」不该比「补上 @PreDestroy」更省事。</p>
     */
    private static final List<String> EXEMPT = List.of(
            // 工具类自身：只提供工厂方法，不持有池
            "ManagedExecutors.java",
            // 虚拟线程 per-task 执行器：无队列、无池化线程，
            // 关闭它没有意义（每个任务一条虚拟线程，随任务结束即回收）
            "DevOpsAgentServiceImpl.java"
    );

    private static List<Path> mainSources() throws IOException {
        try (Stream<Path> s = Files.walk(MAIN)) {
            return s.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    @Test
    @DisplayName("持有线程池字段的类必须有 @PreDestroy——否则停机时在途任务静默丢失")
    void everyExecutorHolderHasPreDestroy() throws IOException {
        List<String> offenders = new ArrayList<>();

        for (Path p : mainSources()) {
            String name = p.getFileName().toString();
            if (EXEMPT.contains(name)) {
                continue;
            }
            String src = Files.readString(p, StandardCharsets.UTF_8);

            // 只看"字段声明"形态：局部变量里的执行器随方法结束即可回收
            boolean holdsPool = src.contains("private final ExecutorService")
                    || src.contains("private final ScheduledExecutorService");
            if (!holdsPool) {
                continue;
            }
            if (!src.contains("@PreDestroy")) {
                offenders.add(name);
            }
        }

        assertThat(offenders)
                .as("这些类持有线程池却没有 @PreDestroy：应用停止时队列里的任务会随进程消失，"
                        + "而且没有任何报错。审计丢的是合规证据、通知丢的是故障提醒、"
                        + "索引丢的是「文档永远检索不到」")
                .isEmpty();
    }

    @Test
    @DisplayName("不得再用 Executors.newFixedThreadPool / newSingleThreadExecutor —— 它们是无界队列")
    void noUnboundedQueueFactories() throws IOException {
        // Executors.newFixedThreadPool / newSingleThreadExecutor 内部是
        // 无参 LinkedBlockingQueue，容量 Integer.MAX_VALUE。
        // 生产快于消费时无限堆积直到 OOM，且堆积期间线程池指标一切正常。
        // 调度类（newSingleThreadScheduledExecutor）不在此列——
        // 它的队列存的是周期任务本身，数量由代码写死，不随流量增长。
        List<String> offenders = new ArrayList<>();

        for (Path p : mainSources()) {
            String name = p.getFileName().toString();
            if (EXEMPT.contains(name)) {
                continue;
            }
            String src = Files.readString(p, StandardCharsets.UTF_8);
            // 跳过注释行：多处文档里提到这两个方法名是为了说明"为什么不用"
            boolean used = Stream.of(src.split("\n"))
                    .map(String::trim)
                    .filter(l -> !l.startsWith("*") && !l.startsWith("//") && !l.startsWith("/*"))
                    .anyMatch(l -> l.contains("Executors.newFixedThreadPool")
                            || l.contains("Executors.newSingleThreadExecutor")
                            || l.contains("Executors.newCachedThreadPool"));
            if (used) {
                offenders.add(name);
            }
        }

        assertThat(offenders)
                .as("这些类用了无界队列工厂，应改用 ManagedExecutors.forCriticalWrites / forBestEffort")
                .isEmpty();
    }

    // ==================================================================
    // ManagedExecutors 自身的行为
    // ==================================================================

    @Test
    @DisplayName("forCriticalWrites 队列满时由调用线程执行——绝不丢任务")
    void criticalWritesNeverDropsTask() throws Exception {
        // 审计场景：丢一条就是证据缺失，宁可让请求慢一点。
        // 单线程 + 队列 1，投 5 个任务，全部都要执行到
        ExecutorService ex = ManagedExecutors.forCriticalWrites("t-critical", 1, 1);
        AtomicInteger done = new AtomicInteger();
        CountDownLatch gate = new CountDownLatch(1);

        try {
            for (int i = 0; i < 5; i++) {
                ex.execute(() -> {
                    try {
                        gate.await(2, TimeUnit.SECONDS);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                    done.incrementAndGet();
                });
            }
            gate.countDown();
            ex.shutdown();
            assertTrue(ex.awaitTermination(5, TimeUnit.SECONDS));
            assertThat(done.get()).as("CallerRuns 保证一个都不丢").isEqualTo(5);
        } finally {
            ex.shutdownNow();
        }
    }

    @Test
    @DisplayName("forBestEffort 队列满时丢弃而不是阻塞调用方")
    void bestEffortDropsInsteadOfBlocking() throws Exception {
        // 通知/缓存场景：丢了只影响体验，绝不能拖慢主链路。
        // 与上一条恰好相反——这个差异是刻意的，依据是「丢掉任务的代价」
        ExecutorService ex = ManagedExecutors.forBestEffort("t-besteffort", 1, 1);
        CountDownLatch blocker = new CountDownLatch(1);
        AtomicInteger done = new AtomicInteger();

        try {
            // 占住唯一线程
            ex.execute(() -> {
                try {
                    blocker.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                done.incrementAndGet();
            });

            long start = System.nanoTime();
            for (int i = 0; i < 50; i++) {
                ex.execute(done::incrementAndGet);
            }
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            // 提交 50 个任务应当立刻返回（被丢弃），而不是等前面那个跑完。
            // 若这里退化成 CallerRuns，耗时会接近 blocker 的 2 秒
            assertThat(elapsedMs)
                    .as("best-effort 提交不得阻塞调用方")
                    .isLessThan(1000);

            blocker.countDown();
            ex.shutdown();
            ex.awaitTermination(3, TimeUnit.SECONDS);
            assertThat(done.get())
                    .as("大部分任务应被丢弃，不可能全部执行")
                    .isLessThan(51);
        } finally {
            ex.shutdownNow();
        }
    }

    @Test
    @DisplayName("线程名带业务前缀——jstack 时能分清哪个池属于哪个业务")
    void threadsAreNamed() throws Exception {
        ExecutorService ex = ManagedExecutors.forBestEffort("t-named", 1, 4);
        try {
            List<String> names = new ArrayList<>();
            CountDownLatch latch = new CountDownLatch(1);
            ex.execute(() -> {
                names.add(Thread.currentThread().getName());
                latch.countDown();
            });
            assertTrue(latch.await(2, TimeUnit.SECONDS));
            assertThat(names.get(0)).startsWith("t-named-");
        } finally {
            ex.shutdownNow();
        }
    }

    @Test
    @DisplayName("队列是有界的——这是与 Executors.newFixedThreadPool 的关键区别")
    void queueIsBounded() throws Exception {
        ExecutorService ex = ManagedExecutors.forBestEffort("t-bounded", 1, 7);
        try {
            // 通过反射读队列容量：剩余容量 + 当前大小 = 上限
            ThreadPoolExecutor tpe = (ThreadPoolExecutor) ex;
            int capacity = tpe.getQueue().remainingCapacity() + tpe.getQueue().size();
            assertThat(capacity)
                    .as("无界队列的 remainingCapacity 会是 Integer.MAX_VALUE")
                    .isEqualTo(7);
        } finally {
            ex.shutdownNow();
        }
    }

    @Test
    @DisplayName("shutdownGracefully 对 null 安全——避免初始化失败时二次抛错")
    void shutdownNullIsSafe() {
        org.assertj.core.api.Assertions
                .assertThatCode(() -> ManagedExecutors.shutdownGracefully(null, "t-null", 1))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("shutdownGracefully 会等待在途任务完成，而不是立刻中断")
    void shutdownWaitsForInflight() throws Exception {
        // 这正是「优雅」的含义：审计写到一半被 shutdownNow 打断，
        // 那条记录就永远丢了
        ExecutorService ex = ManagedExecutors.forCriticalWrites("t-wait", 1, 4);
        AtomicInteger done = new AtomicInteger();

        ex.execute(() -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;   // 被中断则不计数
            }
            done.incrementAndGet();
        });

        ManagedExecutors.shutdownGracefully(ex, "t-wait", 3);

        assertThat(done.get())
                .as("在途任务应当跑完，而不是被中断")
                .isEqualTo(1);
        assertThat(ex.isTerminated()).isTrue();
    }
}
