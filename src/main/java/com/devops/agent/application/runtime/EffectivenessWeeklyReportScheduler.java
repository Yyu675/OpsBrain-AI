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
import java.util.Map;

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
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT snapshot_date, total_tickets, finished_tickets, postmortem_count,
                       alert_sourced_tickets, alerts_total, diagnosis_total, diagnosis_sufficient,
                       knowledge_evidence, knowledge_hits, healing_total, healing_succeeded
                  FROM sys_effectiveness_snapshot
                 ORDER BY snapshot_date DESC
                 LIMIT 14
                """);
        if (rows.size() < 2) {
            log.info("📈 [WeeklyReport] 快照不足（{} 行），本周不报", rows.size());
            return null;
        }

        Map<String, Object> latest = rows.get(0);                       // 最近一天
        Map<String, Object> weekAgo = rows.get(Math.min(7, rows.size() - 1)); // 7 天前（不足 7 天取最早）

        StringBuilder md = new StringBuilder();
        md.append("**周期**：").append(weekAgo.get("snapshot_date")).append(" → ")
                .append(latest.get("snapshot_date")).append("\n\n");

        reportRate(md, "复盘完成率",
                ratio(latest, "postmortem_count", "finished_tickets"),
                ratio(weekAgo, "postmortem_count", "finished_tickets"), "%");
        reportRatio(md, "告警压缩比",
                ratio(latest, "alerts_total", "alert_sourced_tickets"),
                ratio(weekAgo, "alerts_total", "alert_sourced_tickets"), ":1");
        reportRate(md, "知识命中率",
                ratio(latest, "knowledge_hits", "knowledge_evidence"),
                ratio(weekAgo, "knowledge_hits", "knowledge_evidence"), "%");
        reportRate(md, "诊断充分率",
                ratio(latest, "diagnosis_sufficient", "diagnosis_total"),
                ratio(weekAgo, "diagnosis_sufficient", "diagnosis_total"), "%");

        md.append("\n**存量**：工单 ").append(num(latest, "total_tickets"))
                .append(" ｜ 完结 ").append(num(latest, "finished_tickets"))
                .append(" ｜ 复盘 ").append(num(latest, "postmortem_count")).append("\n");

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

    private Double ratio(Map<String, Object> row, String num, String den) {
        long d = num(row, den);
        return d == 0 ? null : num(row, num) / (double) d;
    }

    private long num(Map<String, Object> row, String key) {
        Object v = row.get(key);
        return v == null ? 0L : ((Number) v).longValue();
    }
}
