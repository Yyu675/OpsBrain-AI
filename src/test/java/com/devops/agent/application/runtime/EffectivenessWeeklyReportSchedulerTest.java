package com.devops.agent.application.runtime;

import com.devops.agent.domain.notify.Notifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

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

    private EffectivenessWeeklyReportScheduler.SnapshotRow row(
            String date, long postmortems, long finished, long alertSourced, long alerts,
            long diagSuf, long diagTotal, long kbHits, long kbEvidence) {
        return new EffectivenessWeeklyReportScheduler.SnapshotRow(
                LocalDate.parse(date), 100L, finished, postmortems, alertSourced, alerts,
                diagTotal, diagSuf, kbEvidence, kbHits, 5L, 4L, null, false);
    }

    private EffectivenessWeeklyReportScheduler.SnapshotRow withFalsePositive(
            EffectivenessWeeklyReportScheduler.SnapshotRow row, long value) {
        return new EffectivenessWeeklyReportScheduler.SnapshotRow(
                row.snapshotDate(), row.totalTickets(), row.finishedTickets(), row.postmortemCount(),
                row.alertSourcedTickets(), row.alertsTotal(), row.diagnosisTotal(), row.diagnosisSufficient(),
                row.knowledgeEvidence(), row.knowledgeHits(), row.healingTotal(), row.healingSucceeded(),
                value, true);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void whenRows(List<EffectivenessWeeklyReportScheduler.SnapshotRow> rows) {
        doReturn(rows).when(jdbcTemplate).query(anyString(), any(RowMapper.class));
    }

    @Test
    @DisplayName("两周对比：指标改善标↑，恶化标↓，数值来自快照行")
    void buildsWeekOverWeekReport() {
        whenRows(List.of(
                row("2026-09-25", 20, 40, 5, 50, 8, 20, 10, 20),
                row("2026-09-18", 10, 30, 3, 30, 4, 20, 4, 20)));

        String report = scheduler.build();

        assertThat(report).contains("2026-09-18", "2026-09-25");
        assertThat(report).contains("复盘完成率", "告警压缩比", "知识命中率", "诊断充分率");
        assertThat(report).contains("↑");
        assertThat(report).contains("50.0%");
    }

    @Test
    @DisplayName("快照不足两周：不报（不硬凑趋势）")
    void skipsWhenTooFewSnapshots() {
        whenRows(List.of(row("2026-09-25", 20, 40, 5, 50, 8, 20, 10, 20)));
        assertThat(scheduler.build()).isNull();
    }

    @Test
    @DisplayName("误报率：累计误报/累计告警（Feedback 回流口径），有数据才渲染")
    void reportsFalsePositiveRate() {
        var latest = withFalsePositive(row("2026-09-25", 20, 40, 5, 50, 8, 20, 10, 20), 5);
        var before = withFalsePositive(row("2026-09-18", 10, 30, 3, 30, 4, 20, 4, 20), 3);
        whenRows(List.of(latest, before));

        assertThat(scheduler.build()).contains("告警误报率", "10.0%");
    }

    @Test
    @DisplayName("误报列缺数据时整行消失——灰度初期没标注是常态，不渲染 0% 假健康")
    void omitsFalsePositiveWhenNoData() {
        whenRows(List.of(
                row("2026-09-25", 20, 40, 5, 50, 8, 20, 10, 20),
                row("2026-09-18", 10, 30, 3, 30, 4, 20, 4, 20)));
        assertThat(scheduler.build()).doesNotContain("告警误报率");
    }

    @Test
    @DisplayName("指标分母为 0 时跳过该指标，不渲染 0% 假数字")
    void skipsMetricWithZeroDenominator() {
        whenRows(List.of(
                row("2026-09-25", 0, 0, 0, 0, 8, 20, 10, 20),
                row("2026-09-18", 0, 0, 0, 0, 4, 20, 4, 20)));
        String report = scheduler.build();
        assertThat(report).doesNotContain("复盘完成率：");
        assertThat(report).contains("诊断充分率");
    }
}
