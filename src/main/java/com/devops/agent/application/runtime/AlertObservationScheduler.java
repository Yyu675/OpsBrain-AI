package com.devops.agent.application.runtime;

import com.devops.agent.domain.alert.service.AlertService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 自愈观察窗调度器（PRD FR-3.1，2026-09-27）。
 *
 * <p>
 * 背景：warning 级（P2/P3）告警里大量是「三分钟自己好」的瞬时抖动——
 * 连接池瞬时打满、GC 停顿、单次超时。它们触发即建单的话，
 * 工单系统会被噪声淹没（真实教训：真实库 27/28 张工单无人认领）。
 * </p>
 *
 * <p>机制：</p>
 * <ul>
 *   <li>观察级告警入库后<b>不立即建单</b>（AlertService.isObservationPending），
 *       进入默认 10 分钟的自愈观察窗；</li>
 *   <li>窗口内 resolved 回流 → 标记 RESOLVED、只留统计不建单
 *       （自愈率 = 观察级中 RESOLVED 且 ticket_id 为空的占比，可口径化到效能大盘）；</li>
 *   <li>窗口到期仍未愈 → 本调度器捞出来，经 AlertService.createDelayedTickets
 *       补建工单并补触发诊断（Single Writer：建单仍只走 AlertService 一条路径）。</li>
 * </ul>
 *
 * <p>
 * P0/P1 高危告警不走观察窗——高危等不起一个窗口。
 * 单实例假设与 PipelineHeartbeatScheduler 相同（@Scheduled 单线程驱动）；
 * 多副本部署时需另加分布式锁。
 * </p>
 */
@Component
public class AlertObservationScheduler {

    private static final Logger log = LoggerFactory.getLogger(AlertObservationScheduler.class);

    private final AlertService alertService;

    /** 总开关。与 AlertService 侧同名配置保持一致；数据迁移/演练期间可整体停用。 */
    @Value("${devops.alert.observation-enabled:true}")
    private boolean enabled;

    public AlertObservationScheduler(AlertService alertService) {
        this.alertService = alertService;
    }

    @Scheduled(fixedDelayString = "${devops.alert.observation-scan-interval-ms:60000}")
    public void scan() {
        if (!enabled) {
            return;
        }
        try {
            alertService.createDelayedTickets();
        } catch (Exception e) {
            // 扫描失败只留日志——下轮（1 分钟后）会重试，观察窗告警不因一次失败丢单
            log.error("❌ [AlertObservation] 观察窗扫描失败（下轮重试）| error={}", e.getMessage(), e);
        }
    }
}
