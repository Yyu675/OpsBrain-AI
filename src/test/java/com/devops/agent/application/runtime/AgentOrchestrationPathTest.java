package com.devops.agent.application.runtime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 编排层真实调用路径的状态机回放测试。
 *
 * <h3>为什么需要这个类：单测每条边都合法 ≠ 真实路径走得通</h3>
 * {@link AgentStateManagerTest} 逐条验证了状态机的边，但那是在问
 * 「这条边存在吗」。本类问的是另一个问题——
 * <b>{@code DevOpsAgentServiceImpl} 按它实际的调用顺序走一遍，会不会有迁移落不了地？</b>
 *
 * <p>这两件事完全可能背离：每条边单独看都对，但编排层根本没按那些边的
 * 顺序调用。而 {@code transition} 在迁移非法时<b>只返回 null</b>，
 * 编排层此前 14 处调用无一检查返回值——于是路径对不上的后果不是报错，
 * 而是<b>会话轨迹静默缺失一段</b>，没有任何信号。</p>
 *
 * <h3>本类建立时查出的问题：6 条真实路径里有 7 次迁移被静默丢弃</h3>
 * 把编排层的调用顺序照抄下来重放，结果触目惊心：
 * <ol>
 *   <li><b>缓存命中路径（全站最高频）</b>：{@code CONTEXT_PREPARED → SUCCESS}
 *       这条边根本不存在。每一次缓存命中的「成功」都被丢弃，
 *       会话永远停在「上下文就绪」，看板上全是永不完成的会话；</li>
 *   <li><b>所有带工具调用的会话</b>：编排层从 {@code EVIDENCE_READY} 直接迁往
 *       {@code TOOLS_RUNNING}，但状态机只允许经由 {@code TOOLS_PLANNING} 中转——
 *       而 LangChain4j 1.1.0 只有「执行后」回调，那个中间态在生产代码里
 *       <b>从未被触发过</b>。结果整个工具执行段（两次迁移）全部丢失，
 *       轨迹上看不出这次对话到底调没调工具；</li>
 *   <li><b>高风险工单转审批</b>：{@code WAITING_APPROVAL} 是在写库过程中发起的，
 *       而工具状态的两次迁移原本排在写库<b>之后</b>。真实顺序因此变成
 *       「先审批、后工具」，两边互相判非法，<b>「需要人工审批」这件事
 *       彻底没能进入状态机</b>；</li>
 *   <li><b>Saga 补偿失败需人工介入</b>：编排层先把状态打成 {@code FAILED}（终态、
 *       拒绝一切迁出），再调补偿；补偿失败时要迁往 {@code MANUAL_ESCALATED}，
 *       必然被拒。于是「有脏数据残留、需要人工清理」这个<b>最需要被看见的信号</b>
 *       在状态机里完全不存在。而且 {@code COMPENSATING} 从头到尾没人设置过。</li>
 * </ol>
 *
 * <p>这些用例的价值在于<b>钉死顺序</b>：往后任何人调整编排流程、
 * 插入新步骤或挪动某次迁移的位置，只要真实路径走不通就会红。</p>
 */
@DisplayName("编排层真实路径状态机回放")
class AgentOrchestrationPathTest {

    private AgentStateManager manager;

    @BeforeEach
    void setUp() {
        manager = new AgentStateManager();
    }

    /**
     * 按编排层的真实调用顺序回放一条路径，返回被静默丢弃的迁移描述。
     * <p>空列表代表整条路径的每次迁移都真实落地了。</p>
     */
    private List<String> replay(String traceId, Step... steps) {
        manager.getOrCreateSession(traceId, "sess");
        List<String> dropped = new ArrayList<>();
        for (Step s : steps) {
            AgentState before = manager.getCurrentState(traceId);
            AgentStateTransition t = manager.transition(
                    traceId, s.to, s.trigger, s.detail, "SYSTEM", null);
            if (t == null) {
                dropped.add(before + " → " + s.to + "（" + s.detail + "）");
            }
        }
        return dropped;
    }

    private record Step(AgentState to, AgentStateTransition.TriggerType trigger, String detail) {}

    private static Step step(AgentState to, AgentStateTransition.TriggerType trigger, String detail) {
        return new Step(to, trigger, detail);
    }

