package com.devops.agent.application.impl;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 诊断看板合并面（S4-4.2 批次 16 / S4-4.3 批次 17）：把 GROUP BY 查询的原始行
 * 合成看板载荷的纯计算类——与 DashboardServiceImpl 的 SQL 查询分层，
 * 本类的全部语义可被确定性钉测（SQL 层的正确性归集成测试既有闸）。
 *
 * <h3>方向口径</h3>
 * <ul>
 *   <li>{@code successRate = SUCCESS / total}（NO_DATA 计入分母——
 *       「源健康但没数据」与「取到数」本来就是两回事，不豁免）；</li>
 *   <li>{@code attentionTypes} 只点名 FAILED / UNAVAILABLE&gt;0 的方向——
 *       那是「数据源失败率可见」的验收点；NO_DATA 不点名
 *       （服务可能本来就没事可报，点名会制造假警）。</li>
 * </ul>
 *
 * <h3>趋势口径（S4-4.3）</h3>
 * <ul>
 *   <li>固定周期趋势必须补零（与 getCallTrends 的既有纪律同一条）：
 *       没有诊断发起的日子画 0 点，而不是让折线跨坑跳接——跳接的线
 *       会把「系统闲」误读成「中间数据丢了」；</li>
 *   <li>本类不读时钟：today 由调用方注入，日历语义才能被钉测固定；</li>
 *   <li>越窗行直接丢弃（序列长度恒等于窗口天数，由窗口左右端点定秩序，
 *       不认行自带的日期范围）。</li>
 * </ul>
 */
final class DiagnosisBoardComposer {

    private static final DateTimeFormatter DAY_LABEL = DateTimeFormatter.ofPattern("MM-dd");

    private DiagnosisBoardComposer() {
    }

    /**
     * @param statusRows        会话状态分布行 [{status, n}]
     * @param sufficiencyRows   已完成的充分性分布行 [{sufficiency, n}]
     * @param evidenceRows      证据方向×状态分布行 [{evidence_type, status, n}]
     * @param avgDurationSeconds 已完成会话平均耗时秒（无完成会话时为 null）
     * @param windowDays        统计窗口天（已夹紧，透传展示 + 定趋势长度）
     * @param trendRows         逐日诊断行 [{day, total, completed}]
     * @param today             窗口右端点（含）。注入而非自读：时钟语义可钉测
     */
    static Map<String, Object> compose(List<Map<String, Object>> statusRows,
                                       List<Map<String, Object>> sufficiencyRows,
                                       List<Map<String, Object>> evidenceRows,
                                       Double avgDurationSeconds,
                                       int windowDays,
                                       List<Map<String, Object>> trendRows,
                                       LocalDate today) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("windowDays", windowDays);

        long total = 0;
        List<Map<String, Object>> byStatus = new ArrayList<>();
        for (Map<String, Object> r : statusRows) {
            long n = ((Number) r.get("n")).longValue();
            total += n;
            byStatus.add(Map.of("status", String.valueOf(r.get("status")), "count", n));
        }
        Map<String, Object> sessions = new LinkedHashMap<>();
        sessions.put("total", total);
        sessions.put("byStatus", byStatus);
        sessions.put("avgDurationSeconds",
                avgDurationSeconds == null ? null : round(avgDurationSeconds, 2));
        List<Map<String, Object>> suff = new ArrayList<>();
        for (Map<String, Object> r : sufficiencyRows) {
            suff.add(Map.of("sufficiency", String.valueOf(r.get("sufficiency")),
                    "count", ((Number) r.get("n")).longValue()));
        }
        sessions.put("sufficiency", suff);
        out.put("sessions", sessions);

        // 方向聚合：[total, SUCCESS, NO_DATA, FAILED, UNAVAILABLE]
        Map<String, long[]> acc = new LinkedHashMap<>();
        for (Map<String, Object> r : evidenceRows) {
            String type = String.valueOf(r.get("evidence_type"));
            String status = String.valueOf(r.get("status"));
            long n = ((Number) r.get("n")).longValue();
            long[] a = acc.computeIfAbsent(type, k -> new long[5]);
            a[0] += n;
            switch (status) {
                case "SUCCESS" -> a[1] += n;
                case "NO_DATA" -> a[2] += n;
                case "FAILED" -> a[3] += n;
                case "UNAVAILABLE" -> a[4] += n;
                default -> { /* 未知状态计入总数但不进桶——不让新状态静默消失 */ }
            }
        }
        List<Map<String, Object>> directions = new ArrayList<>();
        List<String> attention = new ArrayList<>();
        for (Map.Entry<String, long[]> e : acc.entrySet()) {
            long[] a = e.getValue();
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("type", e.getKey());
            d.put("total", a[0]);
            d.put("success", a[1]);
            d.put("noData", a[2]);
            d.put("failed", a[3]);
            d.put("unavailable", a[4]);
            d.put("successRate", a[0] == 0 ? 0.0 : round(a[1] / (double) a[0], 4));
            directions.add(d);
            if (a[3] + a[4] > 0) {
                attention.add(e.getKey());
            }
        }
        out.put("evidenceDirections", directions);
        out.put("attentionTypes", attention);

        out.put("sessionTrend", buildTrend(trendRows, windowDays, today));
        return out;
    }

    /** 逐日趋势：窗口 [today-windowDays+1, today] 逐日补零，长度恒等于窗口。 */
    private static Map<String, Object> buildTrend(List<Map<String, Object>> trendRows,
                                                  int windowDays, LocalDate today) {
        Map<LocalDate, long[]> byDay = new HashMap<>();
        for (Map<String, Object> r : trendRows) {
            long[] v = new long[2];
            v[0] = ((Number) r.get("total")).longValue();
            v[1] = ((Number) r.get("completed")).longValue();
            byDay.put(toLocalDate(r.get("day")), v);
        }
        List<String> days = new ArrayList<>();
        List<Long> created = new ArrayList<>();
        List<Long> completed = new ArrayList<>();
        for (LocalDate d = today.minusDays(windowDays - 1L);
             !d.isAfter(today); d = d.plusDays(1)) {
            long[] v = byDay.get(d);
            days.add(d.format(DAY_LABEL));
            created.add(v == null ? 0L : v[0]);
            completed.add(v == null ? 0L : v[1]);
        }
        Map<String, Object> trend = new LinkedHashMap<>();
        trend.put("days", days);
        trend.put("created", created);
        trend.put("completed", completed);
        return trend;
    }

    /** 防御日期列的三种可能包裹：java.sql.Date / LocalDate / yyyy-MM-dd 字符串。 */
    private static LocalDate toLocalDate(Object v) {
        if (v instanceof LocalDate d) {
            return d;
        }
        if (v instanceof java.sql.Date d) {
            return d.toLocalDate();
        }
        return LocalDate.parse(String.valueOf(v));
    }

    private static double round(double v, int decimals) {
        double factor = Math.pow(10, decimals);
        return Math.round(v * factor) / factor;
    }
}
