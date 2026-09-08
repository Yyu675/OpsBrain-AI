package com.devops.agent.application.runtime;

/**
 * Agent 执行状态机
 * <p>
 * 定义 Agent 从接收请求到完结的所有显式状态，解决"状态漂移"风险（Agent Methodology §10）。
 * 每次状态迁移都会被记录审计日志，支持断点恢复、回放、一致性校验。
 * </p>
 * <p>
 * 状态迁移规则：仅允许相邻状态流转，禁止跳跃。
 * 迁移触发器：外部事件（用户请求/工具返回/人工审批/超时/错误）
 * </p>
 *
 * <h3>已删除的状态：TOOLS_PLANNING（"工具规划中"）</h3>
 * 这个状态语义上很自然——「模型正在决定调哪个工具」，迁移表里也为它写好了
 * {@code EVIDENCE_READY → TOOLS_PLANNING → TOOLS_RUNNING} 一整条通路。
 * 但它<b>从来没有、也不可能被触发</b>：LangChain4j 1.1.0 只提供
 * {@code onToolExecuted} 这一个「执行<b>后</b>」回调，
 * 框架层根本不存在「模型开始规划工具」这个可观测时点。
 *
 * <p>它造成的实际损害不止是「多一个没用的枚举值」：迁移表要求工具执行
 * <b>必须</b>经它中转，而编排层只能从 {@code EVIDENCE_READY} 直接跳到
 * {@code TOOLS_RUNNING}——于是<b>所有带工具调用的会话，整个工具执行段的
 * 两次迁移全部被判非法并静默丢弃</b>。一个永远不会亮的中间站，
 * 把真实存在的那条路给堵死了。</p>
 *
 * <p><b>不要因为「以后可能有用」把它加回来。</b>要加回来的前提是
 * 框架先提供对应回调，并且<b>同时</b>有生产代码去触发它；
 * 只加枚举值不加触发点，等于再造一次同样的堵路。</p>
 *
 * <h3>与「预留状态」的界线</h3>
 * {@link #EXECUTING} 与 {@link #OBSERVING} 同样零触发，但<b>保留</b>。
 * 区别在于：TOOLS_PLANNING 是<b>永远不可能</b>被触发（依赖的框架回调不存在），
 * 而那两个是<b>尚未</b>触发——它们对应蓝图里 L3 半自动自愈的执行与观察阶段，
 * 有明确定义和落地路径。<b>死代码和预留接口不是一回事</b>，
 * 判据是「它有没有可能被触发」，而不是「它现在有没有被触发」。
 *
 * @author OpsBrain AI
 * @since 2026-08-08
 */
public enum AgentState {

    // ========== 会话生命周期状态 ==========

    /**
     * 初始状态：刚收到用户请求，尚未开始处理
     */
    NEW("新建", "收到请求"),

    /**
     * 上下文就绪：安全检查/缓存检查/路由分流完成，准备检索
     */
    CONTEXT_PREPARED("上下文就绪", "安全/缓存/路由完成"),

    /**
     * 证据就绪：RAG 检索完成，证据包已构建（含预算裁剪）
     */
    EVIDENCE_READY("证据就绪", "检索完成"),

    // 注：此处曾有 TOOLS_PLANNING（"工具规划中"）。已删除，原因见类注释
    // 「已删除的状态」一节——LangChain4j 1.1.0 没有对应回调，它无法被触发。

    /**
     * 工具执行中：正在调用外部工具（检索/创建工单等）
     * <p>
     * 由 {@code onToolExecuted} 回调驱动。该回调在工具<b>执行完成后</b>触发，
     * 编排层在其中连续记录 {@code TOOLS_RUNNING → TOOLS_COMPLETED} 两次迁移，
     * 以还原「开始执行 / 执行完毕」两个时点。
     * </p>
     */
    TOOLS_RUNNING("工具执行中", "工具调用进行中"),

    /**
     * 工具完成：工具返回结果（可能包含部分失败）
     */
    TOOLS_COMPLETED("工具完成", "工具返回结果"),

    /**
     * 草稿就绪：模型生成了初步回答草稿
     */
    DRAFT_READY("草稿就绪", "模型生成回答"),

    // ========== 高风险人工介入状态 ==========

