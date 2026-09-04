package com.devops.agent.application.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AgentSessionStore} <b>可插拔契约</b>测试。
 *
 * <h3>这个类守的不是某个实现，而是「换存储不会出事」</h3>
 * 会话状态存储被设计成可插拔：单实例用内存实现，多实例需换成 Redis
 * ——因为 {@code synchronized} 是进程内锁，跨实例完全失效。
 *
 * <p>但「可插拔」只有在<b>所有实现都遵守同一组约定</b>时才成立。
 * 这里最要命的一条是 {@link AgentSessionStore#inLock}：
 * 它保护的是「校验当前态 → 写入新态」这个原子段，而状态机里有一条
 * {@code WAITING_APPROVAL} 边是高风险工单的<b>审批闸门</b>。
 * 互斥一旦失效，两个并发迁移各自校验通过、后写覆盖先写，
 * 「需人工审批」会被冲成「草稿就绪」——审批被绕过，且没有任何报错。</p>
 *
 * <h3>为什么用遍历式而不是逐实现写一遍</h3>
 * 当前只有内存实现。若逐个写，<b>Redis 实现加进来时没人会想起补测试</b>——
 * 而那恰恰是契约最容易被破坏的时刻（新实现刚接上，
 * 大家关心的是「能存能取吗」，不会有人去验「并发迁移会不会丢更新」）。
 *
 * @author OpsBrain AI
 * @since 2026-08-27
 */
@DisplayName("AgentSessionStore 可插拔契约（所有实现共同遵守）")
class AgentSessionStoreContractTest {

    /**
     * 待检查的实现清单。
     *
     * <p>新增实现时在此登记一行。刻意不做 classpath 扫描：
     * 扫描会把测试里的桩实现也卷进来，且失败信息里看不出是谁。
     * 「漏登记」由下面的 {@code allImplementationsRegistered} 兜住。</p>
     */
    private static List<Supplier<AgentSessionStore>> implementations() {
        List<Supplier<AgentSessionStore>> list = new ArrayList<>();
        list.add(InMemoryAgentSessionStore::new);
        return list;
    }

    private static String nameOf(AgentSessionStore s) {
        return s.getClass().getSimpleName();
    }

    // ==================================================================
    // 基本存取
    // ==================================================================

    @Test
    @DisplayName("每个实现都有非空的后端标识，且互不重复")
    void backendIdentifiersAreUniqueAndNonBlank() {
        // backend() 会被打进启动日志。多实例部署却用着 memory 实现
        // 是一类典型配置事故，而它在单实例测试时完全看不出来——
        // 日志里的这个标识是最低成本的提示
        List<String> seen = new ArrayList<>();
        for (Supplier<AgentSessionStore> f : implementations()) {
            AgentSessionStore s = f.get();
            assertThat(s.backend()).as(nameOf(s) + " 的 backend() 不能为 null").isNotNull();
            assertThat(s.backend().isBlank()).as(nameOf(s) + " 的 backend() 不能为空白").isFalse();
            assertThat(seen).as("后端标识重复: " + s.backend()).doesNotContain(s.backend());
            seen.add(s.backend());
        }
    }

    @Test
    @DisplayName("getOrCreate 首次创建、再次取到同一个会话")
    void getOrCreateIsIdempotent() {
        for (Supplier<AgentSessionStore> f : implementations()) {
            AgentSessionStore s = f.get();
            var a = s.getOrCreate("t1", "sess-1");
            var b = s.getOrCreate("t1", "sess-1");

            assertThat(a).as(nameOf(s) + "：同一 traceId 不应创建出两个会话").isNotNull();
            assertThat(b.getTraceId()).isEqualTo("t1");
            assertThat(b.getSessionId()).isEqualTo("sess-1");
            assertThat(s.size()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("get 对不存在的会话返回 null，而不是抛异常或造一个空会话")
    void getMissingReturnsNull() {
        // 返回 null 是 transition 判断「会话不存在」的依据。
        // 若这里顺手 create 一个，非法迁移会变成合法——
        // 一个早已结束的 traceId 又能重新走一遍状态机
        for (Supplier<AgentSessionStore> f : implementations()) {
            AgentSessionStore s = f.get();
            assertThat(s.get("nope")).as(nameOf(s)).isNull();
            assertThat(s.size()).as(nameOf(s) + "：查询不得产生副作用").isZero();
        }
    }

    @Test
    @DisplayName("get(null) 安全返回 null，不抛 NPE")
    void getNullIsSafe() {
        for (Supplier<AgentSessionStore> f : implementations()) {
            AgentSessionStore s = f.get();
            assertThatCode(() -> assertThat(s.get(null)).isNull())
                    .as(nameOf(s)).doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("remove 后 size 递减，且再取为 null")
    void removeWorks() {
        for (Supplier<AgentSessionStore> f : implementations()) {
            AgentSessionStore s = f.get();
            s.getOrCreate("t1", "sess-1");
            s.remove("t1");

            assertThat(s.get("t1")).as(nameOf(s)).isNull();
            assertThat(s.size()).as(nameOf(s)).isZero();
        }
    }

    @Test
    @DisplayName("save 可重复调用且不报错——内存实现是空操作，Redis 实现须回写")
    void saveIsSafeToCall() {
        for (Supplier<AgentSessionStore> f : implementations()) {
            AgentSessionStore s = f.get();
            var session = s.getOrCreate("t1", "sess-1");
            assertThatCode(() -> {
                s.save(session);
                s.save(session);
            }).as(nameOf(s)).doesNotThrowAnyException();
        }
    }

    // ==================================================================
    // 互斥（本类最重要的部分）
    // ==================================================================

    @Test
    @DisplayName("inLock 保证同一会话的临界区串行——并发迁移不得丢更新")
    void inLockSerializesSameSession() throws Exception {
        // ── 本类最重要的一条 ──────────────────────────────
        // 用「读-改-写」计数器模拟状态迁移的原子段。
        // 不加互斥时两个线程会读到同一个旧值，各自 +1 后写回，
        // 最终少算一次——对应到状态机上就是「后写覆盖先写」，
        // 审批闸门被绕过
        for (Supplier<AgentSessionStore> f : implementations()) {
            AgentSessionStore s = f.get();
            s.getOrCreate("t1", "sess-1");

            int threads = 8;
            int loops = 200;
            int[] shared = {0};
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);

            try {
                for (int i = 0; i < threads; i++) {
                    pool.submit(() -> {
                        try {
                            start.await(2, TimeUnit.SECONDS);
                            for (int j = 0; j < loops; j++) {
                                s.inLock("t1", () -> {
                                    int v = shared[0];
                                    // 让读写之间留出窗口，无锁时必然交错
                                    Thread.yield();
                                    shared[0] = v + 1;
                                    return null;
                                });
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            done.countDown();
                        }
                    });
                }
                start.countDown();
                assertTrue(done.await(20, TimeUnit.SECONDS), nameOf(s) + "：并发任务未在预期时间内完成");

                assertThat(shared[0])
                        .as(nameOf(s) + "：inLock 必须让同一会话的临界区串行，"
                                + "否则并发状态迁移会丢更新——审批闸门可能被绕过")
                        .isEqualTo(threads * loops);
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Test
    @DisplayName("inLock 原样透出返回值")
    void inLockReturnsValue() {
        for (Supplier<AgentSessionStore> f : implementations()) {
            AgentSessionStore s = f.get();
            s.getOrCreate("t1", "sess-1");
            assertThat(s.<String>inLock("t1", () -> "ok")).as(nameOf(s)).isEqualTo("ok");
        }
    }

    @Test
    @DisplayName("inLock 内抛出的运行时异常原样向外传播，不被吞掉")
    void inLockPropagatesRuntimeException() {
        // 吞掉会让迁移「看起来成功了」——调用方拿到 null 当成非法迁移处理，
        // 而真实原因（比如空指针）被埋掉，排查时无从下手
        for (Supplier<AgentSessionStore> f : implementations()) {
            AgentSessionStore s = f.get();
            s.getOrCreate("t1", "sess-1");
            assertThatThrownBy(() -> s.inLock("t1", () -> {
                throw new IllegalArgumentException("boom");
            }))
                    .as(nameOf(s))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("boom");
        }
    }

    @Test
    @DisplayName("会话不存在时 inLock 仍执行动作——由调用方处理「会话不存在」")
    void inLockOnMissingSessionStillRuns() {
        // transition 自己会检查会话是否存在并返回 null。
        // 在这里抢先抛异常，会把一个正常的边界情况变成故障
        for (Supplier<AgentSessionStore> f : implementations()) {
            AgentSessionStore s = f.get();
            AtomicInteger ran = new AtomicInteger();
            s.inLock("missing", ran::incrementAndGet);
            assertThat(ran.get()).as(nameOf(s)).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("不同会话之间不互相阻塞——锁粒度必须是单个会话")
    void differentSessionsDoNotBlockEachOther() throws Exception {
        // 锁全局会让所有并发会话的状态迁移排队。
        // 高并发下这会成为吞吐瓶颈，且症状是「AI 响应整体变慢」，
        // 很难联想到是状态机的锁粒度问题
        for (Supplier<AgentSessionStore> f : implementations()) {
            AgentSessionStore s = f.get();
            s.getOrCreate("t1", "sess-1");
            s.getOrCreate("t2", "sess-2");

            CountDownLatch t1Entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);

            try {
                pool.submit(() -> s.inLock("t1", () -> {
                    t1Entered.countDown();
                    release.await(3, TimeUnit.SECONDS);
                    return null;
                }));

                assertTrue(t1Entered.await(2, TimeUnit.SECONDS), "t1 未进入临界区");

                // t1 仍持锁时，t2 必须能立刻进入
                var t2 = pool.submit(() -> s.inLock("t2", () -> "t2-done"));
                assertThat(t2.get(2, TimeUnit.SECONDS))
                        .as(nameOf(s) + "：不同会话不得互相阻塞")
                        .isEqualTo("t2-done");
            } finally {
                release.countDown();
                pool.shutdownNow();
            }
        }
    }

    // ==================================================================
    // 清单完整性
    // ==================================================================

    @Test
    @DisplayName("实现清单完整——新增存储实现必须登记进本测试")
    void allImplementationsRegistered() {
        List<String> registered = implementations().stream()
                .map(f -> nameOf(f.get())).toList();
        List<String> expected = List.of("InMemoryAgentSessionStore");

        assertThat(registered)
                .as("实现数量与登记不符，请同步更新 implementations() 与 expected")
                .hasSameSizeAs(expected)
                .containsAll(expected);
    }
}