    /** 断言整条路径无丢弃，并停在预期终点 */
    private void assertPathIntact(String traceId, AgentState expectedEnd, Step... steps) {
        List<String> dropped = replay(traceId, steps);
        assertThat(dropped)
                .as("编排层这条路径上的迁移必须全部落地，否则会话轨迹会缺失且无任何报错")
                .isEmpty();
        assertThat(manager.getCurrentState(traceId)).isEqualTo(expectedEnd);
        // 每一步都该在轨迹里留下一条记录
        assertThat(manager.exportTransitions(traceId)).hasSize(steps.length);
    }

    @Test
    @DisplayName("路径1 · 语义缓存命中（全站最高频）：安全通过 → 成功")
    void cacheHitPath() {
        assertPathIntact("p1", AgentState.SUCCESS,
                step(AgentState.CONTEXT_PREPARED,
                        AgentStateTransition.TriggerType.SECURITY_PASSED, "安全检查通过"),
                step(AgentState.SUCCESS,
                        AgentStateTransition.TriggerType.CACHE_HIT, "语义缓存命中"));
    }

    @Test
    @DisplayName("路径2 · 普通问答（无工具）：安全通过 → 进引擎 → 草稿 → 成功")
    void plainChatPath() {
        assertPathIntact("p2", AgentState.SUCCESS,
                step(AgentState.CONTEXT_PREPARED,
                        AgentStateTransition.TriggerType.SECURITY_PASSED, "安全检查通过"),
                step(AgentState.EVIDENCE_READY,
                        AgentStateTransition.TriggerType.RETRIEVAL_COMPLETED, "进入 Agent 引擎"),
                step(AgentState.DRAFT_READY,
                        AgentStateTransition.TriggerType.DRAFT_GENERATED, "模型生成回答草稿"),
                step(AgentState.SUCCESS,
                        AgentStateTransition.TriggerType.SUCCESS, "流程正常结束"));
    }

    @Test
    @DisplayName("路径3 · 带工具调用：工具开始 → 工具返回 → 草稿 → 成功")
    void toolCallPath() {
        assertPathIntact("p3", AgentState.SUCCESS,
                step(AgentState.CONTEXT_PREPARED,
                        AgentStateTransition.TriggerType.SECURITY_PASSED, "安全检查通过"),
                step(AgentState.EVIDENCE_READY,
                        AgentStateTransition.TriggerType.RETRIEVAL_COMPLETED, "进入 Agent 引擎"),
                // 编排层没有「工具规划中」这一步——LangChain4j 只给了执行后回调
                step(AgentState.TOOLS_RUNNING,
                        AgentStateTransition.TriggerType.TOOL_STARTED, "工具调用：searchKnowledge"),
                step(AgentState.TOOLS_COMPLETED,
                        AgentStateTransition.TriggerType.TOOL_COMPLETED, "工具返回：searchKnowledge"),
                step(AgentState.DRAFT_READY,
                        AgentStateTransition.TriggerType.DRAFT_GENERATED, "模型生成回答草稿"),
                step(AgentState.SUCCESS,
                        AgentStateTransition.TriggerType.SUCCESS, "流程正常结束"));
    }

    @Test
    @DisplayName("路径4 · 双工具调用：第二个工具的迁移同样要落地")
    void multiToolPath() {
        assertPathIntact("p4", AgentState.SUCCESS,
                step(AgentState.CONTEXT_PREPARED,
                        AgentStateTransition.TriggerType.SECURITY_PASSED, "安全检查通过"),
                step(AgentState.EVIDENCE_READY,
                        AgentStateTransition.TriggerType.RETRIEVAL_COMPLETED, "进入 Agent 引擎"),
                step(AgentState.TOOLS_RUNNING,
                        AgentStateTransition.TriggerType.TOOL_STARTED, "工具调用：searchKnowledge"),
                step(AgentState.TOOLS_COMPLETED,
                        AgentStateTransition.TriggerType.TOOL_COMPLETED, "工具返回：searchKnowledge"),
                step(AgentState.TOOLS_RUNNING,
                        AgentStateTransition.TriggerType.TOOL_STARTED, "工具调用：createDevOpsTicket"),
                step(AgentState.TOOLS_COMPLETED,
                        AgentStateTransition.TriggerType.TOOL_COMPLETED, "工具返回：createDevOpsTicket"),
                step(AgentState.DRAFT_READY,
                        AgentStateTransition.TriggerType.DRAFT_GENERATED, "模型生成回答草稿"),
                step(AgentState.SUCCESS,
                        AgentStateTransition.TriggerType.SUCCESS, "流程正常结束"));
    }

