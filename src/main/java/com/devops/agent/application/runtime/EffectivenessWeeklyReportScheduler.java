package com.devops.agent.application.runtime;

import com.devops.agent.domain.notify.Notifier;
import com.devops.agent.domain.notify.NotifyMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * 效能周报（FR-7.3 飞轮报表，2026-09-25）。
 *
 * <p>读 sys_effectiveness_snapshot 的日快照，对比「近 7 天」与「前 7 天」，
 * 把处置效能的变化推到值班群里——效能大盘是人来看的，周报是它来看你的。</p>
 *
 * <p>周报口径全部取自快照行（累计值），不实时重算——快照是唯一的口径事实，
 * 周报和大盘看的是同一份数，不会出现「报表说 A、大盘说 B」。</p>
 *
 * <p>只报有变化的东西：两行快照都没有的指标跳过不渲染。</p>
 */
@Component
public class EffectivenessWeeklyReportScheduler {

    record SnapshotRow(LocalDate snapshotDate, Long totalTickets, Long finishedTickets, Long postmortemCount,
                       Long alertSourcedTickets, Long alertsTotal, Long diagnosisTotal, Long diagnosisSufficient,
                       Long knowledgeEvidence, Long knowledgeHits, Long healingTotal, Long healingSucceeded,
                       Long alertsFalsePositive, boolean hasAlertsFalsePositiveColumn) {}

    private static final Logger log = LoggerFactory.getLogger(EffectivenessWeeklyReportScheduler.class);

    private final JdbcTemplate jdbcTemplate;
    private final Notifier notifier;

    /** 总开关：演练/迁移期可临时停用 */
    @Value("${devops.effectiveness.weekly-report.enabled:true}")
    private boolean enabled;

    public EffectivenessWeeklyReportScheduler(JdbcTemplate jdbcTemplate, Notifier notifier) {
        this.jdbcTemplate = jdbcTemplate;
        this.notifier = notifier;
    }

    /** 每周一 09:30：上周末的故障已经收口，正是看趋势的时候。 */
    @Scheduled(cron = "${devops.effectiveness.weekly-report.cron:0 30 9 * * MON}")
    public void send() {
        if (!enabled) {
            return;
        }
        try {
            String report = build();
            if (report == null) {
                return;   // 快照还太少，攒不满两周对比，静默跳过
            }
            notifier.send(NotifyMessage.normal("📈 OpsBrain 效能周报", report));
            log.info("📈 [WeeklyReport] 效能周报已发送");
        } catch (Exception e) {
            log.error("❌ [WeeklyReport] 周报生成异常: {}", e.getMessage(), e);
        }
    }

