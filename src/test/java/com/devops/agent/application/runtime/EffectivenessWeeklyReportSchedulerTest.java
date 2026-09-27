package com.devops.agent.application.runtime;

import com.devops.agent.domain.notify.Notifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 效能周报：守护的是「快照是口径事实」这条约定——
 * 报告的每一个数都必须来自快照行，不能有第二个计算口径。
 */
@DisplayName("效能周报（快照口径）")
class EffectivenessWeeklyReportSchedulerTest {

    private JdbcTemplate jdbcTemplate;
    private Notifier notifier;
    private EffectivenessWeeklyReportScheduler scheduler;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        notifier = mock(Notifier.class);
        scheduler = new EffectivenessWeeklyReportScheduler(jdbcTemplate, notifier);
        ReflectionTestUtils.setField(scheduler, "enabled", true);
    }

    private Map<String, Object> row(String date, long postmortems, long finished,
                                    long alertSourced, long alerts, long diagSuf, long diagTotal,
                                    long kbHits, long kbEvidence) {
        Map<String, Object> m = new HashMap<>();
        m.put("snapshot_date", LocalDate.parse(date));
        m.put("total_tickets", 100L);
        m.put("finished_tickets", finished);
        m.put("postmortem_count", postmortems);
        m.put("alert_sourced_tickets", alertSourced);
        m.put("alerts_total", alerts);
        m.put("diagnosis_total", diagTotal);
        m.put("diagnosis_sufficient", diagSuf);
        m.put("knowledge_evidence", kbEvidence);
        m.put("knowledge_hits", kbHits);
        m.put("healing_total", 5L);
        m.put("healing_succeeded", 4L);
        return m;
    }

    @Test
    @DisplayName("两周对比：指标改善标↑，恶化标↓，数值来自快照行")
    void buildsWeekOverWeekReport() {
        when(jdbcTemplate.queryForList(anyString())).thenReturn(List.of(
                // 最新（近端）：复盘率升、压缩比升、知识命中率升
                row("2026-09-25", 20, 40, 5, 50, 8, 20, 10, 20),
                // 一周前（远端）：复盘率低、压缩比低、知识命中率低
                row("2026-09-18", 10, 30, 3, 30, 4, 20, 4, 20)
        ));

        String report = scheduler.build();

        assertThat(report).contains("2026-09-18", "2026-09-25");
        assertThat(report).contains("复盘完成率", "告警压缩比", "知识命中率", "诊断充分率");
        assertThat(report).contains("↑");  // 全部改善
        // 复盘率 50% vs 33%，压缩比 10:1 vs 10:1，知识命中率 50% vs 20%
        assertThat(report).contains("50.0%");
    }

    @Test
    @DisplayName("快照不足两周：不报（不硬凑趋势）")
    void skipsWhenTooFewSnapshots() {
        when(jdbcTemplate.queryForList(anyString())).thenReturn(List.of(
                row("2026-09-25", 20, 40, 5, 50, 8, 20, 10, 20)
        ));
        assertThat(scheduler.build()).isNull();
    }

    @Test
    @DisplayName("指标分母为 0 时跳过该指标，不渲染 0% 假数字")
    void skipsMetricWithZeroDenominator() {
        when(jdbcTemplate.queryForList(anyString())).thenReturn(List.of(
                row("2026-09-25", 0, 0, 0, 0, 8, 20, 10, 20),   // finished=0 → 复盘率没法算
                row("2026-09-18", 0, 0, 0, 0, 4, 20, 4, 20)
        ));
        String report = scheduler.build();
        assertThat(report).doesNotContain("复盘完成率：");
        assertThat(report).contains("诊断充分率");   // 有分母的照常报
    }
}