    @Test
    @DisplayName("路径5 · 高风险工单转审批：工具迁移必须早于写库发起的审批")
    void approvalPath() {
        // 顺序是这条用例的全部意义：writeTicketFromDraft 内部会迁往 WAITING_APPROVAL，
        // 若工具状态的两次迁移仍排在写库之后，真实顺序就变成「先审批、后工具」，
        // 两边互相判非法，「需要审批」这件事无法进入状态机
        assertPathIntact("p5", AgentState.WAITING_APPROVAL,
                step(AgentState.CONTEXT_PREPARED,
                        AgentStateTransition.TriggerType.SECURITY_PASSED, "安全检查通过"),
                step(AgentState.EVIDENCE_READY,
                        AgentStateTransition.TriggerType.RETRIEVAL_COMPLETED, "进入 Agent 引擎"),
                step(AgentState.TOOLS_RUNNING,
                        AgentStateTransition.TriggerType.TOOL_STARTED, "工具调用：createDevOpsTicket"),
                step(AgentState.TOOLS_COMPLETED,
                        AgentStateTransition.TriggerType.TOOL_COMPLETED, "工具返回：createDevOpsTicket"),
                step(AgentState.WAITING_APPROVAL,
                        AgentStateTransition.TriggerType.APPROVAL_REQUIRED, "HIGH 优先级工单待审批"));
    }

    @Test
    @DisplayName("路径6 · 流式失败触发 Saga 补偿，补偿成功")
    void sagaCompensationPath() {
        assertPathIntact("p6", AgentState.COMPENSATING,
                step(AgentState.CONTEXT_PREPARED,
                        AgentStateTransition.TriggerType.SECURITY_PASSED, "安全检查通过"),
                step(AgentState.EVIDENCE_READY,
                        AgentStateTransition.TriggerType.RETRIEVAL_COMPLETED, "进入 Agent 引擎"),
                step(AgentState.TOOLS_RUNNING,
                        AgentStateTransition.TriggerType.TOOL_STARTED, "工具调用：createDevOpsTicket"),
                step(AgentState.TOOLS_COMPLETED,
                        AgentStateTransition.TriggerType.TOOL_COMPLETED, "工具返回：createDevOpsTicket"),
                // 「正在回滚」此前从未进入过状态机
                step(AgentState.COMPENSATING,
                        AgentStateTransition.TriggerType.COMPENSATION_STARTED, "开始 Saga 补偿"));
    }

    @Test
    @DisplayName("路径7 · Saga 补偿失败需人工介入——最需要被看见的信号")
    void sagaEscalationPath() {
        assertPathIntact("p7", AgentState.MANUAL_ESCALATED,
                step(AgentState.CONTEXT_PREPARED,
                        AgentStateTransition.TriggerType.SECURITY_PASSED, "安全检查通过"),
                step(AgentState.EVIDENCE_READY,
                        AgentStateTransition.TriggerType.RETRIEVAL_COMPLETED, "进入 Agent 引擎"),
                step(AgentState.TOOLS_RUNNING,
                        AgentStateTransition.TriggerType.TOOL_STARTED, "工具调用：createDevOpsTicket"),
                step(AgentState.TOOLS_COMPLETED,
                        AgentStateTransition.TriggerType.TOOL_COMPLETED, "工具返回：createDevOpsTicket"),
                step(AgentState.COMPENSATING,
                        AgentStateTransition.TriggerType.COMPENSATION_STARTED, "开始 Saga 补偿"),
                step(AgentState.MANUAL_ESCALATED,
                        AgentStateTransition.TriggerType.MANUAL_TAKEOVER, "Saga 补偿失败，需人工清理"));
    }

    @Test
    @DisplayName("补偿已升级人工时不再覆盖为 FAILED——信息量更大的状态优先")
    void escalationIsNotOverwrittenByFailed() {
        // 补偿只可能发生在「已经写过东西」之后，而写操作只发生在工具阶段。
        // 因此这条路径必须先走完 TOOLS_RUNNING → TOOLS_COMPLETED，
        // 那才是 COMPENSATING 的合法前驱。
        assertPathIntact("p8", AgentState.MANUAL_ESCALATED,
                step(AgentState.CONTEXT_PREPARED,
                        AgentStateTransition.TriggerType.SECURITY_PASSED, "安全检查通过"),
                step(AgentState.EVIDENCE_READY,
                        AgentStateTransition.TriggerType.RETRIEVAL_COMPLETED, "进入 Agent 引擎"),
                step(AgentState.TOOLS_RUNNING,
                        AgentStateTransition.TriggerType.TOOL_STARTED, "工具调用：createDevOpsTicket"),
                step(AgentState.TOOLS_COMPLETED,
                        AgentStateTransition.TriggerType.TOOL_COMPLETED, "工具返回：createDevOpsTicket"),
                step(AgentState.COMPENSATING,
                        AgentStateTransition.TriggerType.COMPENSATION_STARTED, "开始 Saga 补偿"),
                step(AgentState.MANUAL_ESCALATED,
                        AgentStateTransition.TriggerType.MANUAL_TAKEOVER, "补偿失败"));

        // 编排层据此跳过 FAILED 迁移：「有残留待人工处理」比「失败了」信息量大得多，
        // 且 MANUAL_ESCALATED 后续还要能走到 CLOSED 归档
        assertThat(manager.getCurrentState("p8")).isEqualTo(AgentState.MANUAL_ESCALATED);
        assertThat(AgentState.canTransition(AgentState.MANUAL_ESCALATED, AgentState.CLOSED)).isTrue();
    }

