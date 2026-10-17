package com.devops.agent.application.impl;

import com.devops.agent.controller.dto.DiagnosisBoardResponse;
import com.devops.agent.controller.dto.DiagnosisDirectionStat;
import com.devops.agent.controller.dto.DiagnosisSessionTrend;
import com.devops.agent.controller.dto.DiagnosisSessionsStats;
import com.devops.agent.controller.dto.DiagnosisStatusCount;
import com.devops.agent.controller.dto.DiagnosisSufficiencyStat;
import com.devops.agent.controller.dto.HypothesisCalibration;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 诊断看板合并面：将 JDBC 固定行投影合并为既有的强类型看板响应。
 * 统计/舍入/空值和趋势口径均在此由确定性测试钉住。
 */
final class DiagnosisBoardComposer {

    record StatusCountRow(String status, long count) {}
    record SufficiencyCountRow(String sufficiency, long count) {}
    record EvidenceCountRow(String type, String status, long count) {}
    record TrendRow(LocalDate day, long total, long completed) {}

    private static final DateTimeFormatter DAY_LABEL = DateTimeFormatter.ofPattern("MM-dd");

    private DiagnosisBoardComposer() {}

    static DiagnosisBoardResponse compose(List<StatusCountRow> statusRows,
                                          List<SufficiencyCountRow> sufficiencyRows,
                                          List<EvidenceCountRow> evidenceRows,
                                          Double avgDurationSeconds,
                                          int windowDays,
                                          List<TrendRow> trendRows,
                                          LocalDate today,
                                          Double diagnosisCostTotal,
                                          HypothesisCalibration calibration) {
        long total = statusRows.stream().mapToLong(StatusCountRow::count).sum();
        List<DiagnosisStatusCount> byStatus = statusRows.stream()
                .map(r -> new DiagnosisStatusCount(r.status(), r.count())).toList();
        List<DiagnosisSufficiencyStat> sufficiency = sufficiencyRows.stream()
                .map(r -> new DiagnosisSufficiencyStat(r.sufficiency(), r.count())).toList();
        long completedCount = statusRows.stream()
                .filter(r -> "COMPLETED".equals(r.status()))
                .mapToLong(StatusCountRow::count).findFirst().orElse(0L);
        DiagnosisSessionsStats sessions = new DiagnosisSessionsStats(
                total, byStatus,
                avgDurationSeconds == null ? null : round(avgDurationSeconds, 2),
                completedCount == 0 || diagnosisCostTotal == null ? null
                        : round(diagnosisCostTotal / completedCount, 4),
                sufficiency);

        // 方向聚合：[total, SUCCESS, NO_DATA, FAILED, UNAVAILABLE]
        Map<String, long[]> acc = new LinkedHashMap<>();
        for (EvidenceCountRow row : evidenceRows) {
            long[] a = acc.computeIfAbsent(row.type(), k -> new long[5]);
            a[0] += row.count();
            switch (String.valueOf(row.status())) {
                case "SUCCESS" -> a[1] += row.count();
                case "NO_DATA" -> a[2] += row.count();
                case "FAILED" -> a[3] += row.count();
                case "UNAVAILABLE" -> a[4] += row.count();
                default -> { /* 未知状态计入总数但不进桶——不让新状态静默消失 */ }
            }
        }
        List<DiagnosisDirectionStat> directions = new ArrayList<>();
        List<String> attention = new ArrayList<>();
        for (Map.Entry<String, long[]> entry : acc.entrySet()) {
            long[] a = entry.getValue();
            directions.add(new DiagnosisDirectionStat(entry.getKey(), a[0], a[1], a[2], a[3], a[4],
                    a[0] == 0 ? 0.0 : round(a[1] / (double) a[0], 4)));
            if (a[3] + a[4] > 0) attention.add(entry.getKey());
        }

        DiagnosisSessionTrend trend = buildTrend(trendRows, windowDays, today);
        return new DiagnosisBoardResponse(windowDays, sessions, trend, directions, attention, calibration);
    }

    /** 逐日趋势：窗口 [today-windowDays+1, today] 逐日补零，长度恒等于窗口。 */
    private static DiagnosisSessionTrend buildTrend(List<TrendRow> trendRows, int windowDays, LocalDate today) {
        Map<LocalDate, long[]> byDay = new HashMap<>();
        for (TrendRow row : trendRows) byDay.put(row.day(), new long[]{row.total(), row.completed()});
        List<String> days = new ArrayList<>();
        List<Long> created = new ArrayList<>();
        List<Long> completed = new ArrayList<>();
        for (LocalDate day = today.minusDays(windowDays - 1L);
             !day.isAfter(today); day = day.plusDays(1)) {
            long[] values = byDay.get(day);
            days.add(day.format(DAY_LABEL));
            created.add(values == null ? 0L : values[0]);
            completed.add(values == null ? 0L : values[1]);
        }
        return new DiagnosisSessionTrend(days, created, completed);
    }

    private static double round(double value, int decimals) {
        double factor = Math.pow(10, decimals);
        return Math.round(value * factor) / factor;
    }
}
