package com.devops.agent.application.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RedisAgentSessionStore} 集成测试（需要真实 Redis）。
 *
 * <h3>为什么必须用真实 Redis 而不是 mock</h3>
 * 这个类的价值全在于<b>跨进程</b>语义：{@code SET NX PX} 的原子性、
 * Lua 脚本的「比对后删除」、TTL 自动过期。
 * mock 掉 {@code StringRedisTemplate} 之后这些全都变成「我说它返回什么它就返回什么」——
 * 测的是我对 Redis 的想象，而不是 Redis 的行为。
 *
 * <p>本项目已有 {@code HybridRetrieverIntegrationTest} 等
 * {@code @SpringBootTest} 用例连真实中间件，CI 里 Redis 与 PostgreSQL 都在跑。</p>
 *
 * <h3>重点验证 mock 测不出来的三件事</h3>
 * <ol>
 *   <li><b>互斥是真的跨实例</b>——用两个<b>独立的 store 实例</b>模拟两个进程，
 *       它们之间没有任何共享内存，只能靠 Redis 协调；</li>
 *   <li><b>锁不会被误删</b>——A 的锁过期后 B 拿到锁，A 收尾时不得删掉 B 的锁；</li>
 *   <li><b>序列化往返不丢字段</b>——尤其 {@code createdAt}，
 *       它决定空闲清理能否触发。</li>
 * </ol>
 *
 * @author OpsBrain AI
 * @since 2026-08-27
 */
@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "devops.ai.mode=MOCK",
        // 显式开启 Redis 会话存储；不开的话容器里注册的是内存实现，
        // 本测试就变成「用 Redis 测内存实现」——什么都验证不到
        "devops.ai.session.store=redis"
})
@DisplayName("RedisAgentSessionStore（真实 Redis）")
class RedisAgentSessionStoreIntegrationTest {

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private ObjectMapper objectMapper;

    /** 两个独立实例：模拟两个进程，它们之间只有 Redis 这一个共享点 */
    private RedisAgentSessionStore nodeA;
    private RedisAgentSessionStore nodeB;

    private String traceId;

    @BeforeEach
    void setUp() {
        nodeA = new RedisAgentSessionStore(redis, objectMapper, Duration.ofMinutes(10));
        nodeB = new RedisAgentSessionStore(redis, objectMapper, Duration.ofMinutes(10));
        // 每个用例独立 traceId：并行执行时不互相干扰，
        // 也避免上一次跑残留的键影响断言
        traceId = "it-" + java.util.UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        nodeA.remove(traceId);
    }

    // ==================================================================
    // 跨实例可见性
    // ==================================================================

    @Nested
    @DisplayName("跨实例状态共享")
    class CrossNode {

        @Test
        @DisplayName("A 创建的会话，B 能读到——这是整个改造的目的")
        void createdOnANodeVisibleOnOther() {
            nodeA.getOrCreate(traceId, "sess-1");

            AgentStateManager.SessionState onB = nodeB.get(traceId);

            assertThat(onB).as("B 读不到说明状态没真正进 Redis").isNotNull();
            assertThat(onB.getTraceId()).isEqualTo(traceId);
            assertThat(onB.getSessionId()).isEqualTo("sess-1");
        }

        @Test
        @DisplayName("A 改了状态并 save，B 读到的是新状态")
        void stateChangeVisibleAcrossNodes() {
            AgentStateManager.SessionState s = nodeA.getOrCreate(traceId, "sess-1");
            s.setCurrentState(AgentState.WAITING_APPROVAL);
            nodeA.save(s);

            assertThat(nodeB.get(traceId).getCurrentState())
                    .as("save 后其它实例必须看到新状态，否则审批闸门会被旧状态覆盖")
                    .isEqualTo(AgentState.WAITING_APPROVAL);
        }

        @Test
        @DisplayName("createdAt 往返不被刷新——它决定空闲清理能否触发")
        void createdAtSurvivesRoundTrip() {
            // 构造器会把 createdAt 置为 now()。若反序列化时不还原，
            // 每次跨实例读取都相当于「刚创建」，而 getIdleSince() 在
            // 从未迁移过的会话上正是回落到 createdAt——
            // 这类会话永远不会被判定空闲，清理线程一个都清不掉
            AgentStateManager.SessionState s = nodeA.getOrCreate(traceId, "sess-1");
            LocalDateTime original = s.getCreatedAt();

            AgentStateManager.SessionState onB = nodeB.get(traceId);

            assertThat(onB.getCreatedAt())
                    .as("createdAt 被刷新会导致会话永不过期，表现为「会话数只涨不跌」")
                    .isEqualTo(original);
        }

