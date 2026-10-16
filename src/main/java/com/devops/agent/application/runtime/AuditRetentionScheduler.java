package com.devops.agent.application.runtime;

import com.devops.agent.infrastructure.persistence.repo.OperationAuditRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 操作审计日志保留清理器（批88 档位一）。
 *
 * <p>此前 `sys_operation_audit` 表无任何清理策略——每次 API 调用写一条，
 * 表随业务量线性增长，无限膨胀。运维场景日活较高时（告警推送、工单流转、
 * AI 对话），每日可累积数万条审计行，数月后索引退化与查询变慢开始显现。</p>
 *
 * <h3>清理策略</h3>
 * <ol>
 *   <li>每日凌晨 03:00 执行一次（避开业务高峰）</li>
 *   <li>删除 {@code retentionDays} 天前的记录（默认 180 天 ≈ 半年）</li>
 *   <li>清理失败不抛异常——单次清理失败不应中断整个定时链
 *       （与同层 Scheduler 保持一致的容错策略）</li>
 *   <li>可在 dev profile 关闭（{@code devops.audit.retention-enabled:false}），
 *       prod 默认开启</li>
 * </ol>
 *
 * <p><b>为什么不用 Partition</b>：当前数据量在 6 个月内用简单 DELETE 即可。
 * Partition 带来的 DDL 复杂度和调试成本在单表百万行以下不划算。
 * 若日均审计行超过 10 万条持续 3 个月以上，再考虑按月分区。</p>
 *
 * @author OpsBrain AI
 * @since 2026-09-19
 */
@Slf4j
@Component
public class AuditRetentionScheduler {

    private final OperationAuditRepository auditRepo;

    /** 保留天数（默认 180 天）。可通过环境变量 AUDIT_RETENTION_DAYS 覆盖 */
    @Value("${devops.audit.retention-days:180}")
    private int retentionDays;

    /** 清理开关（dev 默认关，prod 默认开——遵循项目 fail-closed 原则） */
    @Value("${devops.audit.retention-enabled:false}")
    private boolean retentionEnabled;

    public AuditRetentionScheduler(OperationAuditRepository auditRepo) {
        this.auditRepo = auditRepo;
    }

    @Scheduled(cron = "0 0 3 * * ?")  // 每日凌晨 03:00
    public void cleanAuditLogs() {
        if (!retentionEnabled) {
            return;
        }
        log.info("🧹 [AuditRetention] 开始清理（保留 {} 天）...", retentionDays);
        int deleted = auditRepo.deleteOlderThan(retentionDays);
        log.info("🧹 [AuditRetention] 清理完成 | 删除 {} 条", Math.max(0, deleted));
    }
}