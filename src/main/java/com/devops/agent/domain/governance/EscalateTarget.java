package com.devops.agent.domain.governance;

/**
 * 自愈失败后的升级目标（L3）。
 *
 * <p>对齐蓝图 §二：P2/P3 自动修复后「异常则升级 P1 人工介入」。
 * 这里定义「升级」具体做什么。</p>
 *
 * @author OpsBrain AI
 * @since 2026-08-25
 */
public enum EscalateTarget {

    /**
     * 仅记录，不打扰人。
     * <p>只读动作失败没有升级的必要——重试或降级即可，
     * 为一次查询失败呼叫值班工程师是噪音。</p>
     */
    NONE("仅记录"),

    /** 自动开工单，进入常规工单流转 */
    TICKET("自动开工单"),

    /**
     * 呼叫值班（钉钉/飞书强提醒）。
     * <p>用于高危动作失败——此时系统可能处于「改了一半」的中间态，
     * 等人上班再看的代价不可接受。</p>
     */
    ONCALL("呼叫值班");

    private final String displayName;

    EscalateTarget(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    /**
     * 宽松解析，无法识别时回退 {@link #TICKET}。
     *
     * <p>回退到 TICKET 而非 NONE：脏数据导致「静默不升级」，
     * 意味着一次失败的自愈动作无人知晓——这正是自动化最危险的失效模式。
     * 开一张工单是可承受的噪音，漏报不是。</p>
     */
    public static EscalateTarget parseOrDefault(String raw) {
        if (raw == null || raw.isBlank()) {
            return TICKET;
        }
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return TICKET;
        }
    }
}
