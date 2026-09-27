package com.devops.agent.application.runtime;

import com.devops.agent.domain.alert.repository.AlertRepository;
import com.devops.agent.domain.biz.repository.DevOpsTicketRepository;
import com.devops.agent.domain.biz.repository.TicketPostmortemRepository;
import com.devops.agent.domain.healing.HealingExecutionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 效能指标每日快照（效能大盘趋势维度的数据源）。
 *
 * <p>
 * 背景：效能大盘的指标全是「当前值」，治理看的是趋势——复盘率从 0%
 * 涨到 10% 的过程，没有快照就永远只有「现在」。
 * 本调度器每天落一行聚合读数（按日期 UPSERT，重跑覆盖不重复）。
 * </p>
 *
 * <p>口径：全部是「截至当天」的累计口径（不是当日增量）——趋势图读的是
 * 水位变化，增量口径会让「没新工单的平静日」显示成下降。</p>
 */
@Component
public class EffectivenessSnapshotScheduler {

    private static final Logger log = LoggerFactory.getLogger(EffectivenessSnapshotScheduler.class);

    private final JdbcTemplate jdbcTemplate;
    private final DevOpsTicketRepository ticketRepository;
    private final TicketPostmortemRepository postmortemRepository;
    private final AlertRepository alertRepository;
    private final HealingExecutionRepository healingRepository;

    @Value("${devops.effectiveness.snapshot-enabled:true}")
    private boolean enabled;

    public EffectivenessSnapshotScheduler(JdbcTemplate jdbcTemplate,
                                          DevOpsTicketRepository ticketRepository,
                                          TicketPostmortemRepository postmortemRepository,
                                          AlertRepository alertRepository,
                                          HealingExecutionRepository healingRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.ticketRepository = ticketRepository;
        this.postmortemRepository = postmortemRepository;
        this.alertRepository = alertRepository;
        this.healingRepository = healingRepository;
    }

    /** 每天 09:20 落当天快照（与复盘提醒错开，错开整点）。 */
    @Scheduled(cron = "${devops.effectiveness.snapshot-cron:0 20 9 * * *}")
    public void snapshotDaily() {
        if (!enabled) {
            return;
        }
        snapshot(LocalDate.now());
    }

    /** 启动时若当天尚无快照则补一行——新装环境第一天就能看到趋势起点。 */
    @EventListener(ApplicationReadyEvent.class)
    public void snapshotOnBoot() {
        if (!enabled) {
            return;
        }
        try {
            snapshot(LocalDate.now());
        } catch (Exception e) {
            log.warn("⚠️ [EffectivenessSnapshot] 启动快照失败（不影响启动）: {}", e.getMessage());
        }
    }

    /** 落一行当天快照（按日期 UPSERT，幂等）。 */
    public void snapshot(LocalDate day) {
        try {
            long totalTickets = ticketRepository.countAll();
            long finished = ticketRepository.countFinished();
            long postmortems = postmortemRepository.countAll();
            long alertSourced = ticketRepository.countByCreator("alert-bot");
            long alertsTotal = alertRepository.countSince(LocalDate.of(2000, 1, 1).atStartOfDay());
            Map<String, Long> healing = healingRepository.stats();

            // 诊断与知识命中：从证据表聚合（知识方向 SUCCESS / 总知识证据）
            long diagnosisTotal = countSql("SELECT COUNT(*) FROM sys_diagnosis_session");
            long diagnosisSufficient = countSql(
                    "SELECT COUNT(*) FROM sys_diagnosis_session WHERE sufficiency = 'SUFFICIENT'");
            long knowledgeEvidence = countSql(
                    "SELECT COUNT(*) FROM sys_diagnosis_evidence WHERE evidence_type = 'knowledge'");
            long knowledgeHits = countSql(
                    "SELECT COUNT(*) FROM sys_diagnosis_evidence WHERE evidence_type = 'knowledge' AND status = 'SUCCESS'");

            jdbcTemplate.update("""
                    INSERT INTO sys_effectiveness_snapshot
                        (snapshot_date, total_tickets, finished_tickets, postmortem_count,
                         alert_sourced_tickets, alerts_total, diagnosis_total, diagnosis_sufficient,
                         knowledge_evidence, knowledge_hits, healing_total, healing_succeeded)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                    ON CONFLICT (snapshot_date) DO UPDATE SET
                        total_tickets = EXCLUDED.total_tickets,
                        finished_tickets = EXCLUDED.finished_tickets,
                        postmortem_count = EXCLUDED.postmortem_count,
                        alert_sourced_tickets = EXCLUDED.alert_sourced_tickets,
                        alerts_total = EXCLUDED.alerts_total,
                        diagnosis_total = EXCLUDED.diagnosis_total,
                        diagnosis_sufficient = EXCLUDED.diagnosis_sufficient,
                        knowledge_evidence = EXCLUDED.knowledge_evidence,
                        knowledge_hits = EXCLUDED.knowledge_hits,
                        healing_total = EXCLUDED.healing_total,
                        healing_succeeded = EXCLUDED.healing_succeeded
                    """,
                    day, totalTickets, finished, postmortems, alertSourced, alertsTotal,
                    diagnosisTotal, diagnosisSufficient, knowledgeEvidence, knowledgeHits,
                    healing.getOrDefault("total", 0L), healing.getOrDefault("succeeded", 0L));
            log.info("📊 [EffectivenessSnapshot] 快照已落 | {} | 工单={} 复盘={} 告警={}",
                    day, totalTickets, postmortems, alertsTotal);
            // 保留策略：只留近 400 天——趋势图与周报的窗口远小于此，
            // 更老的行只是占地方（与审计表治理同族）
            int pruned = jdbcTemplate.update(
                    "DELETE FROM sys_effectiveness_snapshot WHERE snapshot_date < CURRENT_DATE - 400");
            if (pruned > 0) {
                log.info("🧹 [EffectivenessSnapshot] 清理过期快照 {} 行", pruned);
            }
        } catch (Exception e) {
            // 快照失败不外抛——调度异常会停掉后续调度，指标快照不值得这个代价
            log.error("❌ [EffectivenessSnapshot] 快照失败: {}", e.getMessage(), e);
        }
    }

    /** 趋势查询：近 N 天的快照行（效能大盘趋势图用）。 */
    public List<Map<String, Object>> trend(int days) {
        return jdbcTemplate.queryForList("""
                SELECT snapshot_date, total_tickets, finished_tickets, postmortem_count,
                       alert_sourced_tickets, alerts_total, diagnosis_total, diagnosis_sufficient,
                       knowledge_evidence, knowledge_hits, healing_total, healing_succeeded
                  FROM sys_effectiveness_snapshot
                 WHERE snapshot_date >= CURRENT_DATE - (?::int - 1)
                 ORDER BY snapshot_date
                """, Math.max(1, days));
    }

    private long countSql(String sql) {
        Long n = jdbcTemplate.queryForObject(sql, Long.class);
        return n != null ? n : 0L;
    }
}
