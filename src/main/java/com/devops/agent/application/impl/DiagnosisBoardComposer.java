package com.devops.agent.application.impl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 诊断看板合并面（S4-4.2 批次 16）：把三条 GROUP BY 查询的原始行
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
 */
final class DiagnosisBoardComposer {

    private DiagnosisBoardComposer() {
    }

    /**
     * @param statusRows        会话状态分布行 [{status, n}]
     * @param sufficiencyRows   已完成的充分性分布行 [{sufficiency, n}]
     * @param evidenceRows      证据方向×状态分布行 [{evidence_type, status, n}]
     * @param avgDurationSeconds 已完成会话平均耗时秒（无完成会话时为 null）
     * @param windowDays        统计窗口天（已夹紧，透传展示）
     */
    static Map<String, Object> compose(List<Map<String, Object>> statusRows,
                                       List<Map<String, Object>> sufficiencyRows,
                                       List<Map<String, Object>> evidenceRows,
                                       Double avgDurationSeconds,
                                       int windowDays) {
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
        return out;
    }

    private static double round(double v, int decimals) {
        double factor = Math.pow(10, decimals);
        return Math.round(v * factor) / factor;
    }
}
