package com.devops.agent.application.runtime;

import com.devops.agent.common.context.TraceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AgentStateManager} 与 {@link AgentState} 状态机单元测试。
 *
 * <h3>为什么这个类值得单独测：它是「会话说过什么、做到哪一步」的唯一真相</h3>
 * 整条 SSE 对话链路上，{@code DevOpsAgentServiceImpl} 有 <b>13 处</b>调用
 * {@code stateManager.transition(...)}，覆盖安全拦截、缓存命中、预算超限、
 * 工具执行、草稿生成、待审批、Saga 补偿升级等全部关键分支。
 * 而这些调用<b>清一色不检查返回值</b>——迁移被判非法时方法返回 null，
 * 调用方察觉不到，业务照常往下走。
 *
 * <p>这意味着状态机里任何一条判断写错，表现形式都不是报错，而是
 * <b>「会话轨迹悄悄少了一段」或「会话永远停在某个状态」</b>。
 * 运维事后回放时看到的是一条断掉的链路，却无从知道是流程真没走到，
 * 还是状态机把合法迁移吞了。所以这里的每条边都必须由测试钉死。</p>
 *
 * <h3>本类写作过程中查出的四个真实缺陷</h3>
 * <ol>
 *   <li><b>COMPENSATING / MANUAL_ESCALATED 的出边是死代码。</b>
 *       {@code isTerminal()} 把这两个状态算进终态，而 {@code canTransition}
 *       开头就 {@code if (from.isTerminal()) return false;}——
 *       于是 switch 里精心写好的 {@code COMPENSATING → CLOSED}、
 *       {@code MANUAL_ESCALATED → CLOSED} 永远执行不到。
 *       后果：补偿完成、人工处理完毕的会话<b>无法归档</b>，
 *       永久卡在「补偿中」「人工升级」，看板上堆积一批分不清死活的僵尸会话；</li>
 *   <li><b>空闲清理对新会话完全失效。</b>{@code evictIdleSessions} 原本
 *       {@code if (lastTransitionTime == null) return false;} 直接放行，
 *       而 {@code getOrCreateSession} 新建的会话该字段恰好是 null。
 *       任何在首次迁移前就中断的会话（安全拦截、预算超限、客户端断连）
 *       都会<b>永久驻留内存</b>，是一条随请求量线性增长的泄漏；</li>
 *   <li><b>transition 的「校验-写入」不是原子的。</b>迁移由请求线程和模型 SSE
 *       回调线程并发触发，无锁时两个线程可能同时通过校验，让状态机
 *       跨过一条不存在的边（例如把 WAITING_APPROVAL 冲成 DRAFT_READY，
 *       绕过审批闸门），并发 add 还会让审计轨迹丢记录；</li>
 *   <li><b>审计轨迹只进不出。</b>类注释宣称职责之一是「回放数据导出」，
 *       但迁移记录锁在私有内部类里、{@code getSession} 又返回私有类型，
 *       包外<b>连变量都声明不出来</b>，等会话被清理轨迹就彻底消失。</li>
 * </ol>
 */
@DisplayName("AgentStateManager 会话状态机")
class AgentStateManagerTest {

    private AgentStateManager manager;

    @BeforeEach
    void setUp() {
        manager = new AgentStateManager();
    }

    @AfterEach
    void tearDown() {
        TraceContext.clear();
    }

    /** 把会话沿合法路径推到指定状态，便于各用例准备前置条件 */
    private void driveTo(String traceId, AgentState... path) {
        for (AgentState s : path) {
            AgentStateTransition t = manager.transition(traceId, s,
                    AgentStateTransition.TriggerType.SYSTEM_ERROR, "驱动至 " + s, "TEST", null);
            assertThat(t)
                    .as("驱动路径中的迁移 → %s 应当合法，否则用例前置条件本身就是错的", s)
                    .isNotNull();
        }
    }

    // ==================== 会话创建与查询 ====================

