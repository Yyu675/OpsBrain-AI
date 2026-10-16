package com.devops.agent.domain.healing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 执行后验证心跳（S3-3/§7.4 3-4.5）：执行后 5 分钟内持续观测。
 * <p>
 * 三个时间参数的分工（都可配置，单元/演示可调小）：
 * <ul>
 *   <li>{@code verify-interval-ms}（默认 60s）——扫描节拍；</li>
 *   <li>{@code verify-settle-seconds}（默认 30s）——沉淀期：刚执行完的指标
 *       还没稳定，此刻验证会把「还在收敛」误判成「没修好」；</li>
 *   <li>{@code verify-observe-seconds}（默认 300s）——观察窗：超出 5 分钟的
 *       陈旧执行不再验，回头验出来的指标没有因果力。</li>
 * </ul>
 * </p>
 * <p>
 * 默认开启但无副作用面：扫描只读台账（SUCCEEDED 且未验证），
 * 空窗期是零成本 no-op；真正触达目标系统的是验证器各自决定。
 * 可通过 {@code devops.healing.verify.enabled=false} 整体关闭。
 * </p>
 */
@Component
@ConditionalOnProperty(name = "devops.healing.verify.enabled", havingValue = "true", matchIfMissing = true)
public class HealingVerificationScheduler {

    private static final Logger log = LoggerFactory.getLogger(HealingVerificationScheduler.class);

    @Value("${devops.healing.verify-settle-seconds:30}")
    private int settleSeconds = 30;

    @Value("${devops.healing.verify-observe-seconds:300}")
    private int observeSeconds = 300;

    @Value("${devops.healing.verify-batch-limit:20}")
    private int batchLimit = 20;

    private final HealingOrchestrator orchestrator;

    public HealingVerificationScheduler(HealingOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @Scheduled(fixedDelayString = "${devops.healing.verify-interval-ms:60000}",
            initialDelayString = "${devops.healing.verify-interval-ms:60000}")
    public void pulse() {
        try {
            orchestrator.verifyPendingBatch(settleSeconds, observeSeconds, batchLimit);
        } catch (Exception ex) {
            log.warn("[HealingVerify] 心跳批次异常（下次节拍再来）| {}", ex.getMessage());
        }
    }
}
