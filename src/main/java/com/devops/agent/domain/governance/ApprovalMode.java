package com.devops.agent.domain.governance;

/**
 * 审批门槛模式（L3）。
 *
 * <p>回答「这个风险等级的动作，需要几个人点头才能执行」。</p>
 *
 * <h3>为什么用枚举而不是「需要几人审批」的整数</h3>
 * 整数看起来更通用（想要三人审批就填 3），但审批人数不是连续量：
 * <ul>
 *   <li>0 与 1 的差别是「要不要人」——涉及是否创建审批单、是否发通知、
 *       是否有超时逻辑，是完全不同的代码路径；</li>
 *   <li>1 与 2 的差别是「要不要第二双眼睛」——四眼原则要求两个审批人
 *       <b>不能是同一个人</b>，这条约束用整数表达不出来。</li>
 * </ul>
 * 而 3 人以上的审批在运维场景没有实际需求（故障当下没人等得起）。
 * 枚举把有限的真实选项固定下来，也让前端可以直接渲染成三个单选项
 * 而不是一个能填 -1 的数字框。
 *
 * @author OpsBrain AI
 * @since 2026-08-25
 */
public enum ApprovalMode {

    /** 免审批：引擎可直接执行（仅适用于无副作用或可自愈的低风险动作） */
    NONE("免审批", 0),

    /** 单人审批：任一管理员批准即可 */
    SINGLE("单人审批", 1),

    /**
     * 双人审批（四眼原则）：需两名<b>不同</b>管理员分别批准。
     * <p>用于不可逆的高危动作——单人失误的代价过高。</p>
     */
    DUAL("双人审批", 2);

    private final String displayName;

    /** 需要的审批人数。供编排层判断「够不够」，不作为可配置项 */
    private final int requiredApprovers;

    ApprovalMode(String displayName, int requiredApprovers) {
        this.displayName = displayName;
        this.requiredApprovers = requiredApprovers;
    }

    public String getDisplayName() {
        return displayName;
    }

    public int getRequiredApprovers() {
        return requiredApprovers;
    }

    /** 是否需要人工介入。等价于 {@code this != NONE}，但语义更直白 */
    public boolean requiresHuman() {
        return requiredApprovers > 0;
    }

    /**
     * 宽松解析。
     *
     * <p>无法识别时返回 {@link #DUAL} 而非 null 或 NONE——
     * 配置读取失败时应当落到<b>最严格</b>的一档。若回退成 NONE，
     * 一次数据脏值就会让高危动作变成免审批直接执行。
     * 安全相关的默认值必须朝安全的方向倒。</p>
     */
    public static ApprovalMode parseOrStrictest(String raw) {
        if (raw == null || raw.isBlank()) {
            return DUAL;
        }
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return DUAL;
        }
    }
}