    @Nested
    @DisplayName("会话创建与查询")
    class SessionLifecycle {

        @Test
        @DisplayName("新建会话初始状态为 NEW，且同一 traceId 复用同一实例")
        void createsSessionWithNewStateAndReuses() {
            var first = manager.getOrCreateSession("trace-1", "sess-1");
            var second = manager.getOrCreateSession("trace-1", "sess-other");

            assertThat(first.getCurrentState()).isEqualTo(AgentState.NEW);
            // 复用而非覆盖：同一 traceId 第二次进来若新建实例，
            // 已积累的迁移轨迹会被整段丢弃
            assertThat(second).isSameAs(first);
            assertThat(second.getSessionId()).isEqualTo("sess-1");
            assertThat(manager.sessionCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("查询不存在的会话返回 null，而不是伪装成 NEW")
        void missingSessionIsNullNotNew() {
            // 「处于新建态」和「根本没这个会话」是两件事。
            // 后者通常意味着会话已被清理或 traceId 传错，
            // 回落成 NEW 会让调用方以为流程刚开始而继续推进。
            assertThat(manager.getSession("nope")).isNull();
            assertThat(manager.getCurrentState("nope")).isNull();
        }

        @Test
        @DisplayName("新建会话的当前状态可通过 getCurrentState 读到")
        void readsCurrentState() {
            manager.getOrCreateSession("trace-2", "sess-2");
            assertThat(manager.getCurrentState("trace-2")).isEqualTo(AgentState.NEW);
        }
    }

    // ==================== 迁移合法性 ====================

    @Nested
    @DisplayName("状态迁移合法性")
    class Transitions {

        @Test
        @DisplayName("合法迁移：记录 from/to、触发器、操作人并更新当前态")
        void legalTransitionRecordsAndUpdates() {
            manager.getOrCreateSession("t", "s");

            AgentStateTransition t = manager.transition("t", AgentState.CONTEXT_PREPARED,
                    AgentStateTransition.TriggerType.SECURITY_PASSED, "安全检查通过", "SYSTEM", "{\"k\":1}");

            assertThat(t).isNotNull();
            assertThat(t.getFromState()).isEqualTo(AgentState.NEW);
            assertThat(t.getToState()).isEqualTo(AgentState.CONTEXT_PREPARED);
            assertThat(t.getTriggerType()).isEqualTo(AgentStateTransition.TriggerType.SECURITY_PASSED);
            assertThat(t.getTriggerDetail()).isEqualTo("安全检查通过");
            assertThat(t.getOperator()).isEqualTo("SYSTEM");
            assertThat(t.getMetadata()).isEqualTo("{\"k\":1}");
            assertThat(t.getSessionId()).isEqualTo("s");
            assertThat(t.getTimestamp()).isNotNull();
            assertThat(manager.getCurrentState("t")).isEqualTo(AgentState.CONTEXT_PREPARED);
        }

        @Test
        @DisplayName("非法迁移返回 null 且不改变当前状态——跳跃式迁移必须被挡住")
        void illegalTransitionKeepsStateUntouched() {
            manager.getOrCreateSession("t", "s");

            // NEW 直接跳 SUCCESS：绕过检索、工具、草稿全部环节
            AgentStateTransition t = manager.transition("t", AgentState.SUCCESS,
                    AgentStateTransition.TriggerType.SUCCESS, "跳跃", "SYSTEM", null);

            assertThat(t).isNull();
            assertThat(manager.getCurrentState("t")).isEqualTo(AgentState.NEW);
            assertThat(manager.exportTransitions("t")).isEmpty();
        }

        @Test
        @DisplayName("会话不存在时迁移返回 null，且不会顺手把会话建出来")
        void transitionOnMissingSessionDoesNotCreateIt() {
            AgentStateTransition t = manager.transition("ghost", AgentState.CONTEXT_PREPARED,
                    AgentStateTransition.TriggerType.SECURITY_PASSED, "x", "SYSTEM", null);

            assertThat(t).isNull();
            assertThat(manager.sessionCount()).isZero();
        }

        @Test
        @DisplayName("任何状态都能迁到 FAILED——失败是随时可能发生的")
        void anyActiveStateCanFail() {
            String[] ids = {"a", "b", "c", "d"};
            AgentState[][] paths = {
                    {},
                    {AgentState.CONTEXT_PREPARED},
                    {AgentState.CONTEXT_PREPARED, AgentState.EVIDENCE_READY},
                    {AgentState.CONTEXT_PREPARED, AgentState.EVIDENCE_READY, AgentState.DRAFT_READY},
            };
            for (int i = 0; i < ids.length; i++) {
                manager.getOrCreateSession(ids[i], "s");
                driveTo(ids[i], paths[i]);
                assertThat(manager.transition(ids[i], AgentState.FAILED,
                        AgentStateTransition.TriggerType.FAILED, "炸了", "SYSTEM", null))
                        .as("从 %s 迁往 FAILED 必须合法", manager.getCurrentState(ids[i]))
                        .isNotNull();
            }
        }

        @Test
        @DisplayName("缓存命中：CONTEXT_PREPARED 可直达 SUCCESS")
        void cacheHitGoesStraightToSuccess() {
            manager.getOrCreateSession("t", "s");
            driveTo("t", AgentState.CONTEXT_PREPARED);

            // 语义缓存命中时不经检索/工具/草稿，直接出结果。
            // 这是全站最高频路径，缺这条边会让每次缓存命中的「成功」都被静默丢弃，
            // 会话永远停在「上下文就绪」
            assertThat(manager.transition("t", AgentState.SUCCESS,
                    AgentStateTransition.TriggerType.CACHE_HIT, "语义缓存命中", "SYSTEM", null))
                    .isNotNull();
            assertThat(manager.getCurrentState("t")).isEqualTo(AgentState.SUCCESS);
        }

        @Test
        @DisplayName("EVIDENCE_READY 可直达 TOOLS_RUNNING——不存在「工具规划」回调")
        void evidenceReadyGoesStraightToToolsRunning() {
            manager.getOrCreateSession("t", "s");
            driveTo("t", AgentState.CONTEXT_PREPARED, AgentState.EVIDENCE_READY);

            // LangChain4j 1.1.0 只有 onToolExecuted（执行后）回调，
            // 没有任何「模型正在规划工具」的钩子，TOOLS_PLANNING 在生产代码里零引用。
            // 编排层实际就是从 EVIDENCE_READY 直接跳 TOOLS_RUNNING
            assertThat(manager.transition("t", AgentState.TOOLS_RUNNING,
                    AgentStateTransition.TriggerType.TOOL_STARTED, "工具调用", "SYSTEM", null))
                    .isNotNull();
        }

        @Test
        @DisplayName("写操作发生在工具阶段，故 TOOLS_COMPLETED/DRAFT_READY 可进入补偿")
        void writePhasesCanEnterCompensation() {
            // Saga 回滚的触发点是流式失败，此时会话正处于这两个状态之一。
            // 没有这两条边，「正在回滚」根本无法进入状态机
            assertThat(AgentState.canTransition(AgentState.TOOLS_COMPLETED, AgentState.COMPENSATING)).isTrue();
            assertThat(AgentState.canTransition(AgentState.DRAFT_READY, AgentState.COMPENSATING)).isTrue();
        }

        @Test
        @DisplayName("多工具场景：TOOLS_COMPLETED 可回到 TOOLS_RUNNING")
        void multiToolLoopIsLegal() {
            manager.getOrCreateSession("t", "s");
            driveTo("t", AgentState.CONTEXT_PREPARED, AgentState.EVIDENCE_READY,
                    AgentState.TOOLS_PLANNING, AgentState.TOOLS_RUNNING, AgentState.TOOLS_COMPLETED);

            // 第二个工具开始执行。这条边缺失时，双工具调用的第二次迁移
            // 会被静默丢弃，轨迹上只剩一个工具
            assertThat(manager.transition("t", AgentState.TOOLS_RUNNING,
                    AgentStateTransition.TriggerType.TOOL_STARTED, "第二个工具", "SYSTEM", null))
                    .isNotNull();
        }

        @Test
        @DisplayName("耗时统计：首次迁移为 0，后续迁移记录距上次的间隔且非负")
        void durationIsNonNegative() throws InterruptedException {
            manager.getOrCreateSession("t", "s");

            AgentStateTransition first = manager.transition("t", AgentState.CONTEXT_PREPARED,
                    AgentStateTransition.TriggerType.SECURITY_PASSED, "x", "SYSTEM", null);
            // 首次迁移时 lastTransitionTime 尚未设置，按 0 计——
            // 不能拿创建时间硬凑一个「耗时」，那测的是排队而非处理
            assertThat(first.getDurationMs()).isZero();

            Thread.sleep(15);
            AgentStateTransition second = manager.transition("t", AgentState.EVIDENCE_READY,
                    AgentStateTransition.TriggerType.RETRIEVAL_COMPLETED, "x", "SYSTEM", null);

            assertThat(second.getDurationMs()).isGreaterThanOrEqualTo(10);
        }
    }

    // ==================== 缺陷 1：终态定义 ====================

    @Nested
    @DisplayName("终态定义（缺陷：补偿/人工升级的出边曾是死代码）")
    class TerminalSemantics {

        @Test
        @DisplayName("COMPENSATING 可归档到 CLOSED，也可升级为人工处理")
        void compensatingCanReachClosed() {
            manager.getOrCreateSession("t", "s");
            driveTo("t", AgentState.CONTEXT_PREPARED, AgentState.EVIDENCE_READY,
                    AgentState.TOOLS_PLANNING, AgentState.TOOLS_RUNNING, AgentState.TOOLS_COMPLETED,
                    AgentState.WAITING_APPROVAL, AgentState.EXECUTING, AgentState.COMPENSATING);

            // 修复前：isTerminal() 含 COMPENSATING → canTransition 直接 return false，
            // Saga 补偿跑完的会话永远归不了档
            assertThat(AgentState.canTransition(AgentState.COMPENSATING, AgentState.CLOSED)).isTrue();
            assertThat(AgentState.canTransition(AgentState.COMPENSATING, AgentState.MANUAL_ESCALATED)).isTrue();
            assertThat(manager.transition("t", AgentState.CLOSED,
                    AgentStateTransition.TriggerType.COMPENSATION_COMPLETED, "补偿完成", "SYSTEM", null))
                    .isNotNull();
            assertThat(manager.getCurrentState("t")).isEqualTo(AgentState.CLOSED);
        }

        @Test
        @DisplayName("MANUAL_ESCALATED 可归档到 CLOSED——人工处理完必须能收尾")
        void manualEscalatedCanReachClosed() {
            manager.getOrCreateSession("t", "s");
            driveTo("t", AgentState.CONTEXT_PREPARED, AgentState.EVIDENCE_READY,
                    AgentState.DRAFT_READY, AgentState.WAITING_APPROVAL, AgentState.MANUAL_ESCALATED);

            assertThat(manager.transition("t", AgentState.CLOSED,
                    AgentStateTransition.TriggerType.MANUAL_TAKEOVER, "人工处理完毕", "OPS", null))
                    .isNotNull();
        }

        @Test
        @DisplayName("真正的终态 SUCCESS/FAILED/CLOSED 拒绝一切迁出")
        void realTerminalStatesRejectEverything() {
            for (AgentState terminal : List.of(AgentState.SUCCESS, AgentState.FAILED, AgentState.CLOSED)) {
                assertThat(terminal.isTerminal())
                        .as("%s 应为终态", terminal).isTrue();
                for (AgentState to : AgentState.values()) {
                    assertThat(AgentState.canTransition(terminal, to))
                            .as("终态 %s 不应能迁往 %s", terminal, to)
                            .isFalse();
                }
            }
        }

        @Test
        @DisplayName("COMPENSATING/MANUAL_ESCALATED 不是终态，但属于「已脱离自动流程」")
        void settledButNotTerminal() {
            for (AgentState s : List.of(AgentState.COMPENSATING, AgentState.MANUAL_ESCALATED)) {
                // 这两件事必须分开：混成一个判断，必有一方被误伤
                assertThat(s.isTerminal()).as("%s 不应算不可迁移终态", s).isFalse();
                assertThat(s.isSettled()).as("%s 应算已脱离自动流程", s).isTrue();
            }
            assertThat(AgentState.SUCCESS.isSettled()).isTrue();
            assertThat(AgentState.TOOLS_RUNNING.isSettled()).isFalse();
        }

        @Test
        @DisplayName("状态机不存在孤岛：每个非终态都至少有一条出边")
        void everyNonTerminalHasAnExit() {
            for (AgentState from : AgentState.values()) {
                if (from.isTerminal()) continue;
                boolean hasExit = false;
                for (AgentState to : AgentState.values()) {
                    if (AgentState.canTransition(from, to)) {
                        hasExit = true;
                        break;
                    }
                }
                // 出不去的非终态 = 会话卡死在此，且不会有人发现
                assertThat(hasExit).as("非终态 %s 必须至少有一条出边，否则会话会卡死", from).isTrue();
            }
        }

        @Test
        @DisplayName("每个非终态都能走到某个真正的终态（无死循环子图）")
        void everyStateCanReachTerminal() {
            for (AgentState start : AgentState.values()) {
                assertThat(canReachTerminal(start))
                        .as("从 %s 出发必须存在一条通往终态的路径", start)
                        .isTrue();
            }
        }

        /** 广度优先搜索：从 start 出发能否抵达任一真正终态 */
        private boolean canReachTerminal(AgentState start) {
            if (start.isTerminal()) return true;
            var visited = new java.util.HashSet<AgentState>();
            var queue = new java.util.ArrayDeque<AgentState>();
            queue.add(start);
            visited.add(start);
            while (!queue.isEmpty()) {
                AgentState cur = queue.poll();
                if (cur.isTerminal()) return true;
                for (AgentState next : AgentState.values()) {
                    if (AgentState.canTransition(cur, next) && visited.add(next)) {
                        queue.add(next);
                    }
                }
            }
            return false;
        }

        @Test
        @DisplayName("null 参数不抛异常，按不合法处理")
        void nullIsRejectedNotThrown() {
            assertThat(AgentState.canTransition(null, AgentState.SUCCESS)).isFalse();
            assertThat(AgentState.canTransition(AgentState.NEW, null)).isFalse();
            assertThat(AgentState.canTransition(null, null)).isFalse();
        }
    }

    // ==================== 缺陷 2：审计轨迹导出 ====================

    @Nested
    @DisplayName("审计轨迹导出（缺陷：轨迹曾只进不出）")
    class TransitionExport {

        @Test
        @DisplayName("按发生顺序导出全部迁移记录")
        void exportsInOrder() {
            manager.getOrCreateSession("t", "s");
            driveTo("t", AgentState.CONTEXT_PREPARED, AgentState.EVIDENCE_READY, AgentState.DRAFT_READY);

            List<AgentStateTransition> trail = manager.exportTransitions("t");

            assertThat(trail).hasSize(3);
            assertThat(trail).extracting(AgentStateTransition::getToState)
                    .containsExactly(AgentState.CONTEXT_PREPARED, AgentState.EVIDENCE_READY, AgentState.DRAFT_READY);
            // from 必须接得上前一条的 to，否则回放出来的链路是断的
            assertThat(trail.get(1).getFromState()).isEqualTo(AgentState.CONTEXT_PREPARED);
            assertThat(trail.get(2).getFromState()).isEqualTo(AgentState.EVIDENCE_READY);
        }

        @Test
        @DisplayName("导出的是不可变副本——外部改不动内部轨迹")
        void exportIsImmutableCopy() {
            manager.getOrCreateSession("t", "s");
            driveTo("t", AgentState.CONTEXT_PREPARED);

            List<AgentStateTransition> trail = manager.exportTransitions("t");
            assertThat(trail).hasSize(1);

            // 返回内部 List 本身会让调用方能清空审计记录
            try {
                trail.add(new AgentStateTransition());
                org.junit.jupiter.api.Assertions.fail("导出的轨迹不应可写");
            } catch (UnsupportedOperationException expected) {
                // 预期
            }

            // 再次迁移后重新导出应看到新记录，说明副本没有切断后续观察
            driveTo("t", AgentState.EVIDENCE_READY);
            assertThat(manager.exportTransitions("t")).hasSize(2);
        }

        @Test
        @DisplayName("会话不存在时导出空列表，而不是 null")
        void exportMissingSessionIsEmpty() {
            // 返回 null 会让调用方在 for 循环处 NPE，而这只是一次查询
            assertThat(manager.exportTransitions("nope")).isEmpty();
        }
    }

    // ==================== 缺陷 3：并发原子性 ====================

    @Nested
    @DisplayName("并发安全（缺陷：校验与写入曾非原子）")
    class Concurrency {

        @Test
        @DisplayName("多线程从同一状态争抢迁移，只有一个成功，状态不被覆盖")
        void onlyOneWinnerWhenRacing() throws InterruptedException {
            int threads = 16;
            manager.getOrCreateSession("race", "s");
            driveTo("race", AgentState.CONTEXT_PREPARED, AgentState.EVIDENCE_READY, AgentState.DRAFT_READY);

            // 目标全取终态（SUCCESS / FAILED），二者从 DRAFT_READY 出发都合法且互斥：
            // 谁先落地，会话就进入终态，其余线程必须全部被拒。
            // 无锁时多个线程会同时读到 DRAFT_READY 并各自校验通过，
            // 后写的覆盖先写的——一个已判定失败的会话会被冲成「成功」，
            // 反之亦然，而且两条互相矛盾的记录都会留在审计轨迹里。
            //
            // 刻意不把 WAITING_APPROVAL 混进来：它不是终态，
            // 赢下之后仍能合法迁往 FAILED，那样「只有一个赢家」的断言本身就不成立，
            // 测的就不再是原子性而是状态机形状了。
            AgentState[] targets = {AgentState.SUCCESS, AgentState.FAILED};
            var latch = new CountDownLatch(1);
            var done = new CountDownLatch(threads);
            var succeeded = new AtomicInteger();
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            try {
                for (int i = 0; i < threads; i++) {
                    AgentState target = targets[i % targets.length];
                    pool.submit(() -> {
                        try {
                            latch.await();
                            if (manager.transition("race", target,
                                    AgentStateTransition.TriggerType.SYSTEM_ERROR, "race", "T", null) != null) {
                                succeeded.incrementAndGet();
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            done.countDown();
                        }
                    });
                }
                latch.countDown();
                assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
            } finally {
                pool.shutdownNow();
            }

            // 目标全是终态，第一个赢家落地后其余必须全部被拒
            assertThat(succeeded.get()).isEqualTo(1);
            // 3 条驱动 + 1 条竞争胜出，多一条就说明有线程越过了终态门禁
            assertThat(manager.exportTransitions("race")).hasSize(4);
            assertThat(manager.getCurrentState("race")).isIn(AgentState.SUCCESS, AgentState.FAILED);
        }

        @Test
        @DisplayName("并发迁移不丢审计记录——成功次数与轨迹长度必须相等")
        void trailLengthMatchesSuccessCount() throws InterruptedException {
            int rounds = 200;
            var succeeded = new AtomicInteger();
            var done = new CountDownLatch(2);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                for (int t = 0; t < 2; t++) {
                    pool.submit(() -> {
                        try {
                            for (int i = 0; i < rounds; i++) {
                                String id = "s" + i;
                                manager.getOrCreateSession(id, id);
                                // TOOLS_COMPLETED ⇄ TOOLS_RUNNING 是合法环，可反复迁移
                                if (manager.transition(id, AgentState.CONTEXT_PREPARED,
                                        AgentStateTransition.TriggerType.SECURITY_PASSED, "x", "T", null) != null) {
                                    succeeded.incrementAndGet();
                                }
                            }
                        } finally {
                            done.countDown();
                        }
                    });
                }
                assertThat(done.await(20, TimeUnit.SECONDS)).isTrue();
            } finally {
                pool.shutdownNow();
            }

            // 每个 traceId 只可能有一次 NEW→CONTEXT_PREPARED 成功
            assertThat(succeeded.get()).isEqualTo(rounds);
            int totalTrail = 0;
            for (int i = 0; i < rounds; i++) {
                totalTrail += manager.exportTransitions("s" + i).size();
            }
            // ArrayList 并发 add 会丢元素，这里必须严格相等
            assertThat(totalTrail).isEqualTo(rounds);
        }

        @Test
        @DisplayName("导出轨迹的同时持续写入，不抛 ConcurrentModificationException")
        void exportDuringWriteIsSafe() throws InterruptedException {
            manager.getOrCreateSession("t", "s");
            driveTo("t", AgentState.CONTEXT_PREPARED, AgentState.EVIDENCE_READY,
                    AgentState.TOOLS_PLANNING, AgentState.TOOLS_RUNNING);

            var stop = new java.util.concurrent.atomic.AtomicBoolean(false);
            var error = new java.util.concurrent.atomic.AtomicReference<Throwable>();
            Thread writer = new Thread(() -> {
                try {
                    while (!stop.get()) {
                        manager.transition("t", AgentState.TOOLS_COMPLETED,
                                AgentStateTransition.TriggerType.TOOL_COMPLETED, "x", "T", null);
                        manager.transition("t", AgentState.TOOLS_RUNNING,
                                AgentStateTransition.TriggerType.TOOL_STARTED, "x", "T", null);
                    }
                } catch (Throwable e) {
                    error.set(e);
                }
            });
            writer.start();
            try {
                for (int i = 0; i < 500; i++) {
                    // 直接返回内部 ArrayList 时，这里会随机撞 ConcurrentModificationException——
                    // 一次「只是看看轨迹」的读操作把请求打挂
                    List<AgentStateTransition> snapshot = manager.exportTransitions("t");
                    for (AgentStateTransition tr : snapshot) {
                        assertThat(tr.getToState()).isNotNull();
                    }
                }
            } finally {
                stop.set(true);
                writer.join(5000);
            }
            assertThat(error.get()).isNull();
        }
    }

    // ==================== 缺陷 4：空闲清理 ====================

    @Nested
    @DisplayName("空闲会话清理（缺陷：从未迁移过的会话永不回收）")
    class IdleEviction {

        /** 反射调用私有清理方法：清理由后台线程每 5 分钟触发，测试等不起 */
        private void evict() throws Exception {
            var m = AgentStateManager.class.getDeclaredMethod("evictIdleSessions");
            m.setAccessible(true);
            m.invoke(manager);
        }

        /** 把会话的空闲基准时间人为拨到过去 */
        private void backdate(String traceId, long minutesAgo) throws Exception {
            Object session = manager.getSession(traceId);
            var cls = session.getClass();
            var past = java.time.LocalDateTime.now().minusMinutes(minutesAgo);
            for (String f : new String[]{"createdAt", "lastTransitionTime"}) {
                var field = cls.getDeclaredField(f);
                field.setAccessible(true);
                if (field.get(session) != null || "createdAt".equals(f)) {
                    field.set(session, past);
                }
            }
        }

        @Test
        @DisplayName("从未迁移过的陈旧会话会被回收——修复前它们永久驻留")
        void evictsSessionThatNeverTransitioned() throws Exception {
            manager.getOrCreateSession("stale", "s");
            // 典型场景：请求刚进来就被安全门卫拒绝 / 预算超限 / 客户端断连，
            // 会话建出来后一次迁移都没发生，lastTransitionTime 恒为 null
            backdate("stale", 45);

            evict();

            assertThat(manager.sessionCount())
                    .as("从未迁移的陈旧会话必须被回收，否则是随请求量线性增长的内存泄漏")
                    .isZero();
        }

        @Test
        @DisplayName("迁移过但已超时的会话同样被回收")
        void evictsIdleSessionAfterTransition() throws Exception {
            manager.getOrCreateSession("old", "s");
            driveTo("old", AgentState.CONTEXT_PREPARED);
            backdate("old", 45);

            evict();

            assertThat(manager.getSession("old")).isNull();
        }

        @Test
        @DisplayName("活跃会话不被误清——刚迁移过的会话必须留下")
        void keepsActiveSession() throws Exception {
            manager.getOrCreateSession("fresh", "s");
            driveTo("fresh", AgentState.CONTEXT_PREPARED);

            evict();

            assertThat(manager.getSession("fresh")).isNotNull();
            assertThat(manager.getCurrentState("fresh")).isEqualTo(AgentState.CONTEXT_PREPARED);
        }

        @Test
        @DisplayName("刚创建、尚未迁移的会话在超时前不被清掉")
        void keepsBrandNewSession() throws Exception {
            manager.getOrCreateSession("newborn", "s");

            evict();

            // 回落到创建时间不能矫枉过正：新会话此刻空闲时长约等于 0
            assertThat(manager.getSession("newborn")).isNotNull();
        }

        @Test
        @DisplayName("混合场景：只清超时的，不动活跃的")
        void evictsOnlyExpired() throws Exception {
            manager.getOrCreateSession("stale-a", "s");
            manager.getOrCreateSession("stale-b", "s");
            driveTo("stale-b", AgentState.CONTEXT_PREPARED);
            manager.getOrCreateSession("live", "s");
            backdate("stale-a", 60);
            backdate("stale-b", 60);

            evict();

            assertThat(manager.sessionCount()).isEqualTo(1);
            assertThat(manager.getSession("live")).isNotNull();
        }
    }

    // ==================== 简化版迁移（依赖 TraceContext） ====================

    @Nested
    @DisplayName("简化版迁移（从 TraceContext 取 traceId）")
    class ShorthandTransition {

        @Test
        @DisplayName("TraceContext 有 traceId 时正常迁移，操作人记为 SYSTEM")
        void usesTraceContext() {
            TraceContext.setTraceId("ctx-trace");
            manager.getOrCreateSession("ctx-trace", "s");

            AgentStateTransition t = manager.transition(AgentState.CONTEXT_PREPARED,
                    AgentStateTransition.TriggerType.SECURITY_PASSED, "安全通过");

            assertThat(t).isNotNull();
            assertThat(t.getTraceId()).isEqualTo("ctx-trace");
            assertThat(t.getOperator()).isEqualTo("SYSTEM");
        }

        @Test
        @DisplayName("TraceContext 无 traceId 时返回 null 而非抛异常")
        void nullTraceIdReturnsNull() {
            TraceContext.clear();

            // 工具回调跑在模型 HTTP 线程上，ThreadLocal 取不到 traceId。
            // 这条路径必须安静降级——为了记一条审计而让业务请求失败是本末倒置。
            // 也正因为它静默，调用方才应尽量用显式传 traceId 的 6 参重载。
            assertThat(manager.transition(AgentState.CONTEXT_PREPARED,
                    AgentStateTransition.TriggerType.SECURITY_PASSED, "x")).isNull();
        }
    }
}
