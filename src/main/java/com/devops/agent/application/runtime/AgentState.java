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

    /**
     * 工具规划中：模型正在决定是否调用工具、调用哪个工具
     */
    TOOLS_PLANNING("工具规划中", "模型推理工具调用"),

    /**
     * 工具执行中：正在调用外部工具（检索/创建工单等）
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
     */
    EXECUTING("执行中", "自愈/写操作进行中"),

    /**
     * 观测中：执行动作已发出，等待结果反馈验证
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
            case CONTEXT_PREPARED -> to == EVIDENCE_READY || to == FAILED;
            case EVIDENCE_READY -> to == TOOLS_PLANNING || to == DRAFT_READY || to == FAILED;
            case TOOLS_PLANNING -> to == TOOLS_RUNNING || to == DRAFT_READY || to == FAILED;
            case TOOLS_RUNNING -> to == TOOLS_COMPLETED || to == FAILED;
            // TOOLS_COMPLETED → TOOLS_RUNNING：多工具场景第二步工具开始执行需此合法边，
            // 否则双工具调用时第二次迁移被 StateManager 判为非法静默丢弃（P1-1）。
            case TOOLS_COMPLETED -> to == TOOLS_RUNNING || to == DRAFT_READY || to == WAITING_APPROVAL || to == FAILED;
            case DRAFT_READY -> to == SUCCESS || to == WAITING_APPROVAL || to == FAILED;
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