    @Test
    @DisplayName("无写操作的流式失败：不迁 COMPENSATING，避免告警噪音")
    void streamErrorWithoutWriteSkipsCompensationState() {
        // onError 是所有流式失败的公共出口，而绝大多数失败（模型超时、网络抖动）
        // 根本没发生过写操作，compensateSaga 返回 noop。
        // 此时会话还停在 EVIDENCE_READY，而 COMPENSATING 的合法前驱只有
        // TOOLS_COMPLETED / DRAFT_READY——若无条件迁移，每次常规失败都会
        // 撞非法迁移并打出 ERROR 日志，本轮刚加的告警会立刻变成噪音。
        // 告警一旦开始狼来了，真正的问题就再也没人看了。
        assertPathIntact("p12", AgentState.FAILED,
                step(AgentState.CONTEXT_PREPARED,
                        AgentStateTransition.TriggerType.SECURITY_PASSED, "安全检查通过"),
                step(AgentState.EVIDENCE_READY,
                        AgentStateTransition.TriggerType.RETRIEVAL_COMPLETED, "进入 Agent 引擎"),
                // 补偿判定为 noop，跳过 COMPENSATING，直接进终态
                step(AgentState.FAILED,
                        AgentStateTransition.TriggerType.SYSTEM_ERROR, "流式执行异常"));
    }

    @Test
    @DisplayName("路径9 · 各类前置拒绝：预算超限 / 安全拦截 / 配额超限")
    void earlyRejectionPaths() {
        // 预算超限发生在任何迁移之前，从 NEW 直接失败
        assertPathIntact("p9a", AgentState.FAILED,
                step(AgentState.FAILED, AgentStateTransition.TriggerType.FAILED, "预算超限"));

        // 安全拦截同样发生在 CONTEXT_PREPARED 之前
        assertPathIntact("p9b", AgentState.FAILED,
                step(AgentState.FAILED, AgentStateTransition.TriggerType.FAILED, "安全拦截"));

        // 配额超限发生在安全检查通过之后
        assertPathIntact("p9c", AgentState.FAILED,
                step(AgentState.CONTEXT_PREPARED,
                        AgentStateTransition.TriggerType.SECURITY_PASSED, "安全检查通过"),
                step(AgentState.FAILED, AgentStateTransition.TriggerType.FAILED, "配额超限"));
    }

    @Test
    @DisplayName("路径10 · 流式异常（无写操作，补偿无事可回滚）直接失败")
    void streamErrorWithoutWrite() {
        assertPathIntact("p10", AgentState.FAILED,
                step(AgentState.CONTEXT_PREPARED,
                        AgentStateTransition.TriggerType.SECURITY_PASSED, "安全检查通过"),
                step(AgentState.EVIDENCE_READY,
                        AgentStateTransition.TriggerType.RETRIEVAL_COMPLETED, "进入 Agent 引擎"),
                step(AgentState.FAILED,
                        AgentStateTransition.TriggerType.SYSTEM_ERROR, "流式执行异常"));
    }

    @Test
    @DisplayName("回放机制自身有效性：故意走一条错路必须被检出")
    void replayDetectsBrokenPath() {
        // 如果 replay 永远返回空列表，上面所有用例都是假通过。
        // 这里故意从 NEW 直接跳 SUCCESS（明确非法），确认检测确实生效
        List<String> dropped = replay("p11",
                step(AgentState.SUCCESS, AgentStateTransition.TriggerType.SUCCESS, "非法跳跃"));

        assertThat(dropped)
                .as("回放必须能检出被丢弃的迁移，否则整个测试类都是摆设")
                .hasSize(1);
        assertThat(dropped.get(0)).contains("NEW").contains("SUCCESS");
    }
}