    /**
     * 待人工审批：高风险操作（如 P0/P1 故障自愈、敏感工单）需人工授权
     */
    WAITING_APPROVAL("待人工审批", "高风险操作需授权"),

    /**
     * 执行中：已获授权，正在执行修复/写操作
     *
     * <p><b>⚠️ 预留状态，当前无生产代码触发。</b>与 {@link #OBSERVING} 一同
     * 服务于蓝图中的 <b>L3 半自动自愈</b>（见
     * {@code docs/02-architecture-design/OpsBrain_AI_L1至L5全自动智能自愈与商业化拓展蓝图.md}）：
     * P2/P3 级故障下 AI 自动执行非破坏性自愈指令（如
     * {@code kubectl rollout restart}），随后进入健康心跳观察期。
     * 待自愈执行引擎落地后接入。</p>
     *
     * <p><b>与已删除的 TOOLS_PLANNING 的区别</b>：那个状态是<b>永远不可能</b>被
     * 触发的（依赖的框架回调不存在），而本状态只是<b>尚未</b>触发——
     * 对应的能力在蓝图里有明确定义、有落地路径。前者是死代码，
     * 后者是预留接口，二者不可混为一谈。</p>
     */
    EXECUTING("执行中", "自愈/写操作进行中"),

    /**
     * 观测中：执行动作已发出，等待结果反馈验证
     *
     * <p><b>⚠️ 预留状态，当前无生产代码触发。</b>对应蓝图 L3 自愈的
     * 「修复后启动 5 分钟健康指标心跳监控」阶段：心跳恢复正常则自动结单
     * （{@code → SUCCESS}），异常则升级 P1 人工介入。
     * 理由同 {@link #EXECUTING}。</p>
     */
    OBSERVING("观测中", "验证执行结果"),

    // ========== 终态 ==========

    /**
     * 成功：全流程正常完结
     */
    SUCCESS("成功", "流程正常结束"),

    /**
     * 失败：遇到不可恢复错误，流程终止
     */
    FAILED("失败", "遇到不可恢复错误"),

    /**
     * 补偿中：前序步骤失败，正在执行逆向补偿操作
     */
    COMPENSATING("补偿中", "执行 Saga 补偿"),

    /**
     * 人工升级：自动化无法处理，已升级至人工处理
     */
    MANUAL_ESCALATED("人工升级", "已转人工处理"),

    /**
     * 归档：会话彻底结束，进入冷存储
     */
    CLOSED("归档", "会话彻底结束");

    private final String displayName;
    private final String description;

