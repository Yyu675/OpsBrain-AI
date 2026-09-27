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
}