        @Test
        @DisplayName("迁移轨迹跨实例可见")
        void transitionsVisibleAcrossNodes() {
            AgentStateManager.SessionState s = nodeA.getOrCreate(traceId, "sess-1");
            s.addTransition(AgentStateTransition.of(traceId, "sess-1",
                    AgentState.NEW, AgentState.CONTEXT_PREPARED,
                    AgentStateTransition.TriggerType.SECURITY_PASSED,
                    "安全检查通过", "SYSTEM", 12L, null));
            nodeA.save(s);

            List<AgentStateTransition> onB = nodeB.get(traceId).snapshotTransitions();

            assertThat(onB).hasSize(1);
            assertThat(onB.get(0).getToState()).isEqualTo(AgentState.CONTEXT_PREPARED);
        }

        @Test
        @DisplayName("迁移轨迹超过上限时只保留最近 N 条，防止单个 key 无界膨胀")
        void transitionsAreCapped() {
            // 不截断的话，长会话的 value 会越来越大，
            // 而每次状态迁移都要完整读写一遍这个 value——
            // 表现为「聊得越久，AI 响应越慢」，且很难联想到是状态存储
            AgentStateManager.SessionState s = nodeA.getOrCreate(traceId, "sess-1");
            int over = RedisAgentSessionStore.MAX_KEPT_TRANSITIONS + 20;
            for (int i = 0; i < over; i++) {
                s.addTransition(AgentStateTransition.of(traceId, "sess-1",
                        AgentState.NEW, AgentState.CONTEXT_PREPARED,
                        AgentStateTransition.TriggerType.SECURITY_PASSED,
                        "第 " + i + " 次", "SYSTEM", 1L, null));
            }
            nodeA.save(s);

            List<AgentStateTransition> onB = nodeB.get(traceId).snapshotTransitions();

            assertThat(onB).hasSize(RedisAgentSessionStore.MAX_KEPT_TRANSITIONS);
            // 保留的必须是**最近**的，不是最早的——
            // 排查问题时关心的是「刚才发生了什么」
            assertThat(onB.get(onB.size() - 1).getTriggerDetail())
                    .isEqualTo("第 " + (over - 1) + " 次");
        }

        @Test
        @DisplayName("remove 后两个实例都读不到")
        void removeIsGlobal() {
            nodeA.getOrCreate(traceId, "sess-1");
            nodeA.remove(traceId);

            assertThat(nodeA.get(traceId)).isNull();
            assertThat(nodeB.get(traceId)).isNull();
        }

        @Test
        @DisplayName("不存在的会话返回 null，且不产生副作用")
        void missingReturnsNull() {
            String ghost = "it-ghost-" + java.util.UUID.randomUUID();
            assertThat(nodeA.get(ghost)).isNull();
            // 查询不得顺手创建：那会让「早已结束的 traceId」重新走一遍状态机
            assertThat(redis.hasKey("devops:agent:session:" + ghost)).isFalse();
        }
    }

    // ==================================================================
    // 分布式互斥
    // ==================================================================

    @Nested
    @DisplayName("分布式锁")
    class DistributedLock {