    /**
     * 组装周报 markdown。
     *
     * @return 报告正文；快照不足两周对比时返回 null（不硬凑数据）
     */
    String build() {
        List<SnapshotRow> rows = jdbcTemplate.query("""
                SELECT snapshot_date, total_tickets, finished_tickets, postmortem_count,
                       alert_sourced_tickets, alerts_total, diagnosis_total, diagnosis_sufficient,
                       knowledge_evidence, knowledge_hits, healing_total, healing_succeeded,
                       alerts_false_positive
                  FROM sys_effectiveness_snapshot
                 ORDER BY snapshot_date DESC
                 LIMIT 14
                """, (rs, rowNum) -> new SnapshotRow(
                rs.getObject("snapshot_date", LocalDate.class),
                rs.getObject("total_tickets", Long.class),
                rs.getObject("finished_tickets", Long.class),
                rs.getObject("postmortem_count", Long.class),
                rs.getObject("alert_sourced_tickets", Long.class),
                rs.getObject("alerts_total", Long.class),
                rs.getObject("diagnosis_total", Long.class),
                rs.getObject("diagnosis_sufficient", Long.class),
                rs.getObject("knowledge_evidence", Long.class),
                rs.getObject("knowledge_hits", Long.class),
                rs.getObject("healing_total", Long.class),
                rs.getObject("healing_succeeded", Long.class),
                rs.getObject("alerts_false_positive", Long.class), true));
        if (rows.size() < 2) {
            log.info("📈 [WeeklyReport] 快照不足（{} 行），本周不报", rows.size());
            return null;
        }

        SnapshotRow latest = rows.get(0);                       // 最近一天
        SnapshotRow weekAgo = rows.get(Math.min(7, rows.size() - 1)); // 7 天前（不足 7 天取最早）

        StringBuilder md = new StringBuilder();
        md.append("**周期**：").append(weekAgo.snapshotDate()).append(" → ")
                .append(latest.snapshotDate()).append("\n\n");

        reportRate(md, "复盘完成率",
                ratio(latest.postmortemCount(), latest.finishedTickets()),
                ratio(weekAgo.postmortemCount(), weekAgo.finishedTickets()), "%");
        reportRatio(md, "告警压缩比",
                ratio(latest.alertsTotal(), latest.alertSourcedTickets()),
                ratio(weekAgo.alertsTotal(), weekAgo.alertSourcedTickets()), ":1");
        reportRate(md, "知识命中率",
                ratio(latest.knowledgeHits(), latest.knowledgeEvidence()),
                ratio(weekAgo.knowledgeHits(), weekAgo.knowledgeEvidence()), "%");
        reportRate(md, "诊断充分率",
                ratio(latest.diagnosisSufficient(), latest.diagnosisTotal()),
                ratio(weekAgo.diagnosisSufficient(), weekAgo.diagnosisTotal()), "%");
        // 误报率（A1 Feedback 回流闭环，V18 快照列）：累计误报/累计告警。
        // 它不追求当下好看——灰度初期样本小，看的是趋势：该数往上走=阈值有问题，
        // 值班在用标注投票。列缺失（迁移前的旧快照行）整行不渲染。
        Double fpNow = fpRatio(latest);
        Double fpBefore = fpRatio(weekAgo);
        if (fpNow != null || fpBefore != null) {
            reportRate(md, "告警误报率", fpNow, fpBefore, "%");
        }

        md.append("\n**存量**：工单 ").append(num(latest.totalTickets()))
                .append(" ｜ 完结 ").append(num(latest.finishedTickets()))
                .append(" ｜ 复盘 ").append(num(latest.postmortemCount())).append("\n");

        return md.toString();
    }

    /** 比率行：两个时点都有值才报「变化」，否则只报现值（避免假趋势） */
    private void reportRate(StringBuilder md, String label, Double now, Double before, String unit) {
        if (now == null) {
            return;
        }
        double nowPct = now * 100;
        if (before == null) {
            md.append("- **").append(label).append("**：").append(String.format("%.1f", nowPct)).append(unit).append("\n");
            return;
        }
        double diff = (now - before) * 100;
        String arrow = diff > 0.05 ? "↑" : diff < -0.05 ? "↓" : "→";
        md.append("- **").append(label).append("**：").append(String.format("%.1f", nowPct)).append(unit)
                .append("（较上周期 ").append(arrow).append(" ").append(String.format("%.1f", Math.abs(diff))).append(unit).append("）\n");
    }

    /** 比率值行（压缩比这类「x:1」形态） */
    private void reportRatio(StringBuilder md, String label, Double now, Double before, String unit) {
        if (now == null) {
            return;
        }
        if (before == null) {
            md.append("- **").append(label).append("**：").append(String.format("%.1f", now)).append(unit).append("\n");
            return;
        }
        double diff = now - before;
        String arrow = diff > 0.05 ? "↑" : diff < -0.05 ? "↓" : "→";
        md.append("- **").append(label).append("**：").append(String.format("%.1f", now)).append(unit)
                .append("（较上周期 ").append(arrow).append(" ").append(String.format("%.1f", Math.abs(diff))).append(unit).append("）\n");
    }

    private Double ratio(Long numerator, Long denominator) {
        long d = num(denominator);
        return d == 0 ? null : num(numerator) / (double) d;
    }

    /** 误报率：列缺席（V18 前的旧快照行）返回 null 交给上层整行省略，不当 0% 渲染 */
    private Double fpRatio(SnapshotRow row) {
        return row.hasAlertsFalsePositiveColumn()
                ? ratio(row.alertsFalsePositive(), row.alertsTotal())
                : null;
    }

    private long num(Long value) {
        return value == null ? 0L : value;
    }
}