    AgentState(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    /**
     * 判断是否为<b>不可再迁移的终态</b>
     * <p>
     * <b>此处只列真正走不出去的状态</b>：{@link #SUCCESS}、{@link #FAILED}、{@link #CLOSED}。
     * </p>
     *
     * <h3>为什么 COMPENSATING / MANUAL_ESCALATED 不算终态（缺陷修复）</h3>
     * 这两个状态在 {@link #canTransition} 的 switch 里各自定义了出边
     * （{@code COMPENSATING → CLOSED | MANUAL_ESCALATED}、{@code MANUAL_ESCALATED → CLOSED}），
     * 但它们曾被本方法一并算作终态，而 {@code canTransition} 开头就有
     * {@code if (from.isTerminal()) return false;}——<b>于是那些 case 分支永远执行不到，
     * 是彻头彻尾的死代码</b>。
     *
     * <p>用户可见后果：Saga 补偿跑完、或人工接管处理完之后，会话<b>永远停在
     * 「补偿中」「人工升级」</b>，无法归档到 CLOSED。运维在会话轨迹里看到的是一批
     * 「卡在补偿中」的僵尸会话，无法区分「真的还在补偿」和「补偿早就结束了但状态没推进」，
     * 后台还会刷出「非法状态迁移」告警。同时这些会话因为不再产生迁移，
     * 也拿不到正确的空闲清理时间。</p>
     *
     * <p>语义上这两个状态本就是<b>进行时</b>（「补偿<b>中</b>」「已转人工<b>处理</b>」），
     * 不是流程的句号；真正的句号是它们之后的 {@link #CLOSED}。</p>
     *
     * @return true 表示不允许再从此状态迁出
     */
    public boolean isTerminal() {
        return this == SUCCESS || this == FAILED || this == CLOSED;
    }

    /**
     * 判断会话是否<b>已脱离自动流程</b>（不再由 Agent 主动推进）
     * <p>
     * 与 {@link #isTerminal()} 的区别：本方法为<b>「统计/展示」口径</b>——
     * 补偿中与人工升级都已不在正常自动化链路上，看板上应与成功/失败一并
     * 从「进行中」里摘出去；但它们<b>仍可继续迁移到 {@link #CLOSED}</b>，
     * 所以不能拿本方法当迁移门禁。
     * </p>
     * <p>
     * 拆成两个方法而非复用一个，是因为上一处同类缺陷已经证明：
     * 把「不再活跃」和「不许再改」混成一个判断，必然有一方被误伤。
     * </p>
     */
    public boolean isSettled() {
        return isTerminal() || this == COMPENSATING || this == MANUAL_ESCALATED;
    }

    /**
     * 判断是否为高风险需审批状态
     */
    public boolean requiresApproval() {
        return this == WAITING_APPROVAL;
    }

    /**
     * 判断是否为执行类状态（会改变业务状态）
     */
    public boolean isExecutionState() {
        return this == EXECUTING || this == OBSERVING;
    }

    /**
     * 合法的下一状态集合（防止非法跳跃）
     */
    public static boolean canTransition(AgentState from, AgentState to) {
        if (from == null || to == null) return false;
        if (from.isTerminal()) return false; // 终态不可再迁移

        // 定义合法迁移路径
        return switch (from) {
            case NEW -> to == CONTEXT_PREPARED || to == FAILED;
            // CONTEXT_PREPARED → SUCCESS：语义缓存命中直接出结果，不经检索/工具/草稿。
            // 这是全站最高频的一条路径，缺这条边会让每一次缓存命中的「成功」都被静默丢弃，
            // 会话永远停在「上下文就绪」（P1-2）。
            case CONTEXT_PREPARED -> to == EVIDENCE_READY || to == SUCCESS || to == FAILED;
            // EVIDENCE_READY → TOOLS_RUNNING：LangChain4j 1.1.0 只提供 onToolExecuted
            // 这一个「执行后」回调，没有任何「模型正在规划工具」的钩子。
            // 编排层因此是从 EVIDENCE_READY 直接跳到 TOOLS_RUNNING 的，
            // 缺这条边会让所有带工具调用的会话丢掉整个工具执行段（P1-2）。
            case EVIDENCE_READY -> to == TOOLS_RUNNING || to == DRAFT_READY || to == FAILED;
            case TOOLS_RUNNING -> to == TOOLS_COMPLETED || to == FAILED;
            // TOOLS_COMPLETED → TOOLS_RUNNING：多工具场景第二步工具开始执行需此合法边，
            // 否则双工具调用时第二次迁移被 StateManager 判为非法静默丢弃（P1-1）。
            // TOOLS_COMPLETED / DRAFT_READY → COMPENSATING：写操作发生在工具阶段，
            // 流式失败触发 Saga 回滚时会话正处于这两个状态之一。没有这两条边，
            // 「正在回滚」这一事实根本无法进入状态机（P1-2）。
            case TOOLS_COMPLETED -> to == TOOLS_RUNNING || to == DRAFT_READY || to == WAITING_APPROVAL
                    || to == COMPENSATING || to == FAILED;
            case DRAFT_READY -> to == SUCCESS || to == WAITING_APPROVAL || to == COMPENSATING || to == FAILED;
            case WAITING_APPROVAL -> to == EXECUTING || to == MANUAL_ESCALATED || to == FAILED;
            case EXECUTING -> to == OBSERVING || to == COMPENSATING || to == FAILED;
            case OBSERVING -> to == SUCCESS || to == COMPENSATING || to == FAILED;
            case COMPENSATING -> to == CLOSED || to == MANUAL_ESCALATED;
            case MANUAL_ESCALATED -> to == CLOSED;
            case SUCCESS, FAILED, CLOSED -> false; // 终态
        };
    }

    /**
     * 获取状态的自然语言描述（用于前端展示/日志）
     */
    public String toLogString() {
        return String.format("[%s] %s - %s", name(), displayName, description);
    }
}