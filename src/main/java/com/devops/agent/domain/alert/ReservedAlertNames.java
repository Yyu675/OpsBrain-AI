package com.devops.agent.domain.alert;

/**
 * 告警域的保留告警名（管道自检信号，与业务告警区分）。
 *
 * <p>放在 domain 层而非调度器里：看门狗名同时被「报警方」
 * （PipelineHeartbeatScheduler，application 层）与「展示方」
 * （AlertQueryService，domain 层）引用——常量跟着语义走，不该跟着
 * 某一个使用方走，否则低层会反向依赖高层。</p>
 */
public final class ReservedAlertNames {

    private ReservedAlertNames() {}

    /**
     * 看门狗告警名（与 monitoring/alert.rules.yml 的 OpsBrainPipelineWatchdog 对齐）。
     * 恒真告警：存在即证明 Prometheus→Alertmanager→webhook 管道存活。
     */
    public static final String PIPELINE_WATCHDOG = "OpsBrainPipelineWatchdog";

    /** 管道静默元告警名：看门狗停跳超阈值时由调度器构造上报。 */
    public static final String PIPELINE_SILENT = "OpsBrainPipelineSilent";

    /**
     * 告警风暴摘要事件名（FR-2.5）：风暴模式期间被抑制的低级别告警
     * 聚合成这一条（固定去重键 → 整场风暴只活跃一条、只建一单），
     * 速率回落后由风暴守卫自动标记恢复。
     */
    public static final String STORM_SUMMARY = "OpsBrainAlertStormSummary";

    /** 日志管道静默元告警名（LogsPipelineWatchdogScheduler 构造上报）。 */
    public static final String LOGS_SILENT = "OpsBrainLogsSilent";

    /**
     * 通知管道静默元告警名（NotifySilentWatchdogScheduler 构造上报，评审项③）。
     * 配了渠道但窗口内发送成功数为 0 时上报——「P0 提醒发不出去」是比
     * 任何单条业务告警都紧急的信号。
     */
    public static final String NOTIFY_SILENT = "OpsBrainNotifySilent";

    /**
     * 是否平台保留信号（恒真心跳 / 静默元告警 / 风暴摘要 / 日志-通知静默）。
     * <p>它们报的是<b>平台自身健康</b>而非业务故障：诊断跳过（对管道告警跑 LLM
     * 只会烧钱得 NO_DATA）、风暴期豁免放行（平台自监控在风暴里更要可见）。
     * 新增保留名必须同步进本集合——2026-10-01 前 LOGS_SILENT 就漏在两处名单外，
     * 是「常量加了但使用方各写各的」这种发散的直接后果。</p>
     */
    public static boolean isReserved(String name) {
        return PIPELINE_WATCHDOG.equals(name)
                || PIPELINE_SILENT.equals(name)
                || STORM_SUMMARY.equals(name)
                || LOGS_SILENT.equals(name)
                || NOTIFY_SILENT.equals(name);
    }
}