        @Test
        @DisplayName("两个实例并发时临界区串行——丢更新即审批闸门被绕过")
        void mutualExclusionAcrossNodes() throws Exception {
            // ── 本类最重要的一条 ──────────────────────────────
            // nodeA / nodeB 是两个独立对象，没有共享内存，
            // synchronized 在它们之间完全不起作用——
            // 这正是内存实现在多实例下失效的原因。
            // 用「读-改-写」计数器模拟状态迁移的原子段
            nodeA.getOrCreate(traceId, "sess-1");

            int loops = 60;
            int[] shared = {0};
            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(2);

            try {
                for (RedisAgentSessionStore node : List.of(nodeA, nodeB)) {
                    pool.submit(() -> {
                        try {
                            start.await(3, TimeUnit.SECONDS);
                            for (int i = 0; i < loops; i++) {
                                node.inLock(traceId, () -> {
                                    int v = shared[0];
                                    Thread.yield();     // 放大交错窗口
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
                assertTrue(done.await(60, TimeUnit.SECONDS), "并发任务未在预期时间内完成");

                assertThat(shared[0])
                        .as("跨实例互斥失效会丢更新——对应到状态机就是"
                                + "「需人工审批」被冲成「草稿就绪」，审批被绕过且无报错")
                        .isEqualTo(2 * loops);
            } finally {
                pool.shutdownNow();
            }
        }

        @Test
        @DisplayName("持锁期间其它实例拿不到同一把锁")
        void lockIsHeldExclusively() throws Exception {
            nodeA.getOrCreate(traceId, "sess-1");
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicInteger bEntered = new AtomicInteger();
            ExecutorService pool = Executors.newFixedThreadPool(2);

            try {
                pool.submit(() -> nodeA.inLock(traceId, () -> {
                    entered.countDown();
                    release.await(3, TimeUnit.SECONDS);
                    return null;
                }));
                assertTrue(entered.await(3, TimeUnit.SECONDS), "A 未进入临界区");

                pool.submit(() -> nodeB.inLock(traceId, bEntered::incrementAndGet));
                // A 仍持锁，B 应当在自旋等待而非立刻进入
                Thread.sleep(300);
                assertThat(bEntered.get())
                        .as("A 持锁期间 B 不应进入临界区")
                        .isZero();

                release.countDown();
                // A 释放后 B 应当很快拿到
                for (int i = 0; i < 50 && bEntered.get() == 0; i++) {
                    Thread.sleep(50);
                }
                assertThat(bEntered.get())
                        .as("A 释放锁后 B 必须能拿到，否则是死锁")
                        .isEqualTo(1);
            } finally {
                release.countDown();
                pool.shutdownNow();
            }
        }

        @Test
        @DisplayName("不同会话之间互不阻塞——锁粒度必须是单个会话")
        void differentSessionsDoNotBlock() throws Exception {
            String other = "it-other-" + java.util.UUID.randomUUID();
            nodeA.getOrCreate(traceId, "s1");
            nodeA.getOrCreate(other, "s2");
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);

            try {
                pool.submit(() -> nodeA.inLock(traceId, () -> {
                    entered.countDown();
                    release.await(3, TimeUnit.SECONDS);
                    return null;
                }));
                assertTrue(entered.await(3, TimeUnit.SECONDS));

                // 锁全局会让所有并发会话排队，症状是「AI 响应整体变慢」，
                // 很难联想到是状态机的锁粒度
                var f = pool.submit(() -> nodeB.inLock(other, () -> "done"));
                assertThat(f.get(3, TimeUnit.SECONDS)).isEqualTo("done");
            } finally {
                release.countDown();
                pool.shutdownNow();
                nodeA.remove(other);
            }
        }

        @Test
        @DisplayName("锁超时后被他人接管，原持有者收尾不得删掉接管者的锁")
        void doesNotDeleteLockTakenOverByOthers() {
            // ── 这条第一版写错了，记下来 ──────────────────────
            // 最初的写法是「在**另一个 traceId** 上放一把外来锁，
            // 验证它没被删」——那把锁本就与 nodeA 要删的 key 不同，
            // 不比对持有者的实现同样不会碰它。注入验证时 CI 照常通过，
            // 说明用例根本没构造出竞争。
            //
            // 真正要复现的是**同一把锁易主**：
            // A 持锁 → 锁超时自动释放 → B 拿到同一个 key → A 收尾调 DEL。
            // 不带持有者标识的实现此刻删掉的是 B 的锁，
            // 两个实例同时进入临界区——正是加锁要防的事。
            //
            // 这里在 A 的临界区内直接把锁的 value 改成别人的 token，
            // 等价于「锁已易主」，无需真的等 TTL 过期。
            nodeA.getOrCreate(traceId, "sess-1");
            String lockKey = "devops:agent:session:lock:" + traceId;

            nodeA.inLock(traceId, () -> {
                // 模拟：A 的锁已过期，B 抢到了同一个 key
                redis.opsForValue().set(lockKey, "token-of-node-B", Duration.ofSeconds(30));
                return null;
            });
            // 此刻 A 已执行完 release()

            try {
                assertThat(redis.opsForValue().get(lockKey))
                        .as("A 收尾时只能删自己那把；删掉接管者的锁会让两个实例同时进临界区")
                        .isEqualTo("token-of-node-B");
            } finally {
                redis.delete(lockKey);
            }
        }

        @Test
        @DisplayName("锁在动作结束后被释放，不残留")
        void lockIsReleasedAfterAction() {
            nodeA.getOrCreate(traceId, "sess-1");
            nodeA.inLock(traceId, () -> "ok");

            assertThat(redis.hasKey("devops:agent:session:lock:" + traceId))
                    .as("锁未释放会让后续迁移全部等到 TTL 超时")
                    .isFalse();
        }

        @Test
        @DisplayName("动作抛异常时锁同样被释放")
        void lockReleasedOnException() {
            nodeA.getOrCreate(traceId, "sess-1");

            assertThatThrownBy(() -> nodeA.inLock(traceId, () -> {
                throw new IllegalStateException("boom");
            })).isInstanceOf(IllegalStateException.class);

            assertThat(redis.hasKey("devops:agent:session:lock:" + traceId))
                    .as("异常路径不释放锁会让该会话彻底卡死")
                    .isFalse();
        }

        @Test
        @DisplayName("运行时异常原样抛出，不被吞成 null")
        void runtimeExceptionPropagates() {
            nodeA.getOrCreate(traceId, "sess-1");

            assertThatThrownBy(() -> nodeA.inLock(traceId, () -> {
                throw new IllegalArgumentException("bad arg");
            }))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("bad arg");
        }
    }

    // ==================================================================
    // 后端标识
    // ==================================================================

    @Test
    @DisplayName("backend() 返回 redis——启动日志据此提示当前用的是哪套存储")
    void backendIsRedis() {
        assertThat(nodeA.backend()).isEqualTo("redis");
    }
}
