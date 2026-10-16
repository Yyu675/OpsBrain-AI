package com.devops.agent.domain.tools;

/**
 * 工具执行状态机
 * <p>
 * 参考 Agent Methodology §9.5：这不是锦上添花，而是企业级 Agent 的底线。
 * </p>
 * <p>
 * 状态流转：
 * <pre>
 *   PENDING → RUNNING → SUCCESS
 *                     → FAILED
 *                     → PARTIAL_SUCCESS ──┐
 *                                          ├→ COMPENSATING → COMPENSATED
 *   SUCCESS（因后续步骤失败需回滚）────────┘                 → COMPENSATION_FAILED
 *                                                                    ↓
 *                                                    MANUAL_INTERVENTION_REQUIRED
 * </pre>
 * </p>
 *
 * @author OpsBrain AI
 * @since 2026-08-08
 */
public enum ToolExecutionState {

    /** 待执行：已登记但未开始 */
    PENDING("待执行", false, false),

    /** 执行中 */
    RUNNING("执行中", false, false),

    /** 成功：可能因后续步骤失败而需补偿 */
    SUCCESS("成功", true, false),

    /** 失败：未产生副作用，无需补偿 */
    FAILED("失败", true, false),

    /**
     * 部分成功：最危险的状态
     * <p>系统处于"半残"，必须触发补偿</p>
     */
    PARTIAL_SUCCESS("部分成功", false, true),

    /** 补偿中 */
    COMPENSATING("补偿中", false, false),

    /** 补偿完成：副作用已回滚 */
    COMPENSATED("已补偿", true, false),

    /** 补偿失败：需人工介入 */
    COMPENSATION_FAILED("补偿失败", false, true),

    /** 需人工介入：自动化无法收敛 */
    MANUAL_INTERVENTION_REQUIRED("需人工介入", true, true),

    /** 已跳过：如幂等命中、前序失败导致跳过 */
    SKIPPED("已跳过", true, false);

    private final String displayName;
    private final boolean terminal;
    private final boolean needsAttention;

    ToolExecutionState(String displayName, boolean terminal, boolean needsAttention) {
        this.displayName = displayName;
        this.terminal = terminal;
        this.needsAttention = needsAttention;
    }

    public String getDisplayName() { return displayName; }

    /** 是否终态（不再自动流转） */
    public boolean isTerminal() { return terminal; }

    /** 是否需要人工关注（告警/看板高亮） */
    public boolean needsAttention() { return needsAttention; }

    /**
     * 是否为需要补偿的状态
     * <p>
     * SUCCESS 也可能需要补偿——当同一 Saga 内后续步骤失败时，
     * 已成功的步骤要逆序回滚。
     * </p>
     */
    public boolean isCompensable() {
        return this == SUCCESS || this == PARTIAL_SUCCESS;
    }

    /**
     * 校验状态迁移合法性
     */
    public static boolean canTransition(ToolExecutionState from, ToolExecutionState to) {
        if (from == null || to == null) return false;
        // 终态中仅 SUCCESS 可再流转（因后续步骤失败触发补偿）
        if (from.isTerminal() && from != SUCCESS) return false;

        return switch (from) {
            case PENDING -> to == RUNNING || to == SKIPPED || to == FAILED;
            case RUNNING -> to == SUCCESS || to == FAILED || to == PARTIAL_SUCCESS;
            case SUCCESS -> to == COMPENSATING;   // 后续步骤失败，需回滚
            case PARTIAL_SUCCESS -> to == COMPENSATING || to == MANUAL_INTERVENTION_REQUIRED;
            case COMPENSATING -> to == COMPENSATED || to == COMPENSATION_FAILED;
            case COMPENSATION_FAILED -> to == MANUAL_INTERVENTION_REQUIRED || to == COMPENSATING; // 允许重试补偿
            case FAILED, COMPENSATED, MANUAL_INTERVENTION_REQUIRED, SKIPPED -> false;
        };
    }
}